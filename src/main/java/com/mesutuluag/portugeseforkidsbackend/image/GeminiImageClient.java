package com.mesutuluag.portugeseforkidsbackend.image;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client for Google's Gemini image-generation endpoint.
 *
 * <p>Obtains a short-lived access token via Google Application Default Credentials (ADC)
 * and calls the Vertex AI generateContent API.
 *
 * <p>Returns the raw image bytes (PNG/WebP as returned by the API), or
 * {@code null} when Gemini is unavailable or returns no image part.
 */
@Component
public class GeminiImageClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageClient.class);

    private static final String GEMINI_IMAGE_URL =
            "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/gemini-2.5-flash-image:generateContent";

    private final String projectId;
    private final String location;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiImageClient(
            @Value("${GOOGLE_CLOUD_PROJECT:}") String projectId,
            @Value("${spring.ai.vertex.ai.gemini.location:us-central1}") String location) {
        this.projectId  = projectId;
        this.location   = location;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Generate an image for {@code prompt}.
     *
     * @return raw image bytes from Gemini, or {@code null} if unavailable
     */
    public byte[] generate(String prompt) {
        String accessToken = getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            log.warn("[gemini-client] No access token available, skipping Gemini");
            return null;
        }

        String requestBody = """
                {
                  "contents": {
                    "role": "user",
                    "parts": {"text": "%s"}
                  },
                  "generationConfig": {"responseModalities": ["IMAGE", "TEXT"]}
                }
                """.formatted(prompt.replace("\"", "\\\"").replace("\n", " "));

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_IMAGE_URL.formatted(projectId, location)))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[gemini-client] Error body: {}",
                        response.body().substring(0, Math.min(300, response.body().length())));
                return null;
            }

            JsonNode root  = objectMapper.readTree(response.body());
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");

            for (JsonNode part : parts) {
                JsonNode inlineData = part.path("inlineData");
                if (!inlineData.isMissingNode()) {
                    String mimeType   = inlineData.path("mimeType").asText();
                    String base64Data = inlineData.path("data").asText();
                    return Base64.getDecoder().decode(base64Data);
                }
            }
            log.warn("[gemini-client] No image part in response. Body snippet: {}",
                    response.body().substring(0, Math.min(300, response.body().length())));

        } catch (Exception e) {
            log.error("[gemini-client] Request threw: {}", e.getMessage(), e);
        }
        return null;
    }

    private String getAccessToken() {
        try {
            GoogleCredentials credentials = GoogleCredentials
                    .getApplicationDefault()
                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            log.warn("[gemini-client] Could not obtain ADC access token: {}", e.getMessage());
            return null;
        }
    }
}
