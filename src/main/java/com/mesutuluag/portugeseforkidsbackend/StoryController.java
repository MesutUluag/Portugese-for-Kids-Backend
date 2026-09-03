package com.mesutuluag.portugeseforkidsbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {"http://127.0.0.1:8080", "http://localhost:8080", "http://127.0.0.1:5173", "http://localhost:5173", "https://mesutuluag.github.io"})
@RequestMapping("/api/story")
public class StoryController {

	private static final String DEFAULT_CONTEXT = "school";

	private final RateLimitService rateLimitService;
	private final ChatClient chatClient;
	private final java.util.Map<String, String> systemPrompts;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public StoryController(RateLimitService rateLimitService, ChatClient.Builder chatClientBuilder) throws IOException {
		this.rateLimitService = rateLimitService;
		this.chatClient = chatClientBuilder.build();
		this.systemPrompts = new java.util.HashMap<>();
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
		if (request.getPreviousSentence() != null && !request.getPreviousSentence().isBlank()) {
			userPrompt = userPrompt + " The previous sentence was: \"" + request.getPreviousSentence()
				+ "\". Now generate the natural reply from the other speaker (e.g. the officer, waiter, doctor, or driver).";
		}

		StoryPage page = chatClient.prompt()
			.system(systemPrompts.get(context))
			.user(userPrompt)
			.call()
			.entity(StoryPage.class);

		return new StoryResponse(objectMapper.writeValueAsString(page));
	}
}
