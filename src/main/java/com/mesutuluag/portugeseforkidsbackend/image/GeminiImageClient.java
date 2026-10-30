package com.mesutuluag.portugeseforkidsbackend.image;

import java.time.Duration;
import java.time.Instant;

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
 * the API key is not configured, when disabled via feature toggle, or when in cooldown after hitting spend caps.
 */
@Component
public class GeminiImageClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageClient.class);

    private static final String MODEL = "gemini-3.1-flash-lite-image";
    private static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(15);

    private static final GenerateContentConfig CONFIG = GenerateContentConfig.builder()
            .responseModalities("IMAGE", "TEXT")
            .build();

    private final boolean enabled;
    private final Duration cooldownDuration;
    private final Client client;
    private volatile Instant cooldownUntil;

    public GeminiImageClient(String apiKey, boolean enabled) {
        this(apiKey, enabled, DEFAULT_COOLDOWN);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiImageClient(
            @Value("${gemini.image.api-key:}") String apiKey,
            @Value("${gemini.image.enabled:false}") boolean enabled,
            @Value("${gemini.image.cooldown-minutes:15}") long cooldownMinutes) {
        this(apiKey, enabled, Duration.ofMinutes(cooldownMinutes));
    }

    public GeminiImageClient(String apiKey, boolean enabled, Duration cooldownDuration) {
        this.enabled = enabled;
        this.cooldownDuration = cooldownDuration != null ? cooldownDuration : DEFAULT_COOLDOWN;
        Client built = null;
        if (!enabled) {
            log.info("[gemini-client] Gemini image generation is disabled via gemini.image.enabled=false");
        } else if (apiKey != null && !apiKey.isBlank()) {
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
     * Indicates whether Gemini image generation is enabled, a valid client is configured,
     * and cooldown from any previous spend cap or quota error has expired.
     */
    public boolean isEnabled() {
        if (!enabled || client == null) {
            return false;
        }
        Instant cooldown = cooldownUntil;
        if (cooldown != null) {
            if (Instant.now().isBefore(cooldown)) {
                return false;
            }
            cooldownUntil = null;
            log.info("[gemini-client] Cooldown expired; re-enabling Gemini image client");
        }
        return true;
    }

    /**
     * Generate an image for {@code prompt}.
     *
     * @return raw image bytes from Gemini, or {@code null} if unavailable
     */
    public byte[] generate(String prompt) {
        if (!isEnabled()) {
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
            if (msg != null && (msg.contains("Spend cap breached") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("429"))) {
                cooldownUntil = Instant.now().plus(cooldownDuration);
                log.warn("[gemini-client] Quota or spend cap reached: {}. Cooldown activated until {}", msg, cooldownUntil);
                throw new SpendCapException(msg);
            }
            log.warn("[gemini-client] Request failed: {}", msg);
        }
        return null;
    }

    Instant getCooldownUntil() {
        return cooldownUntil;
    }

    void setCooldownUntil(Instant cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }

    /** Thrown when the Gemini API key has hit its spend cap. */
    static class SpendCapException extends RuntimeException {
        SpendCapException(String message) { super(message); }
    }
}
