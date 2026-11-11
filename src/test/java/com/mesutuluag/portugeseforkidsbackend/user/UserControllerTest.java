package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Map;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserStatsService userStatsService;

    private RateLimitService rateLimitService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitService(250, 30, 500, true);
        controller = new UserController(userStatsService, rateLimitService);
    }

    @Test
    void getProfile_withJwt_extractsClaimsAndCallsService() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("uid-999");
        when(jwt.hasClaim("email")).thenReturn(true);
        when(jwt.getClaimAsString("email")).thenReturn("kid@test.com");
        when(jwt.hasClaim("name")).thenReturn(true);
        when(jwt.getClaimAsString("name")).thenReturn("Kid Name");

        UserProfile profile = UserProfile.defaultFor("uid-999", "kid@test.com", "Kid Name");
        when(userStatsService.getOrCreateProfile("uid-999", "kid@test.com", "Kid Name")).thenReturn(profile);

        ResponseEntity<UserProfile> response = controller.getProfile(jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().uid()).isEqualTo("uid-999");
        verify(userStatsService).getOrCreateProfile("uid-999", "kid@test.com", "Kid Name");
    }

    @Test
    void getProfile_withoutJwt_fallsBackToLocalDevUser() {
        UserProfile fallback = UserProfile.defaultFor("local-dev-user", "user@example.com", "Kid Explorer");
        when(userStatsService.getOrCreateProfile("local-dev-user", "user@example.com", "Kid Explorer")).thenReturn(fallback);

        ResponseEntity<UserProfile> response = controller.getProfile(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().uid()).isEqualTo("local-dev-user");
    }

    @Test
    void syncStats_withJwtAndRequest_delegatesToService() {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("uid-888");
        when(jwt.hasClaim("email")).thenReturn(false);
        when(jwt.hasClaim("name")).thenReturn(false);

        StatsSyncRequest req = new StatsSyncRequest();
        req.setActiveSecondsDelta(45);

        UserProfile profile = UserProfile.defaultFor("uid-888", "user@example.com", "Kid Explorer");
        when(userStatsService.syncStats(eq("uid-888"), eq("user@example.com"), eq("Kid Explorer"), any(StatsSyncRequest.class)))
                .thenReturn(profile);

        ResponseEntity<UserProfile> response = controller.syncStats(req, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        verify(userStatsService).syncStats(eq("uid-888"), eq("user@example.com"), eq("Kid Explorer"), eq(req));
    }

    @Test
    void syncStats_exceedingUserSyncLimit_throwsDailyLimitExceededException() {
        RateLimitService strictRateLimit = new RateLimitService(250, 30, 2, true);
        UserController strictController = new UserController(userStatsService, strictRateLimit);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("uid-spam");
        when(jwt.hasClaim("email")).thenReturn(false);
        when(jwt.hasClaim("name")).thenReturn(false);

        UserProfile profile = UserProfile.defaultFor("uid-spam", "user@example.com", "Kid Explorer");
        when(userStatsService.syncStats(eq("uid-spam"), any(), any(), any())).thenReturn(profile);

        strictController.syncStats(new StatsSyncRequest(), jwt);
        strictController.syncStats(new StatsSyncRequest(), jwt);

        assertThatThrownBy(() -> strictController.syncStats(new StatsSyncRequest(), jwt))
                .isInstanceOf(DailyLimitExceededException.class);
    }
}
