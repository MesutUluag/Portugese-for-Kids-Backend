package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SemanticImageCache}.
 *
 * <p>No Spring context is loaded — the cache is instantiated directly.
 *
 * <p>The tokenisation pipeline (stop-word removal → synonym expansion → porter-lite stemming)
 * means that semantically equivalent prompts using different vocabulary collapse to the same
 * token set and therefore score 1.0 cosine similarity against each other.
 * Tests are grouped by the specific normalisation step they exercise.
 */
class SemanticImageCacheTest {

    private static final byte[] IMAGE_A = {1, 2, 3};
    private static final byte[] IMAGE_B = {4, 5, 6};

    private SemanticImageCache cache;

    @BeforeEach
    void setUp() {
        cache = new SemanticImageCache(Optional.empty());
    }

    // -------------------------------------------------------------------------
    // put / size basics
    // -------------------------------------------------------------------------

    @Test
    void put_increasesSize() {
        cache.put("a child waving at a teacher in a sunny classroom", IMAGE_A);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void put_nullPrompt_isIgnored() {
        cache.put(null, IMAGE_A);
        assertThat(cache.size()).isZero();
    }

    @Test
    void put_nullBytes_isIgnored() {
        cache.put("a child waving at a teacher in a sunny classroom", null);
        assertThat(cache.size()).isZero();
    }

    @Test
    void put_emptyBytes_isIgnored() {
        cache.put("a child waving at a teacher in a sunny classroom", new byte[0]);
        assertThat(cache.size()).isZero();
    }

    // -------------------------------------------------------------------------
    // get — null / blank / empty cache guards
    // -------------------------------------------------------------------------

    @Test
    void get_nullPrompt_returnsNull() {
        assertThat(cache.get(null)).isNull();
    }

    @Test
    void get_blankPrompt_returnsNull() {
        assertThat(cache.get("   ")).isNull();
    }

    @Test
    void get_onEmptyCache_returnsNull() {
        assertThat(cache.get("child playing in school yard")).isNull();
    }

    // -------------------------------------------------------------------------
    // Exact-match hit
    // -------------------------------------------------------------------------

    @Test
    void get_exactMatchPrompt_returnsBytes() {
        String prompt = "a child asking for the bill at a restaurant table";
        cache.put(prompt, IMAGE_A);

        assertThat(cache.get(prompt)).isEqualTo(IMAGE_A);
    }

    @Test
    void get_exactMatch_returnsCorrectBytesWhenMultipleEntriesExist() {
        String promptA = "a child asking for the bill at a restaurant table";
        String promptB = "a doctor examining a child in a hospital room";
        cache.put(promptA, IMAGE_A);
        cache.put(promptB, IMAGE_B);

        assertThat(cache.get(promptA)).isEqualTo(IMAGE_A);
        assertThat(cache.get(promptB)).isEqualTo(IMAGE_B);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — boilerplate stripping
    // -------------------------------------------------------------------------

    @Test
    void get_boilerplateOnlyDifference_treatedAsSamePrompt() {
        // Boilerplate suffix is stripped before vectorisation → same vector → score 1.0
        String base       = "smiling child waving teacher school";
        String withSuffix = "smiling child waving teacher school, colorful cute kids illustration, storybook art, bright colors, simple background, no text";

        cache.put(base, IMAGE_A);

        assertThat(cache.get(withSuffix)).isEqualTo(IMAGE_A);
    }

    @Test
    void get_boilerplateOnlyDifference_reversed_treatedAsSamePrompt() {
        // Stored prompt has boilerplate; query does not
        String withSuffix = "child holding medicine at pharmacy, colorful cute kids illustration, storybook art";
        String base       = "child holds medicine at pharmacy";

        cache.put(withSuffix, IMAGE_A);

        assertThat(cache.get(base)).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — minor surface variation (word order, extra adjective)
    // -------------------------------------------------------------------------

    @Test
    void get_wordOrderDifference_returnsBytes() {
        cache.put("child asking for bill at restaurant table", IMAGE_A);

        // Same tokens, different order
        assertThat(cache.get("restaurant table bill asking child")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_extraDescriptorWord_returnsBytes() {
        cache.put("child asking for bill at restaurant table scene", IMAGE_A);

        // One extra word not in the stored prompt
        assertThat(cache.get("child asking for bill at restaurant table")).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — synonym expansion
    // -------------------------------------------------------------------------

    @Test
    void get_kidVsChild_synonymHit() {
        // "kid" and "child" both normalise to "child"
        cache.put("child sitting at restaurant table", IMAGE_A);

        assertThat(cache.get("kid sitting at restaurant table")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_boyVsChild_synonymHit() {
        cache.put("child waving at doctor in hospital", IMAGE_A);

        assertThat(cache.get("boy waving at doctor in hospital")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_girlVsChild_synonymHit() {
        cache.put("child reading book at school", IMAGE_A);

        assertThat(cache.get("girl reading book at school")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_shopVsStore_synonymHit() {
        // "shop" → "store"
        cache.put("child buying fruit at store", IMAGE_A);

        assertThat(cache.get("child buying fruit at shop")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_clinicVsHospital_synonymHit() {
        // "clinic" → "hospital"
        cache.put("doctor examining child at hospital", IMAGE_A);

        assertThat(cache.get("doctor examining child at clinic")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_cafeVsCafeteria_synonymHit() {
        // "cafe" → "cafeteria"
        cache.put("child drinking coffee at cafeteria", IMAGE_A);

        assertThat(cache.get("child drinking coffee at cafe")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_classroomVsSchool_synonymHit() {
        // "classroom" → "school"
        cache.put("child learning letters at school", IMAGE_A);

        assertThat(cache.get("child learning letters at classroom")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_nurseVsDoctor_synonymHit() {
        // "nurse" → "doctor"
        cache.put("doctor helping child at hospital", IMAGE_A);

        assertThat(cache.get("nurse helping child at hospital")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_chemistVsPharmacy_synonymHit() {
        // "chemist" → "pharmacy"
        cache.put("child picking up medication at pharmacy", IMAGE_A);

        assertThat(cache.get("child picking up medication at chemist")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_dinerVsRestaurant_synonymHit() {
        // "diner" → "restaurant"
        cache.put("child eating soup at restaurant", IMAGE_A);

        assertThat(cache.get("child eating soup at diner")).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — verb inflection via synonym map
    // -------------------------------------------------------------------------

    @Test
    void get_eatingVsEat_synonymHit() {
        // "eating" → "eat"
        cache.put("child eat soup at restaurant table", IMAGE_A);

        assertThat(cache.get("child eating soup at restaurant table")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_askingVsAsk_synonymHit() {
        // "asking" → "ask"
        cache.put("child ask for bill at restaurant", IMAGE_A);

        assertThat(cache.get("child asking for bill at restaurant")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_payingVsPay_synonymHit() {
        // "paying" → "pay"
        cache.put("child pay at pharmacy counter", IMAGE_A);

        assertThat(cache.get("child paying at pharmacy counter")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_smilingVsSmile_synonymHit() {
        // "smiling" → "smile"
        cache.put("child smile at teacher in school", IMAGE_A);

        assertThat(cache.get("child smiling at teacher in school")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_talkingVsTalk_synonymHit() {
        // "talking" → "talk"
        cache.put("child talk to doctor at hospital", IMAGE_A);

        assertThat(cache.get("child talking to doctor at hospital")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_waitingVsWait_synonymHit() {
        // "waiting" → "wait"
        cache.put("child wait at bus stop", IMAGE_A);

        assertThat(cache.get("child waiting at bus stop")).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — stemmer (inflections NOT in synonym map)
    // -------------------------------------------------------------------------

    @Test
    void get_stemming_pluralNoun() {
        // "tables" → stem → "tabl" (both prompts share the same stem)
        cache.put("child sitting at restaurant tables", IMAGE_A);

        assertThat(cache.get("child sitting at restaurant table")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_stemming_ingForm_notInSynonymMap() {
        // "arriving" is not in the synonym map, so the stemmer removes "-ing"
        cache.put("child arrive at airport gate", IMAGE_A);

        assertThat(cache.get("child arriving at airport gate")).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic hit — combined synonym + stemmer
    // -------------------------------------------------------------------------

    @Test
    void get_synonymPlusStemming_kidEatingAtDiner() {
        // stored: "child eat restaurant"  query: "kid eating diner"
        // kid→child, eating→eat, diner→restaurant
        cache.put("child eat at restaurant", IMAGE_A);

        assertThat(cache.get("kid eating at diner")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_synonymPlusStemming_girlWaitingAtClinic() {
        // girl→child, waiting→wait, clinic→hospital
        cache.put("child wait at hospital", IMAGE_A);

        assertThat(cache.get("girl waiting at clinic")).isEqualTo(IMAGE_A);
    }

    @Test
    void get_synonymPlusStemming_boyPayingAtChemist() {
        // boy→child, paying→pay, chemist→pharmacy
        cache.put("child pay at pharmacy counter", IMAGE_A);

        assertThat(cache.get("boy paying at chemist counter")).isEqualTo(IMAGE_A);
    }

    // -------------------------------------------------------------------------
    // Semantic miss — completely unrelated prompts
    // -------------------------------------------------------------------------

    @Test
    void get_completelyDifferentPrompt_returnsNull() {
        cache.put("child asking for bill at restaurant table", IMAGE_A);

        assertThat(cache.get("spaceship flying over moon at night")).isNull();
    }

    @Test
    void get_weatherVsRestaurant_returnsNull() {
        cache.put("child eating soup at restaurant", IMAGE_A);

        assertThat(cache.get("lightning storm over ocean waves")).isNull();
    }

    @Test
    void get_twoDistinctMedicalScenes_doNotCross() {
        // Hospital and pharmacy are both medical but describe different scenes
        cache.put("doctor examining child at hospital room", IMAGE_A);
        cache.put("child picking up medication at pharmacy counter", IMAGE_B);

        // Each query should resolve to its own image, not the other
        assertThat(cache.get("doctor examining child at hospital room")).isEqualTo(IMAGE_A);
        assertThat(cache.get("child picking up medication at pharmacy counter")).isEqualTo(IMAGE_B);
    }

    @Test
    void get_twoDistinctTransportScenes_doNotCross() {
        cache.put("child boarding bus at bus stop", IMAGE_A);
        cache.put("child checking passport at airport gate", IMAGE_B);

        assertThat(cache.get("child boarding bus at bus stop")).isEqualTo(IMAGE_A);
        assertThat(cache.get("child checking passport at airport gate")).isEqualTo(IMAGE_B);
    }

    // -------------------------------------------------------------------------
    // Stop-word / boilerplate filtering edge cases
    // -------------------------------------------------------------------------

    @Test
    void get_promptMadeOfOnlyStopWords_returnsNull() {
        cache.put("the and or in on at to for with is are", IMAGE_A);
        assertThat(cache.get("the and or in on at")).isNull();
    }

    @Test
    void get_promptMadeOfOnlyBoilerplate_returnsNull() {
        cache.put("colorful cute kids illustration storybook art bright colors simple background no text", IMAGE_A);
        assertThat(cache.get("colorful cute kids illustration storybook art")).isNull();
    }

    // -------------------------------------------------------------------------
    // put overwrites existing entry for the same prompt
    // -------------------------------------------------------------------------

    @Test
    void put_samePromptTwice_overwritesEntry() {
        String prompt = "child waving at teacher at school";
        cache.put(prompt, IMAGE_A);
        cache.put(prompt, IMAGE_B);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get(prompt)).isEqualTo(IMAGE_B);
    }

    // -------------------------------------------------------------------------
    // LRU eviction
    // -------------------------------------------------------------------------

    @Test
    void lruEviction_oldestEntryEvictedWhenCapacityExceeded() {
        int maxEntries = 500;
        for (int i = 0; i < maxEntries + 5; i++) {
            cache.put("unique prompt describing scene number " + i + " with distinct words", new byte[]{(byte) i});
        }
        assertThat(cache.size()).isEqualTo(maxEntries);
    }

    // -------------------------------------------------------------------------
    // tokenise() — direct unit tests on the normalisation pipeline
    // -------------------------------------------------------------------------

    @Test
    void tokenise_stopWordsRemoved() {
        String[] tokens = SemanticImageCache.tokenise("a child is at the school");
        // "a", "is", "at", "the" are stop-words; only "child" and "school" remain
        assertThat(tokens).containsExactlyInAnyOrder("child", "school");
    }

    @Test
    void tokenise_boilerplateWordsRemoved() {
        String[] tokens = SemanticImageCache.tokenise("child school colorful cute illustration");
        assertThat(tokens).containsExactlyInAnyOrder("child", "school");
    }

    @Test
    void tokenise_synonymExpansion_kidToChild() {
        String[] tokens = SemanticImageCache.tokenise("kid school");
        assertThat(tokens).contains("child");
        assertThat(tokens).doesNotContain("kid");
    }

    @Test
    void tokenise_synonymExpansion_eatingToEat() {
        String[] tokens = SemanticImageCache.tokenise("eating restaurant");
        assertThat(tokens).contains("eat");
        assertThat(tokens).doesNotContain("eating");
    }

    @Test
    void tokenise_synonymExpansion_clinicToHospital() {
        String[] tokens = SemanticImageCache.tokenise("child clinic");
        assertThat(tokens).contains("hospital");
        assertThat(tokens).doesNotContain("clinic");
    }

    @Test
    void tokenise_stemming_ingRemoved() {
        // "arriving" is not in synonym map; stemmer strips "-ing"
        String[] tokens = SemanticImageCache.tokenise("child arriving airport");
        // "arriving" → stem → "arriv"
        assertThat(tokens).doesNotContain("arriving");
    }

    @Test
    void tokenise_emptyAfterFiltering_returnsEmptyArray() {
        String[] tokens = SemanticImageCache.tokenise("colorful cute no text art");
        assertThat(tokens).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Thread safety — concurrent puts and gets must not throw
    // -------------------------------------------------------------------------

    @Test
    void concurrentPutsAndGets_doNotThrow() throws InterruptedException {
        int threads = 8;
        int opsPerThread = 50;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            exec.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        String prompt = "thread " + threadId + " scene " + i + " child playing";
                        cache.put(prompt, new byte[]{(byte) i});
                        cache.get(prompt);
                        cache.get("child playing at school scene " + i);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertThat(completed).as("All threads should complete within 10 seconds").isTrue();
        assertThat(cache.size()).isPositive();
    }
}
