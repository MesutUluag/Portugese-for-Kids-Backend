package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private static final long CACHE_TTL_MS = 60_000L; // 60 seconds

    private final Optional<FirestoreUserStore> firestoreUserStore;
    private final UserStatsService userStatsService;

    private record CachedData(List<UserProfile> profiles, int totalCount, long timestamp) {}
    private volatile CachedData cachedData = null;

    public LeaderboardService(
            Optional<FirestoreUserStore> firestoreUserStore,
            UserStatsService userStatsService) {
        this.firestoreUserStore = firestoreUserStore;
        this.userStatsService = userStatsService;
    }

    public LeaderboardResponse getLeaderboard(String currentUid, String currentEmail, String currentName, int limit) {
        int boundedLimit = (limit > 0) ? Math.min(limit, 50) : 25;
        CachedData currentCache = getOrRefreshCache(boundedLimit);

        List<UserProfile> profiles = currentCache.profiles();
        int totalUsers = Math.max(profiles.size(), currentCache.totalCount());

        UserProfile currentUserProfile = null;
        if (currentUid != null && !currentUid.isBlank()) {
            currentUserProfile = userStatsService.getOrCreateProfile(currentUid, currentEmail, currentName);
        }

        List<LeaderboardEntry> topEntries = new ArrayList<>();
        LeaderboardEntry currentUserEntry = null;

        for (int i = 0; i < profiles.size(); i++) {
            UserProfile p = profiles.get(i);
            int rank = i + 1;
            boolean isSelf = (currentUserProfile != null && currentUserProfile.uid().equals(p.uid()));
            LeaderboardEntry entry = toEntry(p, rank, isSelf);
            topEntries.add(entry);

            if (isSelf) {
                currentUserEntry = entry;
            }
        }

        // If current user is not in the top N, construct their entry
        if (currentUserProfile != null && currentUserEntry == null) {
            int rank = calculateRank(currentUserProfile, profiles, totalUsers);
            currentUserEntry = toEntry(currentUserProfile, rank, true);
        }

        return new LeaderboardResponse(topEntries, currentUserEntry, totalUsers, currentCache.timestamp());
    }

    private synchronized CachedData getOrRefreshCache(int limit) {
        long now = System.currentTimeMillis();
        if (cachedData != null && (now - cachedData.timestamp()) < CACHE_TTL_MS) {
            return cachedData;
        }

        List<UserProfile> fetched = Collections.emptyList();
        int totalCount = 0;

        if (firestoreUserStore.isPresent()) {
            try {
                fetched = firestoreUserStore.get().getTopLeaderboard(limit);
                totalCount = firestoreUserStore.get().getTotalUsersCount();
            } catch (Exception e) {
                log.warn("[leaderboard] Failed to fetch leaderboard from Firestore: {}", e.getMessage());
            }
        }

        // Fallback to in-memory profiles if Firestore is empty or disabled
        if (fetched == null || fetched.isEmpty()) {
            Map<String, UserProfile> inMemory = userStatsService.getInMemoryProfiles();
            if (inMemory != null && !inMemory.isEmpty()) {
                fetched = inMemory.values().stream()
                        .sorted(Comparator.comparingInt((UserProfile p) -> p.stats().totalScore()).reversed())
                        .limit(limit)
                        .toList();
                totalCount = inMemory.size();
            } else {
                fetched = createMockLeaderboard();
                totalCount = fetched.size();
            }
        }

        cachedData = new CachedData(fetched, totalCount, now);
        return cachedData;
    }

    private int calculateRank(UserProfile user, List<UserProfile> topProfiles, int totalUsers) {
        int userScore = user.stats().totalScore();
        for (int i = 0; i < topProfiles.size(); i++) {
            if (userScore >= topProfiles.get(i).stats().totalScore()) {
                return i + 1;
            }
        }
        return Math.max(topProfiles.size() + 1, totalUsers);
    }

    private LeaderboardEntry toEntry(UserProfile profile, int rank, boolean isCurrentUser) {
        String displayName = sanitizeDisplayName(profile.displayName());
        String avatarInitial = !displayName.isBlank()
                ? displayName.substring(0, 1).toUpperCase()
                : "⭐";

        int gamesCompleted = profile.stats().gamesCompletedByMode() != null
                ? profile.stats().gamesCompletedByMode().values().stream().mapToInt(Integer::intValue).sum()
                : 0;

        return new LeaderboardEntry(
                rank,
                displayName,
                avatarInitial,
                profile.stats().totalScore(),
                profile.stats().scoreByMode(),
                gamesCompleted,
                isCurrentUser
        );
    }

    private String sanitizeDisplayName(String name) {
        if (name == null || name.isBlank() || "Kid Explorer".equals(name)) {
            return "Minik Gezgin";
        }
        // Protect child privacy: only return first name or sanitized display name
        String[] parts = name.trim().split("\\s+");
        if (parts.length > 1) {
            return parts[0] + " " + parts[1].charAt(0) + ".";
        }
        return parts[0];
    }

    private List<UserProfile> createMockLeaderboard() {
        return List.of(
                createMockUser("mock-1", "Sofia P.", 1850, Map.of("game1", 240, "game6", 450, "game8", 720)),
                createMockUser("mock-2", "Miguel R.", 1520, Map.of("game1", 310, "game5", 420, "game7", 350)),
                createMockUser("mock-3", "Tiago S.", 1240, Map.of("game2", 210, "game3", 320, "game8", 410)),
                createMockUser("mock-4", "Leonor M.", 980, Map.of("game1", 180, "game4", 250, "game6", 300)),
                createMockUser("mock-5", "Lucas C.", 760, Map.of("game2", 150, "game5", 280, "game7", 190))
        );
    }

    private UserProfile createMockUser(String uid, String name, int totalScore, Map<String, Integer> scoreByMode) {
        UserStats stats = new UserStats(
                totalScore * 4, 3, 5, "2026-11-11",
                totalScore / 10, totalScore, 10,
                Map.of(), Map.of(), scoreByMode, Map.of(), Map.of()
        );
        return new UserProfile(uid, "explorer@kids.com", name, "FREE", stats, System.currentTimeMillis() - 86400000L, System.currentTimeMillis());
    }
}
