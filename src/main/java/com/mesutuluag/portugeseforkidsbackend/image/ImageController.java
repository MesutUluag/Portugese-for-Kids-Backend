package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Map;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = {
        "http://127.0.0.1:8080", "http://localhost:8080",
        "http://127.0.0.1:5173", "http://localhost:5173",
        "https://mesutuluag.github.io"
})
@RequestMapping("/api/image")
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);

    private final RateLimitService rateLimitService;
    private final ImageService imageService;

    public ImageController(RateLimitService rateLimitService, ImageService imageService) {
        this.rateLimitService = rateLimitService;
        this.imageService     = imageService;
    }

    @GetMapping
    public ResponseEntity<?> generateImage(
            @RequestParam String imagePrompt,
            @RequestParam(defaultValue = "10") int steps,
            @RequestParam(defaultValue = "768") int width,
            @RequestParam(defaultValue = "368") int height,
            HttpServletRequest httpServletRequest) throws Exception {

        rateLimitService.checkAndIncrement(httpServletRequest.getRemoteAddr());

        byte[] jpeg = imageService.generateJpeg(imagePrompt, steps, width, height);

        if (jpeg == null) {
            log.error("[image] All providers failed for prompt='{}'",
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
}
