package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Map;

public record LeaderboardEntry(
        int rank,
        String displayName,
        String avatarInitial,
        int totalScore,
        Map<String, Integer> scoreByMode,
        int gamesCompleted,
        boolean isCurrentUser
) {}
