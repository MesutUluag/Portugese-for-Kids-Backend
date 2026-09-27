package com.mesutuluag.portugeseforkidsbackend.story;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {
		"http://127.0.0.1:8080", "http://localhost:8080",
		"http://127.0.0.1:5173", "http://localhost:5173",
		"https://mesutuluag.github.io"
})
@RequestMapping("/api/story")
public class StoryController {

	private static final String DEFAULT_CONTEXT = "school";
	private static final int MAX_CONVERSATION_HISTORY = 10;

	private final RateLimitService rateLimitService;
	private final ChatClient chatClient;
	private final Map<String, String> systemPrompts;
	// Lenient mapper: duplicate JSON keys (LLM hallucination) use last-value-wins
	// instead of throwing, and unknown fields are silently ignored.
	private final ObjectMapper objectMapper = new ObjectMapper()
			.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, false)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	public StoryController(RateLimitService rateLimitService, ChatClient.Builder chatClientBuilder) throws IOException {
		this.rateLimitService = rateLimitService;
		this.chatClient = chatClientBuilder.build();
		this.systemPrompts = new HashMap<>();
		this.systemPrompts.put("school",       loadPrompt("story-system-prompt-school.md"));
		this.systemPrompts.put("restaurant",   loadPrompt("story-system-prompt-restaurant.md"));
		this.systemPrompts.put("bank",         loadPrompt("story-system-prompt-bank.md"));
		this.systemPrompts.put("hospital",     loadPrompt("story-system-prompt-hospital.md"));
		this.systemPrompts.put("cafe",         loadPrompt("story-system-prompt-cafe.md"));
		this.systemPrompts.put("airport",      loadPrompt("story-system-prompt-airport.md"));
		this.systemPrompts.put("market",       loadPrompt("story-system-prompt-market.md"));
		this.systemPrompts.put("aima",         loadPrompt("story-system-prompt-aima.md"));
		this.systemPrompts.put("bus",          loadPrompt("story-system-prompt-bus.md"));
		this.systemPrompts.put("pharmacy",     loadPrompt("story-system-prompt-pharmacy.md"));
		this.systemPrompts.put("gas_station",  loadPrompt("story-system-prompt-gas_station.md"));
		this.systemPrompts.put("traffic",      loadPrompt("story-system-prompt-traffic.md"));
	}

	private String loadPrompt(String filename) throws IOException {
		return new ClassPathResource("prompts/" + filename)
				.getContentAsString(StandardCharsets.UTF_8);
	}

	@PostMapping
	public StoryResponse createStory(@RequestBody StoryRequest request, HttpServletRequest httpServletRequest) throws Exception {
		rateLimitService.checkAndIncrement(httpServletRequest.getRemoteAddr());

		String context = (request.getContext() != null && systemPrompts.containsKey(request.getContext()))
				? request.getContext()
				: DEFAULT_CONTEXT;

		String userPrompt = request.getPrompt();

		if (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty()) {
			List<String> history = request.getConversationHistory();
			int fromIndex = Math.max(0, history.size() - MAX_CONVERSATION_HISTORY);
			List<String> trimmedHistory = history.subList(fromIndex, history.size());
			userPrompt = userPrompt + "\n\nConversation so far (do NOT repeat any of these):\n"
					+ String.join("\n", trimmedHistory.stream()
							.map(s -> "- " + s)
							.toList());
		}

		if (request.getPreviousSentence() != null && !request.getPreviousSentence().isBlank()) {
			userPrompt = userPrompt + "\n\nThe previous sentence was: \"" + request.getPreviousSentence()
					+ "\". Generate a logical, direct, short reply from the other speaker answering or continuing directly from that sentence.";
		}

		String raw = chatClient.prompt()
				.system(systemPrompts.get(context))
				.user(userPrompt)
				.call()
				.content();

		// Strip markdown code fences the LLM sometimes wraps around JSON
		String json = raw.replaceAll("(?s)```json\\s*|```", "").trim();
		// Extract the first {...} block in case there is leading/trailing text
		int start = json.indexOf('{');
		int end   = json.lastIndexOf('}');
		if (start >= 0 && end > start) {
			json = json.substring(start, end + 1);
		}

		// Parse with the lenient mapper — duplicate keys use last-value-wins
		StoryPage page = objectMapper.readValue(json, StoryPage.class);

		return new StoryResponse(objectMapper.writeValueAsString(page));
	}
}
