package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.List;

public record LeaderboardResponse(
        List<LeaderboardEntry> topUsers,
        LeaderboardEntry currentUser,
        int totalUsersCount,
        long lastUpdated
) {}
