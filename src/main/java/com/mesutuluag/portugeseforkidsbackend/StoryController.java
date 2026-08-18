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

	private final RateLimitService rateLimitService;
	private final ChatClient chatClient;
	private final String systemPrompt;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public StoryController(RateLimitService rateLimitService, ChatClient.Builder chatClientBuilder) throws IOException {
		this.rateLimitService = rateLimitService;
		this.chatClient = chatClientBuilder.build();
		this.systemPrompt = new ClassPathResource("prompts/story-system-prompt.md")
			.getContentAsString(StandardCharsets.UTF_8);
	}

	@PostMapping
	public StoryResponse createStory(@RequestBody StoryRequest request, HttpServletRequest httpServletRequest) throws Exception {
		rateLimitService.checkAndIncrement(httpServletRequest.getRemoteAddr());

		StoryPage page = chatClient.prompt()
			.system(systemPrompt)
			.user(request.getPrompt())
			.call()
			.entity(StoryPage.class);

		return new StoryResponse(objectMapper.writeValueAsString(page));
	}
}
