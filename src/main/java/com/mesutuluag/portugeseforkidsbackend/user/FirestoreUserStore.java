package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mesutuluag.portugeseforkidsbackend.exception.UserStoreException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

@Component
@ConditionalOnProperty(name = "firestore.enabled", havingValue = "true")
public class FirestoreUserStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreUserStore.class);

    private final Firestore firestore;
    private final String collectionName;
    private final Semaphore semaphore;

    @Autowired
    public FirestoreUserStore(
            @Value("${firestore.collection.users:users}") String collectionName,
            @Value("${firestore.max-concurrent-ops:32}") int maxConcurrentOps,
            @Value("${firestore.project-id:${GOOGLE_CLOUD_PROJECT:placeholder-gcp-project}}") String projectId) {
        this.collectionName = collectionName;
        this.semaphore = new Semaphore(maxConcurrentOps > 0 ? maxConcurrentOps : 32);
        FirestoreOptions options;
        try {
            options = FirestoreOptions.newBuilder().setProjectId(projectId).build();
        } catch (Exception e) {
            log.warn("[firestore-store] Falling back to default FirestoreOptions: {}", e.getMessage());
            options = FirestoreOptions.getDefaultInstance();
        }
        this.firestore = options.getService();
        log.info("[firestore-store] initialized for collection='{}', project='{}'", collectionName, options.getProjectId());
    }

    public FirestoreUserStore(Firestore firestore, String collectionName, int maxConcurrentOps) {
        this.firestore = firestore;
        this.collectionName = collectionName;
        this.semaphore = new Semaphore(maxConcurrentOps > 0 ? maxConcurrentOps : 32);
    }

    @PreDestroy
    void shutdown() {
        try {
            firestore.close();
            log.info("[firestore-store] Closed Firestore client");
        } catch (Exception e) {
            log.warn("[firestore-store] Error closing Firestore: {}", e.getMessage());
        }
    }

    public UserProfile getOrCreateProfile(String uid, String email, String displayName) {
        try {
            semaphore.acquire();
            try {
                DocumentReference docRef = firestore.collection(collectionName).document(uid);
                DocumentSnapshot snapshot = docRef.get().get(10, TimeUnit.SECONDS);

                if (!snapshot.exists()) {
                    UserProfile newProfile = UserProfile.defaultFor(uid, email, displayName);
                    saveProfileSnapshot(docRef, newProfile);
                    return newProfile;
                }

                String storedEmail = snapshot.getString("email");
                String storedName = snapshot.getString("displayName");
                UserProfile parsed = parseProfile(snapshot, uid, email, displayName);
                // Persist upgraded email/displayName if the stored document still had a placeholder
                if (!parsed.email().equals(storedEmail) || !parsed.displayName().equals(storedName)) {
                    saveProfileSnapshot(docRef, parsed);
                }
                return parsed;
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserStoreException("Interrupted fetching profile for uid=" + uid, e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("[firestore-store] Failed to get profile for uid={}: {}", uid, e.getMessage());
            throw new UserStoreException("Failed to get profile for uid=" + uid, e);
        }
    }

    public UserProfile syncStats(String uid, String email, String displayName, StatsSyncRequest sanitized) {
        return syncStats(uid, email, displayName, sanitized, 30);
    }

    public UserProfile syncStats(
            String uid,
            String email,
            String displayName,
            StatsSyncRequest sanitized,
            int maxDailyHistoryDays) {
        try {
            semaphore.acquire();
            try {
                DocumentReference docRef = firestore.collection(collectionName).document(uid);

                return firestore.runTransaction(transaction -> {
                    DocumentSnapshot snapshot = transaction.get(docRef).get();

                    UserProfile current = snapshot.exists()
                            ? parseProfile(snapshot, uid, email, displayName)
                            : UserProfile.defaultFor(uid, email, displayName);

                    UserProfile updatedProfile = UserStatsService.applySyncToProfile(
                            current, email, displayName, sanitized, maxDailyHistoryDays);

                    Map<String, Object> map = toMap(updatedProfile);
                    // Overwrite full document inside transaction so pruned keys in dailyTimeSeconds
                    // are actually removed rather than deep-merged back by SetOptions.merge().
                    transaction.set(docRef, map);

                    return updatedProfile;
                }).get(10, TimeUnit.SECONDS);

            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserStoreException("Interrupted syncing stats for uid=" + uid, e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("[firestore-store] Failed to sync stats for uid={}: {}", uid, e.getMessage());
            throw new UserStoreException("Failed to sync stats for uid=" + uid, e);
        }
    }

    private void saveProfileSnapshot(DocumentReference docRef, UserProfile profile) throws ExecutionException, InterruptedException, TimeoutException {
        docRef.set(toMap(profile)).get(10, TimeUnit.SECONDS);
    }

    private Map<String, Object> toMap(UserProfile profile) {
        Map<String, Object> map = new HashMap<>();
        map.put("uid", profile.uid());
        map.put("email", profile.email());
        map.put("displayName", profile.displayName());
        map.put("tier", profile.tier());
        map.put("createdAt", profile.createdAt());
        map.put("updatedAt", profile.updatedAt());

        UserStats stats = profile.stats();
        Map<String, Object> statsMap = new HashMap<>();
        statsMap.put("totalTimeSeconds", stats.totalTimeSeconds());
        statsMap.put("currentStreakDays", stats.currentStreakDays());
        statsMap.put("longestStreakDays", stats.longestStreakDays());
        statsMap.put("lastActiveDate", stats.lastActiveDate());
        statsMap.put("totalStoriesPracticed", stats.totalStoriesPracticed());
        statsMap.put("totalScore", stats.totalScore());
        statsMap.put("speechListenCount", stats.speechListenCount());
        statsMap.put("timeByModeSeconds", stats.timeByModeSeconds());
        statsMap.put("gamesCompletedByMode", stats.gamesCompletedByMode());
        statsMap.put("storiesByContext", stats.storiesByContext());
        statsMap.put("dailyTimeSeconds", stats.dailyTimeSeconds());

        map.put("stats", statsMap);
        return map;
    }

    @SuppressWarnings("unchecked")
    private UserProfile parseProfile(DocumentSnapshot snapshot, String uid, String email, String displayName) {
        String existingEmail = snapshot.getString("email");
        String existingName = snapshot.getString("displayName");
        String tier = snapshot.getString("tier");
        Long createdAt = snapshot.getLong("createdAt");
        Long updatedAt = snapshot.getLong("updatedAt");

        Map<String, Object> statsMap = (Map<String, Object>) snapshot.get("stats");
        UserStats stats = parseStats(statsMap);

        return new UserProfile(
                uid,
                UserStatsService.resolveEmail(existingEmail, email),
                UserStatsService.resolveDisplayName(existingName, displayName),
                (tier != null && !tier.isBlank()) ? tier : "FREE",
                stats,
                createdAt != null ? createdAt : System.currentTimeMillis(),
                updatedAt != null ? updatedAt : System.currentTimeMillis()
        );
    }

    private UserStats parseStats(Map<String, Object> map) {
        if (map == null) {
            return UserStats.empty();
        }

        int totalTime = getInt(map, "totalTimeSeconds");
        int currentStreak = getInt(map, "currentStreakDays");
        int longestStreak = getInt(map, "longestStreakDays");
        String lastActiveDate = (String) map.get("lastActiveDate");
        int totalStories = getInt(map, "totalStoriesPracticed");
        int totalScore = getInt(map, "totalScore");
        int speechListen = getInt(map, "speechListenCount");

        Map<String, Integer> timeByMode = getIntegerMap(map, "timeByModeSeconds");
        Map<String, Integer> gamesCompleted = getIntegerMap(map, "gamesCompletedByMode");
        Map<String, Integer> storiesByCtx = getIntegerMap(map, "storiesByContext");
        Map<String, Integer> dailyTime = getIntegerMap(map, "dailyTimeSeconds");

        return new UserStats(
                totalTime,
                currentStreak,
                longestStreak,
                lastActiveDate,
                totalStories,
                totalScore,
                speechListen,
                timeByMode,
                gamesCompleted,
                storiesByCtx,
                dailyTime
        );
    }

    private int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private Map<String, Integer> getIntegerMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map<?, ?> rawMap) {
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof Number n) {
                    result.put(entry.getKey().toString(), n.intValue());
                }
            }
            return result;
        }
        return Collections.emptyMap();
    }

    static Map<String, Integer> pruneDailyTime(Map<String, Integer> map, int maxDays) {
        return UserStatsService.pruneDailyTime(map, maxDays);
    }
}
