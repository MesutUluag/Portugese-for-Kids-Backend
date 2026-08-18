package com.mesutuluag.portugeseforkidsbackend;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {"http://127.0.0.1:8080", "http://localhost:8080", "http://127.0.0.1:5173", "http://localhost:5173", "https://mesutuluag.github.io"})
@RequestMapping("/api/image")
public class ImageController {

	private static final String POLLINATIONS_URL = "https://image.pollinations.ai/prompt/";
	private static final String HF_URL = "https://router.huggingface.co/hf-inference/models/stabilityai/stable-diffusion-3-medium-diffusers";

	private final RateLimitService rateLimitService;
	private final String hfToken;
	private final HttpClient httpClient = HttpClient.newHttpClient();

	public ImageController(
			RateLimitService rateLimitService,
			@Value("${hf.api.token}") String hfToken) {
		this.rateLimitService = rateLimitService;
		this.hfToken = hfToken;
	}

	@GetMapping(produces = MediaType.IMAGE_JPEG_VALUE)
	public ResponseEntity<byte[]> generateImage(
			@RequestParam String imagePrompt,
			@RequestParam(defaultValue = "10") int steps,
			@RequestParam(defaultValue = "768") int width,
			@RequestParam(defaultValue = "368") int height,
			HttpServletRequest httpServletRequest) throws Exception {

		rateLimitService.checkAndIncrement(httpServletRequest.getRemoteAddr());

		// 1. Try Pollinations first (free, no auth required)
		String encodedPrompt = URLEncoder.encode(imagePrompt, StandardCharsets.UTF_8);
		HttpRequest pollinationsRequest = HttpRequest.newBuilder()
				.uri(URI.create(POLLINATIONS_URL + encodedPrompt + "?width=" + width + "&height=" + height + "&nologo=true"))
				.GET()
				.build();

		HttpResponse<byte[]> pollinationsResponse = httpClient.send(pollinationsRequest, HttpResponse.BodyHandlers.ofByteArray());

		if (pollinationsResponse.statusCode() == 200) {
			return ResponseEntity.ok()
					.contentType(MediaType.IMAGE_JPEG)
					.body(pollinationsResponse.body());
		}

		// 2. Fall back to Hugging Face if Pollinations fails
		String body = "{\"inputs\":\"" + imagePrompt.replace("\"", "\\\"") + "\",\"parameters\":{\"num_inference_steps\":" + steps + ",\"width\":" + width + ",\"height\":" + height + "}}";

		HttpRequest hfRequest = HttpRequest.newBuilder()
				.uri(URI.create(HF_URL))
				.header("Authorization", "Bearer " + hfToken)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<byte[]> hfResponse = httpClient.send(hfRequest, HttpResponse.BodyHandlers.ofByteArray());

		if (hfResponse.statusCode() != 200) {
			return ResponseEntity.status(hfResponse.statusCode()).build();
		}

		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.body(hfResponse.body());
	}
}
