package com.mesutuluag.portugeseforkidsbackend.user;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserStatsServiceTest {

    private UserStatsService service;

    @BeforeEach
    void setUp() {
        service = new UserStatsService(
                Optional.empty(), // in-memory fallback
                300,  // maxSecondsDelta
                5000, // maxScoreDelta
                50,   // maxStoriesDelta
                50,   // maxGamesDelta
                30    // maxDailyHistoryDays
        );
    }

    @Test
    void getOrCreateProfile_returnsDefaultProfileWhenEmpty() {
        UserProfile profile = service.getOrCreateProfile("u1", "test@kid.com", "Leo");

        assertThat(profile.uid()).isEqualTo("u1");
        assertThat(profile.email()).isEqualTo("test@kid.com");
        assertThat(profile.displayName()).isEqualTo("Leo");
        assertThat(profile.tier()).isEqualTo("FREE");
        assertThat(profile.stats().totalTimeSeconds()).isEqualTo(0);
        assertThat(profile.stats().currentStreakDays()).isEqualTo(0);
    }

    @Test
    void syncStats_firstActivity_setsStreakToOne() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StatsSyncRequest req = new StatsSyncRequest();
        req.setActiveSecondsDelta(60);
        req.setClientDate(today);

        UserProfile profile = service.syncStats("u1", "test@kid.com", "Leo", req);

        assertThat(profile.stats().currentStreakDays()).isEqualTo(1);
        assertThat(profile.stats().longestStreakDays()).isEqualTo(1);
        assertThat(profile.stats().lastActiveDate()).isEqualTo(today);
        assertThat(profile.stats().totalTimeSeconds()).isEqualTo(60);
        assertThat(profile.stats().dailyTimeSeconds().get(today)).isEqualTo(60);
    }

    @Test
    void syncStats_sameDayActivity_doesNotIncrementStreak() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StatsSyncRequest req1 = new StatsSyncRequest();
        req1.setActiveSecondsDelta(60);
        req1.setClientDate(today);
        service.syncStats("u1", "test@kid.com", "Leo", req1);

        StatsSyncRequest req2 = new StatsSyncRequest();
        req2.setActiveSecondsDelta(45);
        req2.setClientDate(today);
        UserProfile profile = service.syncStats("u1", "test@kid.com", "Leo", req2);

        assertThat(profile.stats().currentStreakDays()).isEqualTo(1);
        assertThat(profile.stats().longestStreakDays()).isEqualTo(1);
        assertThat(profile.stats().totalTimeSeconds()).isEqualTo(105);
        assertThat(profile.stats().dailyTimeSeconds().get(today)).isEqualTo(105);
    }

    @Test
    void syncStats_consecutiveDayActivity_incrementsStreak() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        StatsSyncRequest req1 = new StatsSyncRequest();
        req1.setActiveSecondsDelta(30);
        req1.setClientDate(yesterday.toString());
        service.syncStats("u1", "test@kid.com", "Leo", req1);

        StatsSyncRequest req2 = new StatsSyncRequest();
        req2.setActiveSecondsDelta(50);
        req2.setClientDate(today.toString());
        UserProfile profile = service.syncStats("u1", "test@kid.com", "Leo", req2);

        assertThat(profile.stats().currentStreakDays()).isEqualTo(2);
        assertThat(profile.stats().longestStreakDays()).isEqualTo(2);
    }

    @Test
    void syncStats_streakBreaksAfterGapDay_resetsStreakToOne() {
        LocalDate threeDaysAgo = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        // Pre-populate profile with an existing streak from 3 days ago
        UserStats previousStats = new UserStats(
                60, 2, 2, threeDaysAgo.toString(), 0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(threeDaysAgo.toString(), 60)
        );
        UserProfile previousProfile = new UserProfile("u1", "test@kid.com", "Leo", "FREE", previousStats, 1000L, 1000L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                org.springframework.test.util.ReflectionTestUtils.getField(service, "inMemoryProfiles"),
                "put", "u1", previousProfile
        );

        // User syncs today (3 days later -> gap day -> reset streak to 1, keep longest streak 2)
        StatsSyncRequest req = new StatsSyncRequest();
        req.setActiveSecondsDelta(10);
        req.setClientDate(today.toString());
        UserProfile profile = service.syncStats("u1", "test@kid.com", "Leo", req);

        assertThat(profile.stats().currentStreakDays()).isEqualTo(1);
        assertThat(profile.stats().longestStreakDays()).isEqualTo(2); // Best streak preserved!
    }

    @Test
    void syncStats_prunesDailyTimeSecondsTo30Days() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StatsSyncRequest req = new StatsSyncRequest();
        req.setActiveSecondsDelta(10);
        req.setClientDate(today);

        // Pre-populate with 35 days in memory
        UserProfile initial = service.getOrCreateProfile("u2", "u2@test.com", "Kid");
        Map<String, Integer> historical = new HashMap<>();
        for (int i = 35; i >= 1; i--) {
            historical.put(String.format("2026-01-%02d", i), 10);
        }
        UserStats statsWithHistory = new UserStats(
                350, 1, 1, "2026-01-35", 0, 0, 0,
                Map.of(), Map.of(), Map.of(), historical
        );
        service.syncStats("u2", "u2@test.com", "Kid", req); // sets initial

        // Directly invoke pruneDailyTime logic verification
        Map<String, Integer> pruned = FirestoreUserStore.pruneDailyTime(historical, 30);
        assertThat(pruned.size()).isEqualTo(30);
    }

    @Test
    void syncStats_clampsDeltasAndFiltersUnknownModesAndContexts() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StatsSyncRequest req = new StatsSyncRequest();
        req.setActiveSecondsDelta(99999); // max is 300
        req.setScoreDelta(88888);         // max is 5000
        req.setSpeechListenDelta(999);    // max is 250
        req.setClientDate(today);

        Map<String, Integer> modes = new HashMap<>();
        modes.put("cards", 500);          // clamp to 300
        modes.put("malicious_mode", 100); // should be dropped
        modes.put("game1", -10);          // should be dropped
        req.setTimeByModeDelta(modes);

        Map<String, Integer> contexts = new HashMap<>();
        contexts.put("school", 90);       // clamp to 50
        contexts.put("unknown_ctx", 10);  // dropped
        req.setStoriesByContextDelta(contexts);

        UserProfile profile = service.syncStats("u3", "u3@test.com", "Maya", req);

        assertThat(profile.stats().totalTimeSeconds()).isEqualTo(300);
        assertThat(profile.stats().totalScore()).isEqualTo(5000);
        assertThat(profile.stats().speechListenCount()).isEqualTo(250);
        assertThat(profile.stats().timeByModeSeconds()).containsOnlyKeys("cards");
        assertThat(profile.stats().timeByModeSeconds().get("cards")).isEqualTo(300);
        assertThat(profile.stats().storiesByContext()).containsOnlyKeys("school");
        assertThat(profile.stats().storiesByContext().get("school")).isEqualTo(50);
        assertThat(profile.stats().totalStoriesPracticed()).isEqualTo(50);
    }

    @Test
    void syncStats_invalidClientDate_fallsBackToUtcToday() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        StatsSyncRequest req1 = new StatsSyncRequest();
        req1.setActiveSecondsDelta(10);
        req1.setClientDate("invalid-date-string");

        UserProfile p1 = service.syncStats("u4", "u4@test.com", "Sam", req1);
        assertThat(p1.stats().lastActiveDate()).isEqualTo(today);

        StatsSyncRequest req2 = new StatsSyncRequest();
        req2.setActiveSecondsDelta(10);
        req2.setClientDate("2030-01-01"); // Far in future -> rejected

        UserProfile p2 = service.syncStats("u5", "u5@test.com", "Sam", req2);
        assertThat(p2.stats().lastActiveDate()).isEqualTo(today);
    }

    @Test
    void syncStats_zeroActivityPayload_doesNotIncrementStreakOrSetLastActiveDate() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        StatsSyncRequest emptyReq = new StatsSyncRequest();
        emptyReq.setActiveSecondsDelta(0);
        emptyReq.setClientDate(today);

        UserProfile profile = service.syncStats("u6", "u6@test.com", "Noop", emptyReq);
        assertThat(profile.stats().currentStreakDays()).isEqualTo(0);
        assertThat(profile.stats().longestStreakDays()).isEqualTo(0);
        assertThat(profile.stats().lastActiveDate()).isNull();
    }

    @Test
    void getOrCreateProfile_upgradesPlaceholderDisplayNameWhenJwtContainsRealName() {
        // First request arrives right after email sign-up before updateProfile completes
        UserProfile initial = service.getOrCreateProfile("u7", "maya@test.com", "Kid Explorer");
        assertThat(initial.displayName()).isEqualTo("Kid Explorer");

        // Second request arrives after updateProfile refreshes the JWT with "Maya"
        UserProfile upgraded = service.getOrCreateProfile("u7", "maya@test.com", "Maya");
        assertThat(upgraded.displayName()).isEqualTo("Maya");

        // Subsequent request without name claim preserves "Maya"
        UserProfile preserved = service.getOrCreateProfile("u7", "maya@test.com", "Kid Explorer");
        assertThat(preserved.displayName()).isEqualTo("Maya");
    }
}
