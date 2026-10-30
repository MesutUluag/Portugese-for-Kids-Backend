package com.mesutuluag.portugeseforkidsbackend.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import jakarta.annotation.PreDestroy;
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
 * <p>Each entry is stored under {@code image-cache/<sha256>/image.jpg} with the
 * prompt text stored in custom object metadata ({@code prompt}). Legacy entries
 * that stored {@code prompt.txt} as a separate sibling object are also supported
 * transparently on load.
 *
 * <p>Writes are fire-and-forget (virtual-thread executor) so they never block
 * the request path. Startup warmup caps loaded entries to {@link SemanticImageCache#MAX_ENTRIES}
 * and bounds concurrent GCS reads with a semaphore.
 *
 * <p>Activated only when {@code image.cache.gcs.enabled=true}. When disabled,
 * {@link SemanticImageCache} behaves as a pure in-memory cache.
 */
@Component
@ConditionalOnProperty(name = "image.cache.gcs.enabled", havingValue = "true")
public class GcsImageStore {

    private static final Logger log = LoggerFactory.getLogger(GcsImageStore.class);

    private static final String JPEG_NAME                   = "image.jpg";
    private static final String PROMPT_NAME                 = "prompt.txt";
    private static final String PROMPT_METADATA_KEY         = "prompt";
    private static final int    DEFAULT_MAX_CONCURRENT_READS = 32;

    private final Storage storage;
    private final String  bucket;
    private final String  prefix;
    private final int     maxConcurrentReads;

    /** Virtual-thread executor for async writes and bounded startup reads. */
    private final ExecutorService writeExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    public GcsImageStore(
            @Value("${image.cache.gcs.bucket}") String bucket,
            @Value("${image.cache.gcs.prefix:image-cache}") String prefix,
            @Value("${image.cache.gcs.max-concurrent-reads:32}") int maxConcurrentReads) {
        this.bucket             = bucket;
        this.prefix             = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        this.maxConcurrentReads = maxConcurrentReads > 0 ? maxConcurrentReads : DEFAULT_MAX_CONCURRENT_READS;
        this.storage            = StorageOptions.getDefaultInstance().getService();
        log.info("[gcs-store] initialised bucket='{}' prefix='{}'", bucket, this.prefix);
    }

    @PreDestroy
    void shutdown() {
        writeExecutor.shutdown();
        try {
            if (!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Load up to {@link SemanticImageCache#MAX_ENTRIES} cache entries from GCS.
     *
     * @return list of (prompt, jpeg) pairs; never null
     */
    public List<Entry> loadAll() {
        return loadAll(SemanticImageCache.MAX_ENTRIES);
    }

    /**
     * Load up to {@code maxEntries} cache entries from GCS with bounded concurrency.
     *
     * @param maxEntries maximum number of entries to load
     * @return list of (prompt, jpeg) pairs; never null
     */
    public List<Entry> loadAll(int maxEntries) {
        if (maxEntries <= 0) {
            return List.of();
        }
        try {
            // Collect up to maxEntries image blobs (name + metadata only).
            List<Blob> imageBlobs = StreamSupport.stream(
                            storage.list(bucket,
                                    Storage.BlobListOption.prefix(prefix + "/"),
                                    Storage.BlobListOption.fields(Storage.BlobField.NAME, Storage.BlobField.METADATA))
                                    .iterateAll().spliterator(), false)
                    .filter(blob -> blob.getName().endsWith("/" + JPEG_NAME))
                    .limit(maxEntries)
                    .toList();

            Semaphore readPermits = new Semaphore(maxConcurrentReads);

            // Read each entry in parallel using bounded virtual threads.
            List<CompletableFuture<Entry>> futures = imageBlobs.stream()
                    .map(blob -> CompletableFuture.supplyAsync(() -> {
                        boolean acquired = false;
                        try {
                            readPermits.acquire();
                            acquired = true;

                            String blobName = blob.getName();
                            byte[] jpeg = storage.readAllBytes(BlobId.of(bucket, blobName));

                            Map<String, String> metadata = blob.getMetadata();
                            String prompt = (metadata != null) ? metadata.get(PROMPT_METADATA_KEY) : null;
                            if (prompt == null || prompt.isBlank()) {
                                // Legacy fallback: read sibling prompt.txt object
                                String dir = blobName.substring(0, blobName.lastIndexOf('/'));
                                byte[] promptBytes = storage.readAllBytes(BlobId.of(bucket, dir + "/" + PROMPT_NAME));
                                prompt = new String(promptBytes, StandardCharsets.UTF_8);
                            }

                            return new Entry(prompt, jpeg);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        } catch (Exception e) {
                            log.warn("[gcs-store] skipping malformed entry blob='{}': {}", blob.getName(), e.getMessage());
                            return null;
                        } finally {
                            if (acquired) {
                                readPermits.release();
                            }
                        }
                    }, writeExecutor))
                    .toList();

            List<Entry> results = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(e -> e != null)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            log.info("[gcs-store] loaded {} entries from GCS", results.size());
            return results;
        } catch (Exception e) {
            log.error("[gcs-store] loadAll failed — cache will start empty: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Persist a prompt/jpeg pair asynchronously (fire-and-forget) as a single GCS object
     * with the prompt stored in custom metadata.
     * Never throws — errors are logged only.
     */
    public void saveAsync(String prompt, byte[] jpeg) {
        writeExecutor.execute(() -> {
            try {
                String dir = prefix + "/" + sha256(prompt);
                storage.create(
                        BlobInfo.newBuilder(BlobId.of(bucket, dir + "/" + JPEG_NAME))
                                .setContentType("image/jpeg")
                                .setMetadata(Map.of(PROMPT_METADATA_KEY, prompt))
                                .build(),
                        jpeg);
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
