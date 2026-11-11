package com.mesutuluag.portugeseforkidsbackend.user;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserStatsService userStatsService;
    private final LeaderboardService leaderboardService;

    public UserController(UserStatsService userStatsService) {
        this(userStatsService, new LeaderboardService(java.util.Optional.empty(), userStatsService));
    }

    public UserController(UserStatsService userStatsService, RateLimitService rateLimitService) {
        this(userStatsService, new LeaderboardService(java.util.Optional.empty(), userStatsService));
    }

    public UserController(
            UserStatsService userStatsService,
            RateLimitService rateLimitService,
            LeaderboardService leaderboardService) {
        this(userStatsService, leaderboardService);
    }

    @Autowired
    public UserController(
            UserStatsService userStatsService,
            LeaderboardService leaderboardService) {
        this.userStatsService = userStatsService;
        this.leaderboardService = leaderboardService;
    }

    public ResponseEntity<UserProfile> getProfile(Jwt jwt) {
        return getProfile(null, jwt);
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfile> getProfile(
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal Jwt jwt) {
        String uid = extractUid(jwt);
        String email = extractEmail(jwt);
        String displayName = extractDisplayName(jwt);

        UserProfile profile = userStatsService.getOrCreateProfile(uid, email, displayName);
        return ResponseEntity.ok(profile);
    }

    public ResponseEntity<UserProfile> syncStats(StatsSyncRequest request, Jwt jwt) {
        return syncStats(request, null, jwt);
    }

    @PostMapping("/stats/sync")
    public ResponseEntity<UserProfile> syncStats(
            @RequestBody(required = false) StatsSyncRequest request,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal Jwt jwt) {
        String uid = extractUid(jwt);
        String email = extractEmail(jwt);
        String displayName = extractDisplayName(jwt);

        UserProfile profile = userStatsService.syncStats(uid, email, displayName, request);
        return ResponseEntity.ok(profile);
    }

    @GetMapping("/leaderboard")
    public ResponseEntity<LeaderboardResponse> getLeaderboard(
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest httpRequest,
            @AuthenticationPrincipal Jwt jwt) {
        String uid = (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) ? jwt.getSubject() : null;
        String email = (jwt != null && jwt.hasClaim("email")) ? jwt.getClaimAsString("email") : null;
        String displayName = (jwt != null && jwt.hasClaim("name")) ? jwt.getClaimAsString("name") : null;

        LeaderboardResponse response = leaderboardService.getLeaderboard(uid, email, displayName, limit);
        return ResponseEntity.ok(response);
    }

    private String extractUid(Jwt jwt) {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        return "local-dev-user";
    }

    private String extractEmail(Jwt jwt) {
        if (jwt != null && jwt.hasClaim("email")) {
            return jwt.getClaimAsString("email");
        }
        return "user@example.com";
    }

    private String extractDisplayName(Jwt jwt) {
        if (jwt != null && jwt.hasClaim("name")) {
            return jwt.getClaimAsString("name");
        }
        return "Kid Explorer";
    }
}
