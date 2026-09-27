package com.mesutuluag.portugeseforkidsbackend.image;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client for the Hugging Face Inference Router (Stable Diffusion 3).
 *
 * <p>Used as a last-resort fallback when all other image providers fail.
 * Returns raw image bytes on success, or {@code null} on non-200 responses.
 */
@Component
public class HuggingFaceImageClient {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceImageClient.class);

    private static final String HF_URL =
            "https://router.huggingface.co/hf-inference/models/stabilityai/stable-diffusion-3-medium-diffusers";

    private final String hfToken;
    private final HttpClient httpClient;

    public HuggingFaceImageClient(@Value("${hf.api.token}") String hfToken) {
        this.hfToken    = hfToken;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Generate an image for {@code prompt} at the given dimensions.
     *
     * @return raw image bytes, or {@code null} if HF returned a non-200 response
     */
    public byte[] generate(String prompt, int steps, int width, int height) {
        String body = "{\"inputs\":\"" + prompt.replace("\"", "\\\"")
                + "\",\"parameters\":{\"num_inference_steps\":" + steps
                + ",\"width\":" + width
                + ",\"height\":" + height + "}}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HF_URL))
                    .header("Authorization", "Bearer " + hfToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("[hf-client] Non-200 status={}", response.statusCode());

        } catch (Exception e) {
            log.error("[hf-client] Request threw: {}", e.getMessage(), e);
        }
        return null;
    }
}
