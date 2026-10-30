package com.mesutuluag.portugeseforkidsbackend.story;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesutuluag.portugeseforkidsbackend.exception.StoryGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class StoryService {

    private static final Logger log = LoggerFactory.getLogger(StoryService.class);

    private static final String DEFAULT_CONTEXT = "school";

    private final ChatClient chatClient;
    private final Map<String, String> systemPrompts;
    private final ObjectMapper objectMapper;
    private final int maxPromptLength;
    private final int maxPreviousSentenceLength;
    private final int maxHistoryItemLength;
    private final int maxConversationHistory;

    @Autowired
    public StoryService(
            ChatClient.Builder chatClientBuilder,
            @Value("${story.validation.max-prompt-length:300}") int maxPromptLength,
            @Value("${story.validation.max-previous-sentence-length:300}") int maxPreviousSentenceLength,
            @Value("${story.validation.max-history-item-length:200}") int maxHistoryItemLength,
            @Value("${story.history.max-items:10}") int maxConversationHistory) throws IOException {
        this(chatClientBuilder.build(), loadAllPrompts(), maxPromptLength, maxPreviousSentenceLength, maxHistoryItemLength, maxConversationHistory);
    }

    public StoryService(
            ChatClient chatClient,
            Map<String, String> systemPrompts) {
        this(chatClient, systemPrompts, 300, 300, 200, 10);
    }

    public StoryService(
            ChatClient chatClient,
            Map<String, String> systemPrompts,
            int maxPromptLength,
            int maxPreviousSentenceLength,
            int maxHistoryItemLength,
            int maxConversationHistory) {
        this.chatClient = chatClient;
        this.systemPrompts = systemPrompts != null ? systemPrompts : new HashMap<>();
        this.maxPromptLength = maxPromptLength;
        this.maxPreviousSentenceLength = maxPreviousSentenceLength;
        this.maxHistoryItemLength = maxHistoryItemLength;
        this.maxConversationHistory = maxConversationHistory;
        this.objectMapper = new ObjectMapper()
                .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static Map<String, String> loadAllPrompts() throws IOException {
        Map<String, String> prompts = new HashMap<>();
        String[] contexts = {
                "school", "restaurant", "bank", "hospital", "cafe",
                "airport", "market", "aima", "bus", "pharmacy", "gas_station", "traffic"
        };
        for (String ctx : contexts) {
            prompts.put(ctx, loadPrompt("story-system-prompt-" + ctx + ".md"));
        }
        return prompts;
    }

    private static String loadPrompt(String filename) throws IOException {
        return new ClassPathResource("prompts/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public StoryResponse generateStory(StoryRequest request) {
        validateRequest(request);

        String context = (request != null && request.getContext() != null && systemPrompts.containsKey(request.getContext()))
                ? request.getContext()
                : DEFAULT_CONTEXT;

        String userPrompt = buildUserPrompt(request);

        log.debug("[story-service] Calling LLM with context='{}' promptLength={}", context, userPrompt.length());
        String raw;
        try {
            raw = chatClient.prompt()
                    .system(systemPrompts.getOrDefault(context, ""))
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[story-service] ChatClient call threw exception: {}", e.getMessage(), e);
            throw new StoryGenerationException("LLM call failed: " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            log.error("[story-service] ChatClient returned null or blank content for context='{}'", context);
            throw new StoryGenerationException("LLM returned empty or null content");
        }

        // Strip markdown code fences the LLM sometimes wraps around JSON
        String json = raw.replaceAll("(?s)```json\\s*|```", "").trim();
        int start = json.indexOf('{');
        int end   = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = json.substring(start, end + 1);
        }

        try {
            StoryPage page = objectMapper.readValue(json, StoryPage.class);
            return new StoryResponse(objectMapper.writeValueAsString(page));
        } catch (Exception e) {
            String snippet = raw.substring(0, Math.min(200, raw.length()));
            log.error("[story-service] Failed to parse LLM JSON output (snippet='{}'): {}", snippet, e.getMessage());
            throw new StoryGenerationException("Malformed LLM response: " + e.getMessage(), e);
        }
    }

    private void validateRequest(StoryRequest request) {
        if (request == null) {
            return;
        }
        if (request.getPrompt() != null && request.getPrompt().length() > maxPromptLength) {
            throw new IllegalArgumentException("prompt must not exceed " + maxPromptLength + " characters");
        }
        if (request.getPreviousSentence() != null && request.getPreviousSentence().length() > maxPreviousSentenceLength) {
            throw new IllegalArgumentException("previousSentence must not exceed " + maxPreviousSentenceLength + " characters");
        }
        if (request.getConversationHistory() != null) {
            for (String item : request.getConversationHistory()) {
                if (item != null && item.length() > maxHistoryItemLength) {
                    throw new IllegalArgumentException("conversationHistory items must not exceed " + maxHistoryItemLength + " characters");
                }
            }
        }
    }

    private String buildUserPrompt(StoryRequest request) {
        if (request == null) {
            return "";
        }
        String userPrompt = Objects.requireNonNullElse(request.getPrompt(), "");

        if (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty()) {
            List<String> history = request.getConversationHistory();
            int fromIndex = Math.max(0, history.size() - maxConversationHistory);
            List<String> trimmedHistory = history.subList(fromIndex, history.size());
            String historySection = "Conversation so far (do NOT repeat any of these):\n"
                    + String.join("\n", trimmedHistory.stream()
                            .map(s -> "- " + s)
                            .toList());
            userPrompt = userPrompt.isEmpty() ? historySection : userPrompt + "\n\n" + historySection;
        }

        if (request.getPreviousSentence() != null && !request.getPreviousSentence().isBlank()) {
            String prevSection = "The previous sentence was: \"" + request.getPreviousSentence()
                    + "\". Generate a logical, direct, short reply from the other speaker answering or continuing directly from that sentence.";
            userPrompt = userPrompt.isEmpty() ? prevSection : userPrompt + "\n\n" + prevSection;
        }

        return userPrompt;
    }
}
