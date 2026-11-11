package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Collections;
import java.util.Map;

public record UserStats(
        int totalTimeSeconds,
        int currentStreakDays,
        int longestStreakDays,
        String lastActiveDate,
        int totalStoriesPracticed,
        int totalScore,
        int speechListenCount,
        Map<String, Integer> timeByModeSeconds,
        Map<String, Integer> gamesCompletedByMode,
        Map<String, Integer> storiesByContext,
        Map<String, Integer> dailyTimeSeconds
) {
    public UserStats {
        timeByModeSeconds = timeByModeSeconds != null ? Map.copyOf(timeByModeSeconds) : Collections.emptyMap();
        gamesCompletedByMode = gamesCompletedByMode != null ? Map.copyOf(gamesCompletedByMode) : Collections.emptyMap();
        storiesByContext = storiesByContext != null ? Map.copyOf(storiesByContext) : Collections.emptyMap();
        dailyTimeSeconds = dailyTimeSeconds != null ? Map.copyOf(dailyTimeSeconds) : Collections.emptyMap();
    }

    public static UserStats empty() {
        return new UserStats(0, 0, 0, null, 0, 0, 0,
                Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap());
    }
}
