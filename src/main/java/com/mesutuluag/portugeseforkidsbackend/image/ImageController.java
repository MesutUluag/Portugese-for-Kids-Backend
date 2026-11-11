package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Map;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/image")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private static final int DEFAULT_MAX_PROMPT_LENGTH = 500;
    private static final int DEFAULT_MIN_DIMENSION = 64;
    private static final int DEFAULT_MAX_DIMENSION = 1024;

    private final ImageService imageService;
    private final int maxPromptLength;
    private final int minDimension;
    private final int maxDimension;

    public ImageController(ImageService imageService) {
        this(imageService, DEFAULT_MAX_PROMPT_LENGTH, DEFAULT_MIN_DIMENSION, DEFAULT_MAX_DIMENSION);
    }

    public ImageController(RateLimitService rateLimitService, ImageService imageService) {
        this(imageService, DEFAULT_MAX_PROMPT_LENGTH, DEFAULT_MIN_DIMENSION, DEFAULT_MAX_DIMENSION);
    }

    @Autowired
    public ImageController(
            ImageService imageService,
            @Value("${image.validation.max-prompt-length:500}") int maxPromptLength,
            @Value("${image.validation.min-dimension:64}") int minDimension,
            @Value("${image.validation.max-dimension:1024}") int maxDimension) {
        this.imageService     = imageService;
        this.maxPromptLength  = maxPromptLength;
        this.minDimension     = minDimension;
        this.maxDimension     = maxDimension;
    }

    public ImageController(
            RateLimitService rateLimitService,
            ImageService imageService,
            int maxPromptLength,
            int minDimension,
            int maxDimension) {
        this(imageService, maxPromptLength, minDimension, maxDimension);
    }

    @GetMapping
    public ResponseEntity<?> generateImage(
            @RequestParam(required = false) String imagePrompt,
            @RequestParam(defaultValue = "768") int width,
            @RequestParam(defaultValue = "368") int height,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpServletRequest) {

        if (imagePrompt == null || imagePrompt.isBlank()) {
            throw new IllegalArgumentException("imagePrompt must not be blank");
        }
        if (imagePrompt.length() > maxPromptLength) {
            throw new IllegalArgumentException("imagePrompt must not exceed " + maxPromptLength + " characters");
        }

        int clampedWidth = Math.max(minDimension, Math.min(maxDimension, width));
        int clampedHeight = Math.max(minDimension, Math.min(maxDimension, height));

        byte[] jpeg = imageService.generateJpeg(imagePrompt, clampedWidth, clampedHeight);

        if (jpeg == null) {
            log.warn("[image] Returning 503 for prompt='{}'",
                    imagePrompt.substring(0, Math.min(60, imagePrompt.length())));
            return ResponseEntity.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "error", "image_unavailable",
                            "message", "Image generation is temporarily unavailable. Please try again later."
                    ));
        }

        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    public ResponseEntity<?> generateImage(
            String imagePrompt,
            int width,
            int height,
            HttpServletRequest httpServletRequest) {
        return generateImage(imagePrompt, width, height, null, httpServletRequest);
    }
}
