package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory semantic image cache backed by TF-IDF cosine similarity.
 *
 * <p>Instead of exact-string matching, each incoming prompt is compared against
 * all cached prompts using TF-IDF weighted cosine similarity. If the best match
 * exceeds {@link #SIMILARITY_THRESHOLD} the cached JPEG bytes are returned
 * directly, saving an image-generation API call.
 *
 * <p>Uses a single bounded LRU map holding immutable {@link CacheEntry} records
 * (containing both JPEG bytes and TF-IDF vectors) to guarantee atomic eviction
 * and eliminate copy-snapshot overhead.
 */
@Component
public class SemanticImageCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticImageCache.class);

    /** Cosine similarity threshold above which a cache hit is declared. */
    static final double SIMILARITY_THRESHOLD = 0.82;

    /** Maximum number of cached entries before LRU eviction kicks in. */
    private static final int MAX_ENTRIES = 500;

    /**
     * Stop-words that carry no semantic weight for image prompts.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "at", "to",
            "for", "with", "is", "are", "that", "this", "it", "its"
    );

    /**
     * Fixed style-template terms appended to every prompt.
     * Stripped before vectorisation so similarity is computed on the scene
     * subject only — not on the shared boilerplate that all prompts share.
     */
    private static final Set<String> BOILERPLATE_WORDS = Set.of(
            "colorful", "cute", "kids", "illustration", "storybook", "art",
            "bright", "colors", "simple", "background", "no", "text",
            "friendly" // AIMA context uses "colorful friendly illustration" instead of "cute kids"
    );

    /**
     * Immutable value object holding image bytes and pre-computed vector.
     */
    private record CacheEntry(byte[] jpeg, Map<String, Double> vector) {}

    /** Lock guarding access to the LRU cache. */
    private final Object lock = new Object();

    /** Bounded LRU map: prompt → CacheEntry. */
    private final Map<String, CacheEntry> cache;

    public SemanticImageCache() {
        this.cache = new LruMap(MAX_ENTRIES);
    }

    private static final class LruMap extends LinkedHashMap<String, CacheEntry> {
        private final int maxSize;

        LruMap(int maxSize) {
            super(maxSize, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > maxSize;
        }
    }

    /**
     * Look up the cache for an exact or semantically similar prompt.
     *
     * @return cached JPEG bytes if a similar prompt was found, otherwise {@code null}
     */
    public byte[] get(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;

        synchronized (lock) {
            // 1. O(1) Fast path: exact string match (also updates LRU order)
            CacheEntry exactHit = cache.get(prompt);
            if (exactHit != null) {
                log.info("[semantic-cache] EXACT HIT key='{}'", truncate(prompt));
                return exactHit.jpeg();
            }

            if (cache.isEmpty()) return null;

            // 2. O(N) Semantic search — toVector is called with the cache already locked
            Map<String, Double> queryVec = toVectorLocked(prompt);
            if (queryVec.isEmpty()) return null;

            double bestScore = 0.0;
            String bestKey   = null;
            byte[] bestJpeg  = null;

            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                double score = cosineSimilarity(queryVec, entry.getValue().vector());
                if (score > bestScore) {
                    bestScore = score;
                    bestKey   = entry.getKey();
                    bestJpeg  = entry.getValue().jpeg();
                }
            }

            if (bestKey != null && bestScore >= SIMILARITY_THRESHOLD) {
                log.info("[semantic-cache] SEMANTIC HIT score={} key='{}'",
                        String.format("%.3f", bestScore), truncate(bestKey));
                cache.get(bestKey); // touch to update LRU order for the matched key
                return bestJpeg;
            }

            log.debug("[semantic-cache] MISS best-score={}", String.format("%.3f", bestScore));
            return null;
        }
    }

    /**
     * Store a prompt and its generated JPEG bytes in the cache.
     */
    public void put(String prompt, byte[] jpeg) {
        if (prompt == null || jpeg == null || jpeg.length == 0) return;

        synchronized (lock) {
            // Compute vector inside the lock so IDF counts are consistent with
            // the corpus state at insertion time — no separate lock acquisition needed.
            Map<String, Double> vector = toVectorLocked(prompt);
            cache.put(prompt, new CacheEntry(jpeg, vector));
        }

        log.debug("[semantic-cache] stored prompt='{}' size={}KB",
                truncate(prompt), jpeg.length / 1024);
    }

    /** Current number of entries in the cache. */
    public int size() {
        synchronized (lock) {
            return cache.size();
        }
    }

    // -----------------------------------------------------------------------
    // TF-IDF helpers — all called with lock already held
    // -----------------------------------------------------------------------

    /**
     * Build a TF-IDF weighted term vector for {@code prompt}.
     * Must be called with {@link #lock} held — reads {@link #cache} directly
     * for IDF counts without acquiring the lock again.
     */
    private Map<String, Double> toVectorLocked(String prompt) {
        String[] tokens = tokenise(prompt);
        if (tokens.length == 0) return Collections.emptyMap();

        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }

        int totalDocs = cache.size() + 1; // +1 for the document being vectorised

        Map<String, Double> vec = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            String term     = e.getKey();
            double termTf   = (double) e.getValue() / tokens.length;
            int docsWithTerm = countDocsContainingLocked(term);
            double idf      = Math.log(1.0 + (double) totalDocs / (1.0 + docsWithTerm));
            vec.put(term, termTf * idf);
        }

        return l2Normalise(vec);
    }

    /** Must be called with {@link #lock} held. */
    private int countDocsContainingLocked(String term) {
        int count = 0;
        for (CacheEntry entry : cache.values()) {
            if (entry.vector().containsKey(term)) count++;
        }
        return count;
    }

    private static String[] tokenise(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1)
                .filter(t -> !STOP_WORDS.contains(t))
                .filter(t -> !BOILERPLATE_WORDS.contains(t))
                .toArray(String[]::new);
    }

    private static Map<String, Double> l2Normalise(Map<String, Double> vec) {
        double norm = 0.0;
        for (double v : vec.values()) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return vec;

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> e : vec.entrySet()) {
            result.put(e.getKey(), e.getValue() / norm);
        }
        return result;
    }

    private static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> smaller = a.size() <= b.size() ? a : b;
        Map<String, Double> larger  = a.size() <= b.size() ? b : a;
        double result = 0.0;
        for (Map.Entry<String, Double> e : smaller.entrySet()) {
            Double bVal = larger.get(e.getKey());
            if (bVal != null) result += e.getValue() * bVal;
        }
        return result;
    }

    private static String truncate(String text) {
        return text.substring(0, Math.min(60, text.length()));
    }
}
