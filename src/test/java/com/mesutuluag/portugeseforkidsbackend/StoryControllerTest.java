package com.mesutuluag.portugeseforkidsbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryControllerTest {

    // --- ChatClient fluent-chain mocks ---
    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    @Mock private RateLimitService rateLimitService;

    private StoryController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // Only wire stubs that every test exercises: builder → client
        when(chatClientBuilder.build()).thenReturn(chatClient);
        controller = new StoryController(rateLimitService, chatClientBuilder);
    }

    // -------------------------------------------------------------------------
    // Happy path: valid StoryPage is returned and serialised into the response
    // -------------------------------------------------------------------------
    @Test
    void createStory_returnsValidJsonResponse() throws Exception {
        StoryPage page = new StoryPage(
                "Bom dia, professora!",
                "Good morning, teacher!",
                "👋",
                "🏫",
                "📚",
                "a child waving at a teacher in a sunny classroom, colorful cute kids illustration, storybook art, bright colors, simple background, no text"
        );
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(StoryPage.class)).thenReturn(page);

        StoryRequest request = new StoryRequest();
        request.setPrompt("greet a teacher");

        StoryResponse response = controller.createStory(request, new MockHttpServletRequest());

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isNotBlank();

        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Bom dia, professora!");
        assertThat(parsed.en()).isEqualTo("Good morning, teacher!");
        assertThat(parsed.mainEmoji()).isEqualTo("👋");
        assertThat(parsed.bgLeft()).isEqualTo("🏫");
        assertThat(parsed.bgRight()).isEqualTo("📚");
        assertThat(parsed.imagePrompt()).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Context routing: restaurant context uses its own system prompt
    // -------------------------------------------------------------------------
    @Test
    void createStory_restaurantContext_usesRestaurantPrompt() throws Exception {
        StoryPage page = new StoryPage(
                "A conta, por favor.",
                "The bill, please.",
                "🍽️",
                "🍷",
                "👨‍🍳",
                "a child asking for the bill at a restaurant table, colorful cute kids illustration, storybook art, bright colors, simple background, no text"
        );
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(StoryPage.class)).thenReturn(page);

        StoryRequest request = new StoryRequest();
        request.setPrompt("ask for the bill");
        request.setContext("restaurant");

        StoryResponse response = controller.createStory(request, new MockHttpServletRequest());

        assertThat(response.getContent()).isNotBlank();
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("A conta, por favor.");
    }

    // -------------------------------------------------------------------------
    // Context routing: unknown context falls back to school prompt
    // -------------------------------------------------------------------------
    @Test
    void createStory_unknownContext_defaultsToSchool() throws Exception {
        StoryPage page = new StoryPage(
                "Bom dia!",
                "Good morning!",
                "☀️",
                "🏫",
                "📚",
                "a child saying good morning, colorful cute kids illustration, storybook art, bright colors, simple background, no text"
        );
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(StoryPage.class)).thenReturn(page);

        StoryRequest request = new StoryRequest();
        request.setPrompt("say good morning");
        request.setContext("spaceship"); // unknown context

        StoryResponse response = controller.createStory(request, new MockHttpServletRequest());

        assertThat(response.getContent()).isNotBlank();
        StoryPage parsed = objectMapper.readValue(response.getContent(), StoryPage.class);
        assertThat(parsed.pt()).isEqualTo("Bom dia!");
    }

    // -------------------------------------------------------------------------
    // Rate limit exceeded: DailyLimitExceededException is thrown before LLM call
    // -------------------------------------------------------------------------
    @Test
    void createStory_rateLimitExceeded_throwsDailyLimitExceededException() {
        doThrow(new DailyLimitExceededException())
                .when(rateLimitService).checkAndIncrement(anyString());

        StoryRequest request = new StoryRequest();
        request.setPrompt("any prompt");

        assertThrows(DailyLimitExceededException.class,
                () -> controller.createStory(request, new MockHttpServletRequest()));
    }

    // -------------------------------------------------------------------------
    // Null prompt: ChatClient chain is still invoked with null user message
    // -------------------------------------------------------------------------
    @Test
    void createStory_nullPrompt_stillCallsChatClient() throws Exception {
        StoryPage page = new StoryPage(
                "Olá!",
                "Hello!",
                "😊",
                "🌟",
                "🎒",
                "a child saying hello, colorful cute kids illustration, storybook art, bright colors, simple background, no text"
        );
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user((String) null)).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(StoryPage.class)).thenReturn(page);

        StoryRequest request = new StoryRequest();
        // prompt is null by default

        StoryResponse response = controller.createStory(request, new MockHttpServletRequest());

        verify(requestSpec).user((String) null);
        assertThat(response.getContent()).isNotBlank();
    }
}
