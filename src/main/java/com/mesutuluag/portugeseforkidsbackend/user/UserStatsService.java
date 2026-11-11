package com.mesutuluag.portugeseforkidsbackend.user;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UserStatsService {

    private static final Logger log = LoggerFactory.getLogger(UserStatsService.class);

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public static final Set<String> ALLOWED_MODES = Set.of(
            "cards", "story", "game1", "game2", "game3", "game4", "game5", "game6", "game7", "game8"
    );

    public static final Set<String> ALLOWED_CONTEXTS = Set.of(
            "school", "restaurant", "bank", "hospital", "cafe", "airport",
            "market", "aima", "bus", "pharmacy", "gas_station", "traffic"
    );

    private final Optional<FirestoreUserStore> firestoreUserStore;
    private final int maxSecondsDelta;
    private final int maxScoreDelta;
    private final int maxStoriesDelta;
    private final int maxGamesDelta;
    private final int maxDailyHistoryDays;

    private final Map<String, UserProfile> inMemoryProfiles = new ConcurrentHashMap<>();

    public UserStatsService(
            Optional<FirestoreUserStore> firestoreUserStore,
            @Value("${stats.sync.max-seconds-delta:300}") int maxSecondsDelta,
            @Value("${stats.sync.max-score-delta:5000}") int maxScoreDelta,
            @Value("${stats.sync.max-stories-delta:50}") int maxStoriesDelta,
            @Value("${stats.sync.max-games-delta:50}") int maxGamesDelta,
            @Value("${stats.sync.max-daily-history-days:30}") int maxDailyHistoryDays) {
        this.firestoreUserStore = firestoreUserStore;
        this.maxSecondsDelta = maxSecondsDelta;
        this.maxScoreDelta = maxScoreDelta;
        this.maxStoriesDelta = maxStoriesDelta;
        this.maxGamesDelta = maxGamesDelta;
        this.maxDailyHistoryDays = maxDailyHistoryDays;
    }

    public UserProfile getOrCreateProfile(String uid, String email, String displayName) {
        if (firestoreUserStore.isPresent()) {
            try {
                return firestoreUserStore.get().getOrCreateProfile(uid, email, displayName);
            } catch (Exception e) {
                log.warn("[user-stats] Firestore getOrCreateProfile failed for uid={}, falling back to in-memory store: {}", uid, e.getMessage());
            }
        }
        return inMemoryProfiles.compute(uid, (k, existing) -> {
            if (existing == null) {
                return UserProfile.defaultFor(uid, email, displayName);
            }
            String resolvedEmail = resolveEmail(existing.email(), email);
            String resolvedName = resolveDisplayName(existing.displayName(), displayName);
            if (!resolvedEmail.equals(existing.email()) || !resolvedName.equals(existing.displayName())) {
                return new UserProfile(
                        existing.uid(),
                        resolvedEmail,
                        resolvedName,
                        existing.tier(),
                        existing.stats(),
                        existing.createdAt(),
                        System.currentTimeMillis()
                );
            }
            return existing;
        });
    }

    static String resolveEmail(String existingEmail, String incomingEmail) {
        boolean existingIsPlaceholder = existingEmail == null
                || existingEmail.isBlank()
                || "user@example.com".equals(existingEmail);
        boolean incomingIsReal = incomingEmail != null
                && !incomingEmail.isBlank()
                && !"user@example.com".equals(incomingEmail);
        if (incomingIsReal) {
            return incomingEmail;
        }
        if (!existingIsPlaceholder) {
            return existingEmail;
        }
        return incomingEmail != null ? incomingEmail : "";
    }

    static String resolveDisplayName(String existingName, String incomingName) {
        boolean existingIsPlaceholder = existingName == null
                || existingName.isBlank()
                || "Kid Explorer".equals(existingName);
        boolean incomingIsReal = incomingName != null
                && !incomingName.isBlank()
                && !"Kid Explorer".equals(incomingName);
        if (incomingIsReal) {
            return incomingName;
        }
        if (!existingIsPlaceholder) {
            return existingName;
        }
        return (incomingName != null && !incomingName.isBlank()) ? incomingName : "Kid Explorer";
    }

    public UserProfile syncStats(String uid, String email, String displayName, StatsSyncRequest rawRequest) {
        StatsSyncRequest sanitized = sanitize(rawRequest);

        if (firestoreUserStore.isPresent()) {
            try {
                return firestoreUserStore.get().syncStats(uid, email, displayName, sanitized, maxDailyHistoryDays);
            } catch (Exception e) {
                log.warn("[user-stats] Firestore syncStats failed for uid={}, falling back to in-memory store: {}", uid, e.getMessage());
            }
        }

        return inMemoryProfiles.compute(uid, (k, current) -> {
            UserProfile base = (current != null) ? current : UserProfile.defaultFor(uid, email, displayName);
            return applySyncToProfile(base, email, displayName, sanitized, maxDailyHistoryDays);
        });
    }

    static UserProfile applySyncToProfile(
            UserProfile base,
            String email,
            String displayName,
            StatsSyncRequest sanitized,
            int maxDailyHistoryDays) {
        UserStats currentStats = base.stats();
        String lastActiveDate = currentStats.lastActiveDate();
        String clientDate = sanitized.getClientDate();

        int storiesDelta = sanitized.getStoriesByContextDelta() != null
                ? sanitized.getStoriesByContextDelta().values().stream().mapToInt(Integer::intValue).sum()
                : 0;
        int gamesDelta = sanitized.getGamesCompletedDelta() != null
                ? sanitized.getGamesCompletedDelta().values().stream().mapToInt(Integer::intValue).sum()
                : 0;
        int modeTimeDelta = sanitized.getTimeByModeDelta() != null
                ? sanitized.getTimeByModeDelta().values().stream().mapToInt(Integer::intValue).sum()
                : 0;

        boolean hasActivity = sanitized.getActiveSecondsDelta() > 0
                || storiesDelta > 0
                || gamesDelta > 0
                || modeTimeDelta > 0
                || sanitized.getScoreDelta() > 0
                || sanitized.getSpeechListenDelta() > 0;

        int currentStreak = currentStats.currentStreakDays();
        int longestStreak = currentStats.longestStreakDays();
        String updatedLastActiveDate = lastActiveDate;

        if (hasActivity) {
            updatedLastActiveDate = clientDate;
            if (lastActiveDate == null || lastActiveDate.isBlank()) {
                currentStreak = 1;
                longestStreak = Math.max(longestStreak, 1);
            } else if (!clientDate.equals(lastActiveDate)) {
                try {
                    LocalDate last = LocalDate.parse(lastActiveDate);
                    LocalDate cur = LocalDate.parse(clientDate);
                    if (last.plusDays(1).equals(cur)) {
                        currentStreak++;
                        longestStreak = Math.max(longestStreak, currentStreak);
                    } else if (cur.isAfter(last.plusDays(1))) {
                        currentStreak = 1;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        int totalTime = currentStats.totalTimeSeconds() + sanitized.getActiveSecondsDelta();
        int totalScore = currentStats.totalScore() + sanitized.getScoreDelta();
        int speechListen = currentStats.speechListenCount() + sanitized.getSpeechListenDelta();
        int totalStories = currentStats.totalStoriesPracticed() + storiesDelta;

        Map<String, Integer> timeByMode = new HashMap<>(currentStats.timeByModeSeconds());
        if (sanitized.getTimeByModeDelta() != null) {
            sanitized.getTimeByModeDelta().forEach((mode, v) -> timeByMode.merge(mode, v, Integer::sum));
        }

        Map<String, Integer> gamesCompleted = new HashMap<>(currentStats.gamesCompletedByMode());
        if (sanitized.getGamesCompletedDelta() != null) {
            sanitized.getGamesCompletedDelta().forEach((mode, v) -> gamesCompleted.merge(mode, v, Integer::sum));
        }

        Map<String, Integer> storiesByCtx = new HashMap<>(currentStats.storiesByContext());
        if (sanitized.getStoriesByContextDelta() != null) {
            sanitized.getStoriesByContextDelta().forEach((ctx, v) -> storiesByCtx.merge(ctx, v, Integer::sum));
        }

        Map<String, Integer> dailyTime = new HashMap<>(currentStats.dailyTimeSeconds());
        if (sanitized.getActiveSecondsDelta() > 0) {
            dailyTime.merge(clientDate, sanitized.getActiveSecondsDelta(), Integer::sum);
        }

        Map<String, Integer> prunedDaily = pruneDailyTime(dailyTime, maxDailyHistoryDays);

        UserStats updatedStats = new UserStats(
                totalTime,
                currentStreak,
                longestStreak,
                updatedLastActiveDate,
                totalStories,
                totalScore,
                speechListen,
                timeByMode,
                gamesCompleted,
                storiesByCtx,
                prunedDaily
        );

        long now = System.currentTimeMillis();
        long createdAt = base.createdAt() > 0 ? base.createdAt() : now;
        return new UserProfile(
                base.uid(),
                resolveEmail(base.email(), email),
                resolveDisplayName(base.displayName(), displayName),
                base.tier(),
                updatedStats,
                createdAt,
                now
        );
    }

    public StatsSyncRequest sanitize(StatsSyncRequest raw) {
        StatsSyncRequest sanitized = new StatsSyncRequest();
        if (raw == null) {
            sanitized.setClientDate(LocalDate.now(ZoneOffset.UTC).toString());
            return sanitized;
        }

        sanitized.setActiveSecondsDelta(Math.max(0, Math.min(maxSecondsDelta, raw.getActiveSecondsDelta())));
        sanitized.setScoreDelta(Math.max(0, Math.min(maxScoreDelta, raw.getScoreDelta())));
        sanitized.setSpeechListenDelta(Math.max(0, Math.min(maxStoriesDelta * 5, raw.getSpeechListenDelta())));

        sanitized.setTimeByModeDelta(filterAndClamp(raw.getTimeByModeDelta(), ALLOWED_MODES, maxSecondsDelta));
        sanitized.setGamesCompletedDelta(filterAndClamp(raw.getGamesCompletedDelta(), ALLOWED_MODES, maxGamesDelta));
        sanitized.setStoriesByContextDelta(filterAndClamp(raw.getStoriesByContextDelta(), ALLOWED_CONTEXTS, maxStoriesDelta));

        sanitized.setClientDate(sanitizeClientDate(raw.getClientDate()));
        return sanitized;
    }

    private Map<String, Integer> filterAndClamp(Map<String, Integer> input, Set<String> allowlist, int maxVal) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, Integer> entry : input.entrySet()) {
            String key = entry.getKey();
            Integer val = entry.getValue();
            if (key != null && allowlist.contains(key) && val != null && val > 0) {
                result.put(key, Math.min(maxVal, val));
            }
        }
        return result;
    }

    private String sanitizeClientDate(String dateStr) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (dateStr == null || !DATE_PATTERN.matcher(dateStr.trim()).matches()) {
            return today.toString();
        }
        try {
            LocalDate parsed = LocalDate.parse(dateStr.trim());
            // Must be within ±1 day of UTC now
            if (parsed.isBefore(today.minusDays(1)) || parsed.isAfter(today.plusDays(1))) {
                return today.toString();
            }
            return parsed.toString();
        } catch (Exception e) {
            return today.toString();
        }
    }

    static Map<String, Integer> pruneDailyTime(Map<String, Integer> map, int maxDays) {
        if (map.size() <= maxDays) {
            return map;
        }
        TreeMap<String, Integer> sorted = new TreeMap<>(map);
        while (sorted.size() > maxDays) {
            sorted.pollFirstEntry();
        }
        return sorted;
    }
}
