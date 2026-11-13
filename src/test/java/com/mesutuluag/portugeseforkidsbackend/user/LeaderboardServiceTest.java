package com.mesutuluag.portugeseforkidsbackend.user;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaderboardServiceTest {

    @Mock
    private FirestoreUserStore firestoreUserStore;

    @Mock
    private UserStatsService userStatsService;

    private LeaderboardService leaderboardService;

    @BeforeEach
    void setUp() {
        leaderboardService = new LeaderboardService(Optional.of(firestoreUserStore), userStatsService);
    }

    @Test
    void getLeaderboard_withNoDataInFirestoreOrMemory_returnsEmptyLeaderboardNoMockData() {
        when(firestoreUserStore.getTopLeaderboard(25)).thenReturn(Collections.emptyList());
        when(firestoreUserStore.getTotalUsersCount()).thenReturn(0);
        when(userStatsService.getInMemoryProfiles()).thenReturn(Collections.emptyMap());

        LeaderboardResponse response = leaderboardService.getLeaderboard(null, null, null, 25);

        assertThat(response.topUsers()).isEmpty();
        assertThat(response.currentUser()).isNull();
        assertThat(response.totalUsersCount()).isEqualTo(0);
    }

    @Test
    void getLeaderboard_withFirestoreUsers_returnsRankedAndSanitizedNames() {
        UserProfile p1 = createUser("uid-1", "Carlos Ferreira", 500, Map.of("game1", 200, "game8", 300));
        UserProfile p2 = createUser("uid-2", "Ana", 300, Map.of("game2", 300));
        UserProfile p3 = createUser("uid-3", "", 100, Map.of());

        when(firestoreUserStore.getTopLeaderboard(25)).thenReturn(List.of(p1, p2, p3));
        when(firestoreUserStore.getTotalUsersCount()).thenReturn(3);

        LeaderboardResponse response = leaderboardService.getLeaderboard(null, null, null, 25);

        assertThat(response.topUsers()).hasSize(3);
        assertThat(response.totalUsersCount()).isEqualTo(3);

        LeaderboardEntry e1 = response.topUsers().get(0);
        assertThat(e1.rank()).isEqualTo(1);
        assertThat(e1.displayName()).isEqualTo("Carlos F.");
        assertThat(e1.avatarInitial()).isEqualTo("C");
        assertThat(e1.totalScore()).isEqualTo(500);

        LeaderboardEntry e2 = response.topUsers().get(1);
        assertThat(e2.rank()).isEqualTo(2);
        assertThat(e2.displayName()).isEqualTo("Ana");
        assertThat(e2.avatarInitial()).isEqualTo("A");

        LeaderboardEntry e3 = response.topUsers().get(2);
        assertThat(e3.rank()).isEqualTo(3);
        assertThat(e3.displayName()).isEqualTo("Explorer");
    }

    @Test
    void getLeaderboard_withCurrentUserNotInTop_calculatesRank() {
        UserProfile p1 = createUser("uid-1", "Top Player", 1000, Map.of("game1", 1000));
        when(firestoreUserStore.getTopLeaderboard(1)).thenReturn(List.of(p1));
        when(firestoreUserStore.getTotalUsersCount()).thenReturn(10);

        UserProfile current = createUser("uid-current", "Current Kid", 450, Map.of("game3", 450));
        when(userStatsService.getOrCreateProfile("uid-current", "kid@test.com", "Current Kid")).thenReturn(current);

        LeaderboardResponse response = leaderboardService.getLeaderboard("uid-current", "kid@test.com", "Current Kid", 1);

        assertThat(response.topUsers()).hasSize(1);
        assertThat(response.currentUser()).isNotNull();
        assertThat(response.currentUser().isCurrentUser()).isTrue();
        assertThat(response.currentUser().displayName()).isEqualTo("Current K.");
        assertThat(response.currentUser().rank()).isEqualTo(10);
    }

    @Test
    void getLeaderboard_withCurrentUserInTop_marksEntry() {
        UserProfile current = createUser("uid-self", "Self Explorer", 800, Map.of("game1", 800));
        when(firestoreUserStore.getTopLeaderboard(25)).thenReturn(List.of(current));
        when(firestoreUserStore.getTotalUsersCount()).thenReturn(1);
        when(userStatsService.getOrCreateProfile("uid-self", "self@test.com", "Self Explorer")).thenReturn(current);

        LeaderboardResponse response = leaderboardService.getLeaderboard("uid-self", "self@test.com", "Self Explorer", 25);

        assertThat(response.topUsers()).hasSize(1);
        assertThat(response.topUsers().get(0).isCurrentUser()).isTrue();
        assertThat(response.currentUser()).isNotNull();
        assertThat(response.currentUser().isCurrentUser()).isTrue();
        assertThat(response.currentUser().rank()).isEqualTo(1);
    }

    @Test
    void getLeaderboard_inMemoryFallbackWhenFirestoreEmpty() {
        LeaderboardService serviceNoFirestore = new LeaderboardService(Optional.empty(), userStatsService);

        Map<String, UserProfile> inMemory = new HashMap<>();
        inMemory.put("u1", createUser("u1", "Mem One", 200, Map.of()));
        inMemory.put("u2", createUser("u2", "Mem Two", 600, Map.of()));
        when(userStatsService.getInMemoryProfiles()).thenReturn(inMemory);

        LeaderboardResponse response = serviceNoFirestore.getLeaderboard(null, null, null, 25);

        assertThat(response.topUsers()).hasSize(2);
        assertThat(response.topUsers().get(0).displayName()).isEqualTo("Mem T.");
        assertThat(response.topUsers().get(0).totalScore()).isEqualTo(600);
        assertThat(response.topUsers().get(1).displayName()).isEqualTo("Mem O.");
        assertThat(response.topUsers().get(1).totalScore()).isEqualTo(200);
    }

    @Test
    void evictCache_forcesFreshFirestoreFetchAndPreservesTurkishNicknames() {
        UserProfile trUser = createUser("uid-tr", "ŞanslıKaplumbağa", 420, Map.of());
        when(firestoreUserStore.getTopLeaderboard(25)).thenReturn(List.of(trUser));
        when(firestoreUserStore.getTotalUsersCount()).thenReturn(1);

        LeaderboardResponse first = leaderboardService.getLeaderboard(null, null, null, 25);
        assertThat(first.topUsers().get(0).displayName()).isEqualTo("ŞanslıKaplumbağa");
        assertThat(first.topUsers().get(0).avatarInitial()).isEqualTo("Ş");

        leaderboardService.evictCache();
        leaderboardService.getLeaderboard(null, null, null, 25);
        verify(firestoreUserStore, times(2)).getTopLeaderboard(25);
    }

    private UserProfile createUser(String uid, String name, int score, Map<String, Integer> scoreByMode) {
        UserStats stats = new UserStats(
                300, 2, 4, "2026-11-11",
                5, score, 3,
                Map.of(), Map.of(), scoreByMode, Map.of(), Map.of()
        );
        return new UserProfile(uid, uid + "@test.com", name, "FREE", stats, 1000L, 2000L);
    }
}
