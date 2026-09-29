package com.mesutuluag.portugeseforkidsbackend.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

/**
 * Thin GCS-backed store for the semantic image cache.
 *
 * <p>Each entry is stored as two objects under {@code image-cache/<sha256>/}:
 * <ul>
 *   <li>{@code image.jpg} — the raw JPEG bytes</li>
 *   <li>{@code prompt.txt} — the original prompt text (UTF-8)</li>
 * </ul>
 *
 * <p>Writes are fire-and-forget (virtual-thread executor) so they never block
 * the request path. Startup loads all existing entries synchronously so the
 * in-memory cache is warm before the first request is served.
 *
 * <p>Activated only when {@code image.cache.gcs.enabled=true}. When disabled,
 * {@link SemanticImageCache} behaves exactly as before (pure in-memory).
 */
@Component
@ConditionalOnProperty(name = "image.cache.gcs.enabled", havingValue = "true")
public class GcsImageStore {

    private static final Logger log = LoggerFactory.getLogger(GcsImageStore.class);

    private static final String JPEG_NAME   = "image.jpg";
    private static final String PROMPT_NAME = "prompt.txt";

    private final Storage storage;
    private final String  bucket;
    private final String  prefix;

    /** Single virtual-thread executor for async writes — low overhead, bounded parallelism. */
    private final ExecutorService writeExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public GcsImageStore(
            @Value("${image.cache.gcs.bucket}") String bucket,
            @Value("${image.cache.gcs.prefix:image-cache}") String prefix) {
        this.bucket  = bucket;
        this.prefix  = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        this.storage = StorageOptions.getDefaultInstance().getService();
        log.info("[gcs-store] initialised bucket='{}' prefix='{}'", bucket, this.prefix);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Load all cache entries from GCS.
     * Called once at startup from {@link SemanticImageCache}.
     *
     * @return list of (prompt, jpeg) pairs; never null
     */
    public List<Entry> loadAll() {
        List<Entry> results = new ArrayList<>();
        try {
            storage.list(bucket, Storage.BlobListOption.prefix(prefix + "/"),
                            Storage.BlobListOption.fields(Storage.BlobField.NAME))
                    .iterateAll()
                    .forEach(blob -> {
                        String name = blob.getName();
                        if (!name.endsWith("/" + JPEG_NAME)) return;

                        String dir = name.substring(0, name.lastIndexOf('/'));
                        try {
                            byte[] jpeg   = storage.readAllBytes(BlobId.of(bucket, dir + "/" + JPEG_NAME));
                            byte[] prompt = storage.readAllBytes(BlobId.of(bucket, dir + "/" + PROMPT_NAME));
                            results.add(new Entry(new String(prompt, StandardCharsets.UTF_8), jpeg));
                        } catch (Exception e) {
                            log.warn("[gcs-store] skipping malformed entry dir='{}': {}", dir, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("[gcs-store] loadAll failed — cache will start empty: {}", e.getMessage());
        }
        log.info("[gcs-store] loaded {} entries from GCS", results.size());
        return results;
    }

    /**
     * Persist a prompt/jpeg pair asynchronously (fire-and-forget).
     * Never throws — errors are logged only.
     */
    public void saveAsync(String prompt, byte[] jpeg) {
        writeExecutor.execute(() -> {
            try {
                String dir = prefix + "/" + sha256(prompt);
                storage.create(
                        BlobInfo.newBuilder(BlobId.of(bucket, dir + "/" + JPEG_NAME))
                                .setContentType("image/jpeg")
                                .build(),
                        jpeg);
                storage.create(
                        BlobInfo.newBuilder(BlobId.of(bucket, dir + "/" + PROMPT_NAME))
                                .setContentType("text/plain; charset=utf-8")
                                .build(),
                        prompt.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("[gcs-store] async save failed for prompt='{}': {}",
                        prompt.substring(0, Math.min(60, prompt.length())), e.getMessage());
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available in the JDK — unreachable in practice
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Immutable (prompt, jpeg) pair returned by {@link #loadAll()}. */
    public record Entry(String prompt, byte[] jpeg) {}
}
