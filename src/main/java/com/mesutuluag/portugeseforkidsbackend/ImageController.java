package com.mesutuluag.portugeseforkidsbackend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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

		String body = "{\"inputs\":\"" + imagePrompt.replace("\"", "\\\"") + "\",\"parameters\":{\"num_inference_steps\":" + steps + ",\"width\":" + width + ",\"height\":" + height + "}}";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(HF_URL))
				.header("Authorization", "Bearer " + hfToken)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

		if (response.statusCode() != 200) {
			return ResponseEntity.status(response.statusCode()).build();
		}

		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.body(response.body());
	}
}
