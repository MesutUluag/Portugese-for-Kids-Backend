package com.mesutuluag.portugeseforkidsbackend.image;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gemini image-generation client using the standard Gemini API (generativelanguage.googleapis.com).
 *
 * <p>Authenticates with a Gemini API key, which gives access to the full model catalogue
 * including {@code gemini-3.1-flash-lite-image}. Falls back gracefully to null when
 * the API key is not configured.
 */
@Component
public class GeminiImageClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageClient.class);

    private static final String MODEL = "gemini-3.1-flash-lite-image";

    private static final GenerateContentConfig CONFIG = GenerateContentConfig.builder()
            .responseModalities("IMAGE", "TEXT")
            .build();

    private final Client client;

    public GeminiImageClient(@Value("${gemini.image.api-key:}") String apiKey) {
        Client built = null;
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                built = Client.builder()
                        .apiKey(apiKey)
                        .build();
            } catch (Exception e) {
                log.warn("[gemini-client] Could not initialise GenAI client: {}", e.getMessage());
            }
        } else {
            log.warn("[gemini-client] GEMINI_API_KEY not set, Gemini image generation disabled");
        }
        this.client = built;
    }

    /**
     * Generate an image for {@code prompt}.
     *
     * @return raw image bytes from Gemini, or {@code null} if unavailable
     */
    public byte[] generate(String prompt) {
        if (client == null) {
            return null;
        }
        try {
            GenerateContentResponse response = client.models.generateContent(MODEL, prompt, CONFIG);
            for (Part part : response.parts()) {
                if (part.inlineData().isPresent()) {
                    return part.inlineData().get().data().orElse(null);
                }
            }
            log.warn("[gemini-client] No image part in response");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Spend cap breached")) {
                log.error("[gemini-client] Spend cap breached: {}", msg);
                throw new SpendCapException(msg);
            }
            log.error("[gemini-client] Request failed: {}", msg, e);
        }
        return null;
    }

    /** Thrown when the Gemini API key has hit its spend cap. */
    static class SpendCapException extends RuntimeException {
        SpendCapException(String message) { super(message); }
    }
}
