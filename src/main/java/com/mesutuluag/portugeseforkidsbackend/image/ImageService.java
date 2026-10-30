package com.mesutuluag.portugeseforkidsbackend.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates image generation across multiple providers with a semantic cache.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Semantic cache — returns a cached JPEG for a semantically similar prompt.</li>
 *   <li>Gemini (when enabled) — primary AI provider; result is JPEG-converted and cached.</li>
 *   <li>Pollinations — free fallback, not cached.</li>
 * </ol>
 *
 * <p>Returns {@code null} only when all providers fail.
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    /** JPEG compression quality applied to Gemini output (0.0–1.0). */
    private static final float JPEG_QUALITY = 0.60f;

    private final SemanticImageCache semanticImageCache;
    private final GeminiImageClient geminiImageClient;
    private final PollinationsImageClient pollinationsImageClient;

    public ImageService(
            SemanticImageCache semanticImageCache,
            GeminiImageClient geminiImageClient,
            PollinationsImageClient pollinationsImageClient) {
        this.semanticImageCache      = semanticImageCache;
        this.geminiImageClient       = geminiImageClient;
        this.pollinationsImageClient = pollinationsImageClient;
    }

    /**
     * Generate or retrieve a JPEG image for the given prompt.
     *
     * @param prompt  image description
     * @param width   desired width in pixels
     * @param height  desired height in pixels
     * @return JPEG bytes, or {@code null} if all providers failed
     */
    public byte[] generateJpeg(String prompt, int width, int height) {

        // 1. Semantic cache
        byte[] cached = semanticImageCache.get(prompt);
        if (cached != null) {
            return cached;
        }

        // 2. Gemini (only when enabled) — cache result on success
        if (geminiImageClient.isEnabled()) {
            try {
                byte[] raw = geminiImageClient.generate(prompt);
                if (raw != null) {
                    byte[] jpeg = toJpeg(raw, JPEG_QUALITY);
                    semanticImageCache.put(prompt, jpeg);
                    return jpeg;
                }
            } catch (Exception e) {
                log.warn("[image-service] Gemini path failed: {}", e.getMessage());
            }
        }

        // 3. Pollinations fallback
        byte[] pollinations = pollinationsImageClient.generate(prompt, width, height);
        if (pollinations != null) {
            return pollinations;
        }

        log.error("[image-service] All providers failed for prompt='{}'",
                prompt.substring(0, Math.min(60, prompt.length())));
        return null;
    }

    // -----------------------------------------------------------------------
    // JPEG conversion
    // -----------------------------------------------------------------------

    /**
     * Convert arbitrary image bytes (PNG, WebP, …) to JPEG at the given quality.
     * Flattens alpha onto a white background because JPEG has no transparency.
     */
    private static byte[] toJpeg(byte[] imageBytes, float quality) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        BufferedImage rgb   = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);

        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            g.dispose();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
