package com.mesutuluag.portugeseforkidsbackend.image;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the Pollinations free image-generation API.
 *
 * <p>No authentication required. Returns raw image bytes on success,
 * or {@code null} when the API returns a non-200 status or times out.
 */
@Component
public class PollinationsImageClient {

    private static final Logger log = LoggerFactory.getLogger(PollinationsImageClient.class);

    private static final String POLLINATIONS_URL = "https://image.pollinations.ai/prompt/";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public PollinationsImageClient() {
        this(DEFAULT_CONNECT_TIMEOUT.toSeconds(), DEFAULT_REQUEST_TIMEOUT.toSeconds());
    }

    @Autowired
    public PollinationsImageClient(
            @Value("${pollinations.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${pollinations.request-timeout-seconds:15}") long requestTimeoutSeconds) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Duration.ofSeconds(requestTimeoutSeconds)
        );
    }

    PollinationsImageClient(HttpClient httpClient) {
        this(httpClient, DEFAULT_REQUEST_TIMEOUT);
    }

    PollinationsImageClient(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
    }

    /**
     * Generate an image for {@code prompt} at the given dimensions.
     *
     * @return raw image bytes, or {@code null} if Pollinations returned a non-200 response or timed out
     */
    public byte[] generate(String prompt, int width, int height) {
        if (prompt == null || prompt.isBlank()) {
            log.warn("[pollinations-client] Prompt is null or blank");
            return null;
        }

        String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
        String url = POLLINATIONS_URL + encodedPrompt + "?width=" + width + "&height=" + height + "&nologo=true";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("[pollinations-client] Non-200 status={}", response.statusCode());

        } catch (HttpTimeoutException e) {
            log.warn("[pollinations-client] Request timed out after {}s: {}", requestTimeout.toSeconds(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[pollinations-client] Request was interrupted: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[pollinations-client] Request failed: {}", e.getMessage());
        }
        return null;
    }
}
