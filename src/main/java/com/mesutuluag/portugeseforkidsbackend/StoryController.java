package com.mesutuluag.portugeseforkidsbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {"http://127.0.0.1:8080", "http://localhost:8080", "http://127.0.0.1:5173", "http://localhost:5173", "https://mesutuluag.github.io"})
@RequestMapping("/api/story")
public class StoryController {

	private static final int DAILY_LIMIT = 50;

	private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();
	private final ChatClient chatClient;
	private final String systemPrompt;

	public StoryController(ChatClient.Builder chatClientBuilder) throws IOException {
		this.chatClient = chatClientBuilder.build();
		this.systemPrompt = new ClassPathResource("prompts/story-system-prompt.md")
			.getContentAsString(StandardCharsets.UTF_8);
	}

	@PostMapping
	public StoryResponse createStory(@RequestBody StoryRequest request, HttpServletRequest httpServletRequest) {
		String ipAddress = httpServletRequest.getRemoteAddr();
		String key = LocalDate.now() + ":" + ipAddress;
		int count = requestCounts.merge(key, 1, Integer::sum);
		if (count > DAILY_LIMIT) {
			throw new DailyLimitExceededException();
		}

		String content = chatClient.prompt()
			.system(systemPrompt)
			.user(request.getPrompt())
			.call()
			.content();
		return new StoryResponse(content);
	}

	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	private static final class DailyLimitExceededException extends RuntimeException {
	}
}
