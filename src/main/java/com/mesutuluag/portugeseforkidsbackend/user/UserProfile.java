package com.mesutuluag.portugeseforkidsbackend.user;

public record UserProfile(
        String uid,
        String email,
        String displayName,
        String tier,
        UserStats stats,
        long createdAt,
        long updatedAt
) {
    public UserProfile {
        tier = (tier != null && !tier.isBlank()) ? tier : "FREE";
        stats = (stats != null) ? stats : UserStats.empty();
    }

    public static UserProfile defaultFor(String uid, String email, String displayName) {
        long now = System.currentTimeMillis();
        return new UserProfile(
                uid,
                email != null ? email : "",
                displayName != null ? displayName : "Kid Explorer",
                "FREE",
                UserStats.empty(),
                now,
                now
        );
    }
}
