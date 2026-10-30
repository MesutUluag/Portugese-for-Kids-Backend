package com.mesutuluag.portugeseforkidsbackend.story;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesutuluag.portugeseforkidsbackend.exception.StoryGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private StoryService storyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        Map<String, String> prompts = Map.of(
                "school", "System prompt for school",
                "restaurant", "System prompt for restaurant"
        );
        storyService = new StoryService(chatClient, prompts, 300, 300, 200, 10);
    }

    private void stubChatClient(String returnContent) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(returnContent);
    }

    @Test
    void generateStory_happyPath_returnsValidResponse() throws Exception {
        StoryPage page = new StoryPage(
                "Bom dia!", "Good morning!", "👋", "🏫", "📚", "child in school"
        );
        stubChatClient(objectMapper.writeValueAsString(page));

        StoryRequest request = new StoryRequest();
        request.setPrompt("greet");
        request.setContext("school");

        StoryResponse response = storyService.generateStory(request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotBlank();
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Bom dia!");
    }

    @Test
    void generateStory_stripsMarkdownFences() throws Exception {
        StoryPage page = new StoryPage(
                "Olá!", "Hello!", "😊", "🏫", "🎒", "child greeting"
        );
        String fencedJson = "```json\n" + objectMapper.writeValueAsString(page) + "\n```";
        stubChatClient(fencedJson);

        StoryRequest request = new StoryRequest();
        request.setPrompt("say hello");

        StoryResponse response = storyService.generateStory(request);
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Olá!");
    }

    @Test
    void generateStory_extractsJsonFromSurroundingText() throws Exception {
        StoryPage page = new StoryPage(
                "Tudo bem?", "Everything good?", "👍", "🏫", "🎒", "smiling child"
        );
        String wrapped = "Here is the story you requested:\n" + objectMapper.writeValueAsString(page) + "\nHope you enjoy!";
        stubChatClient(wrapped);

        StoryRequest request = new StoryRequest();
        request.setPrompt("greeting");

        StoryResponse response = storyService.generateStory(request);
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Tudo bem?");
    }

    @Test
    void generateStory_duplicateKeysInJson_toleratedByLenientMapper() throws Exception {
        String duplicateKeyJson = """
                {
                    "pt": "Primeiro",
                    "pt": "Segundo",
                    "en": "Second",
                    "mainEmoji": "⭐",
                    "bgLeft": "🏫",
                    "bgRight": "📚",
                    "imagePrompt": "prompt"
                }
                """;
        stubChatClient(duplicateKeyJson);

        StoryRequest request = new StoryRequest();
        request.setPrompt("duplicate key test");

        StoryResponse response = storyService.generateStory(request);
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Segundo");
    }

    @Test
    void generateStory_contextRouting_usesRestaurantPrompt() {
        StoryPage page = new StoryPage("A conta", "The bill", "🍽️", "🍷", "👨‍🍳", "waiter");
        stubChatClient(
                "{\"pt\":\"A conta\",\"en\":\"The bill\",\"mainEmoji\":\"🍽️\",\"bgLeft\":\"🍷\",\"bgRight\":\"👨‍🍳\",\"imagePrompt\":\"waiter\"}"
        );

        StoryRequest request = new StoryRequest();
        request.setContext("restaurant");

        storyService.generateStory(request);
        verify(requestSpec).system("System prompt for restaurant");
    }

    @Test
    void generateStory_unknownContext_defaultsToSchool() {
        stubChatClient(
                "{\"pt\":\"Olá\",\"en\":\"Hi\",\"mainEmoji\":\"👋\",\"bgLeft\":\"🏫\",\"bgRight\":\"📚\",\"imagePrompt\":\"test\"}"
        );

        StoryRequest request = new StoryRequest();
        request.setContext("outer-space");

        storyService.generateStory(request);
        verify(requestSpec).system("System prompt for school");
    }

    @Test
    void generateStory_conversationHistory_trimmedToMax10Items() {
        stubChatClient(
                "{\"pt\":\"Sim\",\"en\":\"Yes\",\"mainEmoji\":\"👍\",\"bgLeft\":\"🏫\",\"bgRight\":\"📚\",\"imagePrompt\":\"test\"}"
        );

        StoryRequest request = new StoryRequest();
        request.setPrompt("Next turn");
        List<String> fifteenItems = List.of(
                "Turn 1", "Turn 2", "Turn 3", "Turn 4", "Turn 5",
                "Turn 6", "Turn 7", "Turn 8", "Turn 9", "Turn 10",
                "Turn 11", "Turn 12", "Turn 13", "Turn 14", "Turn 15"
        );
        request.setConversationHistory(fifteenItems);

        storyService.generateStory(request);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPromptCaptor.capture());
        String assembledPrompt = userPromptCaptor.getValue();

        assertThat(assembledPrompt).doesNotContain("Turn 1\n");
        assertThat(assembledPrompt).doesNotContain("Turn 5\n");
        assertThat(assembledPrompt).contains("Turn 6");
        assertThat(assembledPrompt).contains("Turn 15");
    }

    @Test
    void generateStory_previousSentence_appendsInstruction() {
        stubChatClient(
                "{\"pt\":\"Sim\",\"en\":\"Yes\",\"mainEmoji\":\"👍\",\"bgLeft\":\"🏫\",\"bgRight\":\"📚\",\"imagePrompt\":\"test\"}"
        );

        StoryRequest request = new StoryRequest();
        request.setPrompt("Continue");
        request.setPreviousSentence("Como te chamas?");

        storyService.generateStory(request);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPromptCaptor.capture());
        String assembledPrompt = userPromptCaptor.getValue();

        assertThat(assembledPrompt).contains("The previous sentence was: \"Como te chamas?\"");
    }

    @Test
    void generateStory_nullPrompt_doesNotPrependLiteralNull() {
        stubChatClient(
                "{\"pt\":\"Olá\",\"en\":\"Hello\",\"mainEmoji\":\"👋\",\"bgLeft\":\"🏫\",\"bgRight\":\"📚\",\"imagePrompt\":\"test\"}"
        );

        StoryRequest request = new StoryRequest();
        request.setPrompt(null);
        request.setPreviousSentence("Bom dia");

        storyService.generateStory(request);

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userPromptCaptor.capture());
        String assembledPrompt = userPromptCaptor.getValue();

        assertThat(assembledPrompt).doesNotStartWith("null");
        assertThat(assembledPrompt).startsWith("The previous sentence was: \"Bom dia\"");
    }

    @Test
    void generateStory_emptyLlmContent_throwsStoryGenerationException() {
        stubChatClient("");

        StoryRequest request = new StoryRequest();
        request.setPrompt("test");

        assertThatThrownBy(() -> storyService.generateStory(request))
                .isInstanceOf(StoryGenerationException.class)
                .hasMessageContaining("empty or null content");
    }

    @Test
    void generateStory_malformedLlmOutput_throwsStoryGenerationException() {
        stubChatClient("This is definitely not JSON");

        StoryRequest request = new StoryRequest();
        request.setPrompt("test");

        assertThatThrownBy(() -> storyService.generateStory(request))
                .isInstanceOf(StoryGenerationException.class)
                .hasMessageContaining("Malformed LLM response");
    }

    @Test
    void generateStory_validation_promptTooLong_throwsIllegalArgumentException() {
        StoryRequest request = new StoryRequest();
        request.setPrompt("a".repeat(301));

        assertThatThrownBy(() -> storyService.generateStory(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt must not exceed 300 characters");
    }

    @Test
    void generateStory_validation_previousSentenceTooLong_throwsIllegalArgumentException() {
        StoryRequest request = new StoryRequest();
        request.setPreviousSentence("b".repeat(301));

        assertThatThrownBy(() -> storyService.generateStory(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("previousSentence must not exceed 300 characters");
    }

    @Test
    void generateStory_validation_historyItemTooLong_throwsIllegalArgumentException() {
        StoryRequest request = new StoryRequest();
        request.setConversationHistory(List.of("short", "c".repeat(201)));

        assertThatThrownBy(() -> storyService.generateStory(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationHistory items must not exceed 200 characters");
    }
}
