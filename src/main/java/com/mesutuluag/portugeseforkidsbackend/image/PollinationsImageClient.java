package com.mesutuluag.portugeseforkidsbackend.image;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the Pollinations free image-generation API.
 *
 * <p>No authentication required. Returns raw image bytes on success,
 * or {@code null} when the API returns a non-200 status.
 */
@Component
public class PollinationsImageClient {

    private static final Logger log = LoggerFactory.getLogger(PollinationsImageClient.class);

    private static final String POLLINATIONS_URL = "https://image.pollinations.ai/prompt/";

    private final HttpClient httpClient;

    public PollinationsImageClient() {
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Generate an image for {@code prompt} at the given dimensions.
     *
     * @return raw image bytes, or {@code null} if Pollinations returned a non-200 response
     */
    public byte[] generate(String prompt, int width, int height) {
        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = POLLINATIONS_URL + encodedPrompt + "?width=" + width + "&height=" + height + "&nologo=true";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("[pollinations-client] Non-200 status={}", response.statusCode());

        } catch (Exception e) {
            log.error("[pollinations-client] Request threw: {}", e.getMessage(), e);
        }
        return null;
    }
}
