package com.mesutuluag.portugeseforkidsbackend.image;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    static final double DEFAULT_SIMILARITY_THRESHOLD = 0.55;
    static final int DEFAULT_MAX_ENTRIES = 1000;
    static final double SIMILARITY_THRESHOLD = DEFAULT_SIMILARITY_THRESHOLD;
    static final int MAX_ENTRIES = DEFAULT_MAX_ENTRIES;

    /** Stop-words that carry no semantic weight for image prompts. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "at", "to",
            "for", "with", "is", "are", "that", "this", "it", "its", "be",
            "by", "as", "from", "up", "was", "has", "have", "had", "not",
            "about", "after", "into", "over", "down", "through", "their",
            "them", "our", "your", "his", "her", "while", "towards", "toward",
            "under", "near", "very", "much", "some", "any", "more", "before", "here", "until", "out",
            "they", "he", "she", "him", "we", "us", "i", "me", "you"
    );

    /**
     * Fixed style-template terms appended to every prompt.
     * Stripped before vectorisation so similarity is computed on the scene
     * subject only — not on the shared boilerplate that all prompts share.
     *
     * <p>Also includes common scene-dressing positional/spatial/colour words
     * that add visual flavour but do not identify the core activity
     * (e.g. "sunny", "wall", "against", "first").
     */
    private static final Set<String> BOILERPLATE_WORDS = Set.of(
            // Style boilerplate (US & UK spellings)
            "colorful", "colourful", "cute", "kids", "illustration", "storybook", "art",
            "bright", "colors", "colours", "simple", "background", "no", "text",
            "friendly", "style", "cartoon", "digital", "painting", "render",
            "scene", "image", "photo", "picture",
            // Scene-dressing: spatial / positional
            "against", "behind", "beside", "front", "side", "corner",
            "wall", "door", "tree", "outside", "inside", "nearby", "area",
            // Scene-dressing: descriptive adjectives & nationality boilerplate
            "sunny", "happy", "laughing", "excited", "cheerful", "joyful",
            "cozy", "traditional", "formal", "official", "original", "fresh", "new",
            "young", "little", "small", "big", "tall", "short",
            "wooden", "plastic", "round", "filled", "portuguese", "portugal", "lisbon", "city",
            // Scene-dressing: ordinal / sequence words
            "first", "second", "next", "last", "together", "alone", "two",
            // Scene-dressing: common filler verbs & adverbs
            "going", "starting", "beginning", "trying", "using",
            "carefully", "nervously", "quickly", "slowly", "narrow", "rear", "back",
            "empty", "free", "day", "time", "arrival", "spot", "space", "sign", "direction",
            "turn", "convenience", "uniform", "contactless", "successful", "purchase", "open", "distance",
            "permission", "finished", "finish", "nodding", "nod", "leaning", "lean", "word", "sentence",
            "room", "math", "away", "fetch", "gestur", "gesture", "gesturing", "taking", "symptom", "symptoms", "start"
    );

    /** Nouns ending in -er/-or that should not have their suffix stripped to a verb stem. */
    private static final Set<String> KEEP_ER_OR_NOUNS = Set.of(
            "waiter", "counter", "dinner", "number", "water", "scooter",
            "mother", "father", "sister", "brother", "finger", "paper",
            "officer", "teller", "cashier", "driver", "customer", "shopkeeper", "passenger",
            "fishmonger", "receptionist", "attendant"
    );

    /**
     * Core location/domain stems that anchor a prompt to a specific story setting.
     * Given a 2x TF weight boost and used as a cross-domain guard when both query and
     * candidate contain domain stems.
     */
    private static final Set<String> DOMAIN_STEMS = Set.of(
            "school", "playground", "whiteboard",
            "restaurant", "waiter",
            "bank", "multibanco", "atm",
            "hospital", "doct",
            "cafeteria", "nata", "terrace",
            "airport", "board", "checkpoint",
            "store", "stall", "aisle", "trolley", "shopkeeper",
            "bus",
            "pharmacy", "pharmacist",
            "petrol", "pump", "fuel",
            "motorway", "traffic", "toll", "park", "roundabout", "seatbelt",
            "immigra", "biometric", "visa"
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
        s.put("student",   "school");
        s.put("pupil",     "school");
        s.put("baby",      "child");
        s.put("friend",    "child");
        s.put("classmate", "child");
        s.put("man",       "person");
        s.put("woman",     "person");
        s.put("lady",      "person");
        s.put("gentleman", "person");
        s.put("people",    "person");
        s.put("adult",     "person");
        s.put("applicant", "person");
        s.put("customer",  "person");
        s.put("patient",   "person");
        s.put("parent",    "person");
        s.put("parents",   "person");
        s.put("family",    "person");
        s.put("father",    "person");
        s.put("mother",    "person");
        // Medical
        s.put("physician", "doctor");
        s.put("nurse",     "doctor");
        s.put("medic",     "doctor");
        // Locations & roles
        s.put("shop",      "store");
        s.put("market",    "store");
        s.put("supermarket","store");
        s.put("clinic",    "hospital");
        s.put("cafe",      "cafeteria");
        s.put("pastelaria","cafeteria");
        s.put("gas",       "petrol");
        s.put("galp",      "petrol");
        s.put("gasoline",  "fuel");
        s.put("diesel",    "fuel");
        s.put("assistant", "attendant");
        s.put("cashier",   "attendant");
        s.put("classroom", "school");
        s.put("schoolwork","school");
        s.put("whiteboard","school");
        s.put("teacher",   "school");
        s.put("teachers",  "school");
        s.put("textbook",  "book");
        s.put("reception", "receptionist");
        s.put("airport",   "airport");
        s.put("pharmacy",  "pharmacy");
        s.put("chemist",   "pharmacy");
        s.put("drugstore", "pharmacy");
        s.put("bank",      "bank");
        s.put("restaurant","restaurant");
        s.put("diner",     "restaurant");
        s.put("eatery",    "restaurant");
        s.put("aima",      "immigration");
        s.put("government","immigration");
        s.put("highway",   "motorway");
        s.put("backseat",  "seat");
        s.put("dispenser", "machine");
        s.put("saline",    "medication");
        s.put("ampoule",   "medication");
        s.put("ampoules",  "medication");
        s.put("syrup",     "medication");
        s.put("sachet",    "medication");
        s.put("sachets",   "medication");
        s.put("repellent", "medication");
        s.put("spray",     "medication");
        s.put("gel",       "medication");
        s.put("tube",      "medication");
        s.put("water",     "drink");
        s.put("juice",     "drink");
        s.put("juices",    "drink");
        s.put("wine",      "drink");
        s.put("codfish",   "cod");
        // Actions
        s.put("driving",    "drive");
        s.put("drives",     "drive");
        s.put("drove",      "drive");
        s.put("steering",   "drive");
        s.put("steers",     "drive");
        s.put("steered",    "drive");
        s.put("navigating", "drive");
        s.put("navigates",  "drive");
        s.put("navigated",  "drive");
        s.put("approaching","drive");
        s.put("approaches", "drive");
        s.put("approached", "drive");
        s.put("pulling",    "drive");
        s.put("pulls",      "drive");
        s.put("changing",   "drive");
        s.put("reversing",  "park");
        s.put("reverses",   "park");
        s.put("reversed",   "park");
        s.put("unbuckling", "check");
        s.put("buckling",   "check");
        s.put("placing",    "share");
        s.put("sharing",    "share");
        s.put("telling",    "talk");
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
        s.put("payment",   "pay");
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
        s.put("explaining","talk");
        s.put("explains",  "talk");
        s.put("explained", "talk");
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
        s.put("leaving",   "leave");
        s.put("leaves",    "leave");
        s.put("left",      "leave");
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
        s.put("luggage",   "bag");
        s.put("suitcase",  "bag");
        s.put("suitcases", "bag");
        s.put("bags",      "bag");
        s.put("bus",       "bus");
        s.put("car",       "car");
        s.put("vehicle",   "car");
        s.put("automobile","car");
        s.put("road",      "street");
        s.put("avenue",    "street");
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

    /** Lock guarding access to the LRU cache and document-frequency index. */
    private final Object lock = new Object();

    /** Shared RNG used to pick randomly among qualifying cache candidates. */
    private static final Random RANDOM = new Random();

    /** Bounded LRU map: prompt → CacheEntry. */
    private final Map<String, CacheEntry> cache;

    /**
     * Live document-frequency index: term → number of cached entries containing {@code term}.
     * Maintained incrementally on insert/overwrite/eviction so IDF lookups are O(1) instead of O(N).
     * Guarded by {@link #lock}.
     */
    private final Map<String, Integer> docFreq = new HashMap<>();

    /** GCS store — absent when {@code image.cache.gcs.enabled} is not {@code true}. */
    private final Optional<GcsImageStore> gcsStore;
    private final double similarityThreshold;
    private final int maxEntries;

    public SemanticImageCache(Optional<GcsImageStore> gcsStore) {
        this(gcsStore, false, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_MAX_ENTRIES);
    }

    public SemanticImageCache(Optional<GcsImageStore> gcsStore, boolean asyncWarmup) {
        this(gcsStore, asyncWarmup, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_MAX_ENTRIES);
    }

    @Autowired
    public SemanticImageCache(
            Optional<GcsImageStore> gcsStore,
            @Value("${image.cache.gcs.async-warmup:true}") boolean asyncWarmup,
            @Value("${image.cache.similarity-threshold:0.55}") double similarityThreshold,
            @Value("${image.cache.max-entries:1000}") int maxEntries) {
        this.gcsStore = gcsStore;
        this.similarityThreshold = similarityThreshold;
        this.maxEntries = maxEntries;
        this.cache = new LruMap(maxEntries);
        if (asyncWarmup && gcsStore.isPresent()) {
            CompletableFuture.runAsync(this::loadFromGcs);
        } else {
            loadFromGcs();
        }
    }

    /**
     * Warm the in-memory cache from GCS.
     * Tokenisation is performed outside the lock so incoming requests are never blocked
     * during background warmup.
     */
    private void loadFromGcs() {
        gcsStore.ifPresent(store -> {
            List<GcsImageStore.Entry> entries = store.loadAll(maxEntries);
            List<Map.Entry<String, CacheEntry>> prepared = new ArrayList<>(entries.size());
            for (GcsImageStore.Entry e : entries) {
                if (e.prompt() != null && e.jpeg() != null && e.jpeg().length > 0) {
                    prepared.add(Map.entry(e.prompt(), new CacheEntry(e.jpeg(), toTf(e.prompt()))));
                }
            }
            synchronized (lock) {
                for (Map.Entry<String, CacheEntry> item : prepared) {
                    // Do not overwrite newer entries added while warmup was running
                    if (!cache.containsKey(item.getKey())) {
                        putEntryLocked(item.getKey(), item.getValue());
                    }
                }
            }
            log.info("[semantic-cache] warmed {} entries from GCS", prepared.size());
        });
    }

    private final class LruMap extends LinkedHashMap<String, CacheEntry> {
        private final int maxSize;

        LruMap(int maxSize) {
            super(maxSize, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            boolean evict = size() > maxSize;
            if (evict) {
                decrementDocFreqLocked(eldest.getValue().tf());
            }
            return evict;
        }
    }

    /**
     * Look up the cache for an exact or semantically similar prompt.
     *
     * <p>When multiple cached entries exceed {@link #SIMILARITY_THRESHOLD} the method
     * picks one at random, so repeated calls with similar prompts return a variety of
     * images instead of always reusing the single best-matching one.
     *
     * @return cached JPEG bytes if a similar prompt was found, otherwise {@code null}
     */
    public byte[] get(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;

        // Pre-compute query TF outside the lock to minimise critical section time
        Map<String, Double> queryTf = null;

        synchronized (lock) {
            // 1. O(1) Fast path: exact string match (also updates LRU order)
            CacheEntry exactHit = cache.get(prompt);
            if (exactHit != null) {
                log.info("[semantic-cache] EXACT HIT key='{}'", truncate(prompt));
                return exactHit.jpeg();
            }

            if (cache.isEmpty()) return null;
        }

        queryTf = toTf(prompt);
        if (queryTf.isEmpty()) return null;

        synchronized (lock) {
            if (cache.isEmpty()) return null;

            // 2. O(N) Semantic search — IDF looked up in O(1) from live docFreq index
            Map<String, Double> queryVec = applyIdfAndNormaliseLocked(queryTf);
            Set<String> queryDomains = extractDomains(queryTf);

            // Collect all entries that exceed the similarity threshold so we can
            // randomly select one of the top-scoring matches.
            record Candidate(String key, byte[] jpeg, double score) {}
            List<Candidate> candidates = new ArrayList<>();
            double bestScore = 0.0;

            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                Map<String, Double> entryTf = entry.getValue().tf();
                if (!queryDomains.isEmpty()) {
                    Set<String> entryDomains = extractDomains(entryTf);
                    if (!entryDomains.isEmpty() && Collections.disjoint(queryDomains, entryDomains)) {
                        continue; // Cross-domain guard: prevent different story settings from matching
                    }
                }
                Map<String, Double> entryVec = applyIdfAndNormaliseLocked(entryTf);
                double score = cosineSimilarity(queryVec, entryVec);
                if (score > bestScore) bestScore = score;
                if (score >= similarityThreshold) {
                    candidates.add(new Candidate(entry.getKey(), entry.getValue().jpeg(), score));
                }
            }

            if (!candidates.isEmpty()) {
                double topTierFloor = Math.max(similarityThreshold, bestScore - 0.12);
                List<Candidate> topCandidates = candidates.stream()
                        .filter(c -> c.score() >= topTierFloor)
                        .toList();
                Candidate chosen = topCandidates.get(RANDOM.nextInt(topCandidates.size()));
                log.info("[semantic-cache] SEMANTIC HIT score={} key='{}' (picked 1 of {})",
                        String.format("%.3f", chosen.score()), truncate(chosen.key()), topCandidates.size());
                cache.get(chosen.key()); // touch to update LRU order for the matched key
                return chosen.jpeg();
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

        // Compute raw TF outside the lock — IDF will be applied fresh at query time.
        Map<String, Double> tf = toTf(prompt);
        CacheEntry newEntry = new CacheEntry(jpeg, tf);

        synchronized (lock) {
            putEntryLocked(prompt, newEntry);
        }

        log.debug("[semantic-cache] stored prompt='{}' size={}KB",
                truncate(prompt), jpeg.length / 1024);

        gcsStore.ifPresent(store -> store.saveAsync(prompt, jpeg));
    }

    private void putEntryLocked(String prompt, CacheEntry newEntry) {
        CacheEntry previous = cache.put(prompt, newEntry);
        if (previous != null) {
            decrementDocFreqLocked(previous.tf());
        }
        incrementDocFreqLocked(newEntry.tf());
    }

    private void incrementDocFreqLocked(Map<String, Double> tf) {
        for (String term : tf.keySet()) {
            docFreq.merge(term, 1, Integer::sum);
        }
    }

    private void decrementDocFreqLocked(Map<String, Double> tf) {
        for (String term : tf.keySet()) {
            docFreq.computeIfPresent(term, (k, v) -> v > 1 ? v - 1 : null);
        }
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

    private static Set<String> extractDomains(Map<String, Double> tf) {
        if (tf.isEmpty()) return Collections.emptySet();
        java.util.HashSet<String> domains = new java.util.HashSet<>();
        for (String term : tf.keySet()) {
            if (DOMAIN_STEMS.contains(term)) {
                domains.add(term);
            }
        }
        return domains;
    }

    /**
     * Build a raw term-frequency map for {@code prompt} (no IDF, no normalisation).
     * Core domain stems receive a 2x weight boost so scene setting anchors similarity.
     * Stored in {@link CacheEntry} so IDF can be applied fresh at query time.
     */
    private static Map<String, Double> toTf(String prompt) {
        String[] tokens = tokenise(prompt);
        if (tokens.length == 0) return Collections.emptyMap();

        Map<String, Double> counts = new HashMap<>();
        double totalWeight = 0.0;
        for (String t : tokens) {
            double weight = DOMAIN_STEMS.contains(t) ? 2.0 : 1.0;
            counts.merge(t, weight, Double::sum);
            totalWeight += weight;
        }

        Map<String, Double> tf = new HashMap<>();
        for (Map.Entry<String, Double> e : counts.entrySet()) {
            tf.put(e.getKey(), e.getValue() / totalWeight);
        }
        return tf;
    }

    /**
     * Apply current-corpus IDF weights to a raw TF map and L2-normalise the result.
     * Because IDF is computed from the live {@link #docFreq} index, all vectors —
     * stored and query — always use the same weight scale in O(T) time.
     * When the cache holds at least 10 entries, unseen query terms are dampened
     * (`effectiveDf = max(docsWithTerm, 2)`) so 1–2 incidental words do not tank similarity.
     * Must be called with {@link #lock} held.
     */
    private Map<String, Double> applyIdfAndNormaliseLocked(Map<String, Double> tf) {
        if (tf.isEmpty()) return Collections.emptyMap();

        int totalDocs = cache.size() + 1; // +1 for the query document
        int minDfFloor = cache.size() >= 10 ? 2 : 0;

        Map<String, Double> vec = new HashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            String term      = e.getKey();
            int docsWithTerm = Math.max(countDocsContainingLocked(term), minDfFloor);
            double idf       = Math.log(1.0 + (double) totalDocs / (1.0 + docsWithTerm));
            vec.put(term, e.getValue() * idf);
        }
        return l2Normalise(vec);
    }

    /** O(1) lookup of cached entries containing {@code term}. Must be called with {@link #lock} held. */
    private int countDocsContainingLocked(String term) {
        return docFreq.getOrDefault(term, 0);
    }

    /**
     * Strip diacritics, tokenise, filter stop-words/boilerplate, apply synonym expansion,
     * then apply lightweight suffix-stripping so inflected forms collapse
     * to a shared stem ("running" → "run", "doctor's" → "doctor", "café" → "cafeteria").
     */
    static String[] tokenise(String text) {
        String ascii = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\bbank\\s+cards?\\b", "card")
                .replaceAll("\\bshopping\\s+bags?\\b", "bag")
                .replaceAll("\\bservice\\s+(?:station|area)s?\\b", "petrol station")
                .replaceAll("\\b(?:convenience|petrol|gas|galp)\\s+station\\s+(?:convenience\\s+)?(?:shop|store)s?\\b", "petrol counter")
                .replaceAll("\\b(?:convenience|petrol|gas|galp)\\s+(?:shop|store)s?\\b", "petrol counter")
                .replaceAll("\\bpencil\\s+cases?\\b", "pencil")
                .replaceAll("\\bsigns?\\s+showing\\b", "sign")
                .replaceAll("\\bmedical\\s+(?:office|room|center|centre)s?\\b", "hospital")
                .replaceAll("\\bdisplay\\s+screen\\b", "board")
                .replaceAll("\\bdrink\\s+order\\b", "order");
        return Arrays.stream(ascii.split("[^a-z0-9]+"))
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

        // -er / -or (teacher → teach, doctor → doct, but preserve nouns like "waiter" or "counter")
        if (len > 5 && (word.endsWith("er") || word.endsWith("or")) && !KEEP_ER_OR_NOUNS.contains(word)) {
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
