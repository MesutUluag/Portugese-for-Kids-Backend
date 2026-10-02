package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Token normalisation pipeline (applied before vectorisation):
 * <ol>
 *   <li>Lower-case and split on non-alphanumeric characters.</li>
 *   <li>Drop stop-words and style boilerplate.</li>
 *   <li>Expand synonym groups — e.g. "kid" → "child", "eating" → "eat".</li>
 *   <li>Lightweight suffix-stripping (porter-lite) — "playing" → "play",
 *       "doctor's" → "doctor", etc.</li>
 * </ol>
 *
 * <p>Uses a single bounded LRU map holding immutable {@link CacheEntry} records
 * (containing both JPEG bytes and TF-IDF vectors) to guarantee atomic eviction
 * and eliminate copy-snapshot overhead.
 */
@Component
public class SemanticImageCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticImageCache.class);

    /**
     * Cosine similarity threshold above which a cache hit is declared.
     * Lowered to 0.55 — with imagePrompts now anchored to general scene settings,
     * prompts for the same location share most tokens. Small surface variations
     * (e.g. "wooden table" vs "round table") still score above this threshold.
     */
    static final double SIMILARITY_THRESHOLD = 0.55;

    /** Maximum number of cached entries before LRU eviction kicks in. */
    private static final int MAX_ENTRIES = 1000;

    /** Stop-words that carry no semantic weight for image prompts. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "at", "to",
            "for", "with", "is", "are", "that", "this", "it", "its", "be",
            "by", "as", "from", "up", "was", "has", "have", "had", "not"
    );

    /**
     * Fixed style-template terms appended to every prompt.
     * Stripped before vectorisation so similarity is computed on the scene
     * subject only — not on the shared boilerplate that all prompts share.
     *
     * <p>Also includes common scene-dressing positional/spatial/colour words
     * that add visual flavour but do not identify the core activity
     * (e.g. "sunny", "wall", "against", "standing", "first", "smiling").
     */
    private static final Set<String> BOILERPLATE_WORDS = Set.of(
            // Style boilerplate
            "colorful", "cute", "kids", "illustration", "storybook", "art",
            "bright", "colors", "simple", "background", "no", "text",
            "friendly", "style", "cartoon", "digital", "painting", "render",
            "scene", "image", "photo", "picture",
            // Scene-dressing: spatial / positional
            "against", "behind", "beside", "front", "side", "corner",
            "wall", "door", "tree", "outside", "inside", "nearby",
            // Scene-dressing: descriptive adjectives
            "sunny", "happy", "smiling", "laughing", "excited", "cheerful",
            "young", "little", "small", "big", "tall", "short",
            // Scene-dressing: ordinal / sequence words
            "first", "second", "next", "last", "together", "alone",
            // Scene-dressing: common filler verbs already covered by synonyms
            "going", "starting", "beginning", "trying"
    );

    /**
     * Synonym map: any key token is normalised to its canonical value before
     * vectorisation. This lets prompts using different vocabulary for the same
     * concept share tokens and score higher cosine similarity.
     *
     * <p>Rules of thumb used here:
     * <ul>
     *   <li>Child-related: kid/child/boy/girl → "child"</li>
     *   <li>Food/drink actions: eating/drinking/ordering/buying → base verb</li>
     *   <li>Medical: doctor/physician/nurse → "doctor"</li>
     *   <li>Location synonyms: shop/store/market, clinic/hospital, café/cafe</li>
     *   <li>Verbs with common surface variants: smiling/smiles → "smile"</li>
     * </ul>
     */
    private static final Map<String, String> SYNONYMS;
    static {
        Map<String, String> s = new HashMap<>();
        // People
        s.put("kid",       "child");
        s.put("boy",       "child");
        s.put("girl",      "child");
        s.put("children",  "child");
        s.put("student",   "child");
        s.put("pupil",     "child");
        s.put("man",       "person");
        s.put("woman",     "person");
        s.put("lady",      "person");
        s.put("gentleman", "person");
        s.put("people",    "person");
        // Medical
        s.put("physician", "doctor");
        s.put("nurse",     "doctor");
        s.put("medic",     "doctor");
        // Locations
        s.put("shop",      "store");
        s.put("market",    "store");
        s.put("supermarket","store");
        s.put("clinic",    "hospital");
        s.put("cafe",      "cafeteria");
        s.put("café",      "cafeteria");
        s.put("canteen",   "cafeteria");
        s.put("classroom", "school");
        s.put("airport",   "airport");
        s.put("pharmacy",  "pharmacy");
        s.put("chemist",   "pharmacy");
        s.put("drugstore", "pharmacy");
        s.put("bank",      "bank");
        s.put("restaurant","restaurant");
        s.put("diner",     "restaurant");
        s.put("eatery",    "restaurant");
        // Actions
        s.put("eating",    "eat");
        s.put("eats",      "eat");
        s.put("ate",       "eat");
        s.put("drinking",  "drink");
        s.put("drinks",    "drink");
        s.put("drank",     "drink");
        s.put("ordering",  "order");
        s.put("orders",    "order");
        s.put("ordered",   "order");
        s.put("buying",    "buy");
        s.put("buys",      "buy");
        s.put("bought",    "buy");
        s.put("paying",    "pay");
        s.put("pays",      "pay");
        s.put("paid",      "pay");
        s.put("sitting",   "sit");
        s.put("sits",      "sit");
        s.put("sat",       "sit");
        s.put("standing",  "stand");
        s.put("stands",    "stand");
        s.put("stood",     "stand");
        s.put("walking",   "walk");
        s.put("walks",     "walk");
        s.put("walked",    "walk");
        s.put("smiling",   "smile");
        s.put("smiles",    "smile");
        s.put("smiled",    "smile");
        s.put("talking",   "talk");
        s.put("talks",     "talk");
        s.put("talked",    "talk");
        s.put("speaking",  "speak");
        s.put("speaks",    "speak");
        s.put("spoke",     "speak");
        s.put("asking",    "ask");
        s.put("asks",      "ask");
        s.put("asked",     "ask");
        s.put("looking",   "look");
        s.put("looks",     "look");
        s.put("looked",    "look");
        s.put("holding",   "hold");
        s.put("holds",     "hold");
        s.put("held",      "hold");
        s.put("showing",   "show");
        s.put("shows",     "show");
        s.put("showed",    "show");
        s.put("playing",   "play");
        s.put("plays",     "play");
        s.put("played",    "play");
        s.put("reading",   "read");
        s.put("reads",     "read");
        s.put("writing",   "write");
        s.put("writes",    "write");
        s.put("wrote",     "write");
        s.put("learning",  "learn");
        s.put("learns",    "learn");
        s.put("learned",   "learn");
        s.put("helping",   "help");
        s.put("helps",     "help");
        s.put("helped",    "help");
        s.put("working",   "work");
        s.put("works",     "work");
        s.put("worked",    "work");
        s.put("waiting",   "wait");
        s.put("waits",     "wait");
        s.put("waited",    "wait");
        s.put("arriving",  "arrive");
        s.put("arrives",   "arrive");
        s.put("arrived",   "arrive");
        s.put("entering",  "enter");
        s.put("enters",    "enter");
        s.put("entered",   "enter");
        // Objects / misc
        s.put("bill",      "check");
        s.put("receipt",   "check");
        s.put("medicine",  "medication");
        s.put("medicines", "medication");
        s.put("drug",      "medication");
        s.put("drugs",     "medication");
        s.put("ticket",    "ticket");
        s.put("tickets",   "ticket");
        s.put("bus",       "bus");
        s.put("car",       "car");
        s.put("vehicle",   "car");
        s.put("automobile","car");
        s.put("road",      "street");
        s.put("avenue",    "street");
        s.put("sunny",     "bright");
        s.put("cheerful",  "happy");
        s.put("joyful",    "happy");
        SYNONYMS = Collections.unmodifiableMap(s);
    }

    /**
     * Immutable value object holding image bytes and a raw TF vector.
     *
     * <p>Stores term frequency only (not TF-IDF) so that IDF weights are
     * always applied fresh from the current corpus state at query time,
     * preventing stale weights as the cache grows.
     */
    private record CacheEntry(byte[] jpeg, Map<String, Double> tf) {}

    /** Lock guarding access to the LRU cache. */
    private final Object lock = new Object();

    /** Bounded LRU map: prompt → CacheEntry. */
    private final Map<String, CacheEntry> cache;

    /** GCS store — absent when {@code image.cache.gcs.enabled} is not {@code true}. */
    private final Optional<GcsImageStore> gcsStore;

    public SemanticImageCache(Optional<GcsImageStore> gcsStore) {
        this.gcsStore = gcsStore;
        this.cache    = new LruMap(MAX_ENTRIES);
        loadFromGcs();
    }

    /**
     * Warm the in-memory cache from GCS at startup.
     * Called once from the constructor — runs synchronously so the cache is
     * fully populated before the first request is served.
     */
    private void loadFromGcs() {
        gcsStore.ifPresent(store -> {
            List<GcsImageStore.Entry> entries = store.loadAll();
            synchronized (lock) {
                for (GcsImageStore.Entry e : entries) {
                    Map<String, Double> tf = toTfLocked(e.prompt());
                    cache.put(e.prompt(), new CacheEntry(e.jpeg(), tf));
                }
            }
            log.info("[semantic-cache] warmed {} entries from GCS", entries.size());
        });
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

            // 2. O(N) Semantic search — IDF applied fresh at query time so weights
            //    are always consistent with the current corpus state.
            Map<String, Double> queryTf = toTfLocked(prompt);
            if (queryTf.isEmpty()) return null;
            Map<String, Double> queryVec = applyIdfAndNormaliseLocked(queryTf);

            double bestScore = 0.0;
            String bestKey   = null;
            byte[] bestJpeg  = null;

            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                Map<String, Double> entryVec = applyIdfAndNormaliseLocked(entry.getValue().tf());
                double score = cosineSimilarity(queryVec, entryVec);
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
     * Store a prompt and its generated JPEG bytes in the cache,
     * and persist to GCS asynchronously when the store is configured.
     */
    public void put(String prompt, byte[] jpeg) {
        if (prompt == null || jpeg == null || jpeg.length == 0) return;

        synchronized (lock) {
            // Store raw TF only — IDF will be applied fresh at query time.
            Map<String, Double> tf = toTfLocked(prompt);
            cache.put(prompt, new CacheEntry(jpeg, tf));
        }

        log.debug("[semantic-cache] stored prompt='{}' size={}KB",
                truncate(prompt), jpeg.length / 1024);

        gcsStore.ifPresent(store -> store.saveAsync(prompt, jpeg));
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
     * Build a raw term-frequency map for {@code prompt} (no IDF, no normalisation).
     * Stored in {@link CacheEntry} so IDF can be applied fresh at query time.
     * Must be called with {@link #lock} held.
     */
    private static Map<String, Double> toTfLocked(String prompt) {
        String[] tokens = tokenise(prompt);
        if (tokens.length == 0) return Collections.emptyMap();

        Map<String, Integer> counts = new HashMap<>();
        for (String t : tokens) {
            counts.merge(t, 1, Integer::sum);
        }

        Map<String, Double> tf = new HashMap<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            tf.put(e.getKey(), (double) e.getValue() / tokens.length);
        }
        return tf;
    }

    /**
     * Apply current-corpus IDF weights to a raw TF map and L2-normalise the result.
     * Because IDF is computed from the live cache, all vectors — stored and query —
     * always use the same weight scale regardless of when they were inserted.
     * Must be called with {@link #lock} held.
     */
    private Map<String, Double> applyIdfAndNormaliseLocked(Map<String, Double> tf) {
        if (tf.isEmpty()) return Collections.emptyMap();

        int totalDocs = cache.size() + 1; // +1 for the query document

        Map<String, Double> vec = new HashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            String term      = e.getKey();
            int docsWithTerm = countDocsContainingLocked(term);
            double idf       = Math.log(1.0 + (double) totalDocs / (1.0 + docsWithTerm));
            vec.put(term, e.getValue() * idf);
        }
        return l2Normalise(vec);
    }

    /** Must be called with {@link #lock} held. */
    private int countDocsContainingLocked(String term) {
        int count = 0;
        for (CacheEntry entry : cache.values()) {
            if (entry.tf().containsKey(term)) count++;
        }
        return count;
    }

    /**
     * Tokenise, filter stop-words/boilerplate, apply synonym expansion,
     * then apply lightweight suffix-stripping so inflected forms collapse
     * to a shared stem ("running" → "run", "doctor's" → "doctor").
     */
    static String[] tokenise(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1)
                .filter(t -> !STOP_WORDS.contains(t))
                .filter(t -> !BOILERPLATE_WORDS.contains(t))
                .map(SemanticImageCache::normalise)
                .filter(t -> t.length() > 1)
                .filter(t -> !STOP_WORDS.contains(t))
                .filter(t -> !BOILERPLATE_WORDS.contains(t))
                .toArray(String[]::new);
    }

    /**
     * Apply synonym lookup first, then porter-lite suffix stripping.
     * Synonym lookup is done before stemming so the canonical form gets stemmed
     * consistently (e.g. "children" → "child" → "child", not "children" → "children").
     */
    private static String normalise(String token) {
        String canonical = SYNONYMS.getOrDefault(token, token);
        return stem(canonical);
    }

    /**
     * Minimal suffix-stripping (porter-lite) — covers the most frequent
     * English inflection patterns without requiring an external library.
     *
     * <p>Ordered from longest to shortest suffix to avoid over-stripping.
     */
    private static String stem(String word) {
        int len = word.length();
        if (len <= 3) return word;

        // -ing  (running → run, playing → play, but keep "ring", "sing")
        if (len > 5 && word.endsWith("ing")) {
            String base = word.substring(0, len - 3);
            // if base ends in doubled consonant, remove one (running → run)
            if (base.length() > 2) {
                char last = base.charAt(base.length() - 1);
                char prev = base.charAt(base.length() - 2);
                if (last == prev && isConsonant(last)) {
                    return base.substring(0, base.length() - 1);
                }
            }
            return base;
        }

        // -tion / -sion (examination → examin, but keep short words)
        if (len > 6 && (word.endsWith("tion") || word.endsWith("sion"))) {
            return word.substring(0, len - 4);
        }

        // -ness (happiness → happi — not useful here, skip)
        // -ment (payment → pay)
        if (len > 6 && word.endsWith("ment")) {
            return word.substring(0, len - 4);
        }

        // -er / -or (teacher → teach, doctor → stays via synonym map)
        if (len > 5 && (word.endsWith("er") || word.endsWith("or"))) {
            return word.substring(0, len - 2);
        }

        // -ed  (walked → walk, studied → studi — acceptable)
        if (len > 4 && word.endsWith("ed")) {
            String base = word.substring(0, len - 2);
            // doubled consonant: stopped → stop
            if (base.length() > 2) {
                char last = base.charAt(base.length() - 1);
                char prev = base.charAt(base.length() - 2);
                if (last == prev && isConsonant(last)) {
                    return base.substring(0, base.length() - 1);
                }
            }
            return base;
        }

        // -s  (tables → table, buses → buse→ acceptable, keeps key noun stems)
        // Strip only the trailing "s" so "tables"→"table" and "table"→"table" share a token.
        if (len > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, len - 1);
        }

        return word;
    }

    private static boolean isConsonant(char c) {
        return "bcdfghjklmnpqrstvwxyz".indexOf(c) >= 0;
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
