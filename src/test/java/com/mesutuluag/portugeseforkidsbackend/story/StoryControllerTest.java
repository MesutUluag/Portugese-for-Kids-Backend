package com.mesutuluag.portugeseforkidsbackend.story;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoryControllerTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private StoryService storyService;

    private StoryController controller;

    @BeforeEach
    void setUp() {
        controller = new StoryController(rateLimitService, storyService);
    }

    @Test
    void createStory_happyPath_resolvesIpAndCallsService() throws Exception {
        StoryResponse expectedResponse = new StoryResponse("{\"pt\":\"Bom dia!\"}");
        when(storyService.generateStory(any(StoryRequest.class))).thenReturn(expectedResponse);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.22");

        StoryRequest storyRequest = new StoryRequest();
        storyRequest.setPrompt("greet");

        StoryResponse response = controller.createStory(storyRequest, request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isEqualTo("{\"pt\":\"Bom dia!\"}");
        verify(rateLimitService).checkAndIncrement(request, null);
        verify(storyService).generateStory(storyRequest);
    }

    @Test
    void createStory_withAuthenticatedUser_passesUserIdToRateLimiter() {
        StoryResponse expectedResponse = new StoryResponse("{\"pt\":\"Bom dia!\"}");
        when(storyService.generateStory(any(StoryRequest.class))).thenReturn(expectedResponse);

        MockHttpServletRequest request = new MockHttpServletRequest();
        StoryRequest storyRequest = new StoryRequest();
        storyRequest.setPrompt("greet");

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("test-user-123");

        StoryResponse response = controller.createStory(storyRequest, jwt, request);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isEqualTo("{\"pt\":\"Bom dia!\"}");
        verify(rateLimitService).checkAndIncrement(request, "test-user-123");
        verify(storyService).generateStory(storyRequest);
    }

    @Test
    void createStory_rateLimitExceeded_throwsDailyLimitExceededException() {
        doThrow(new DailyLimitExceededException())
                .when(rateLimitService).checkAndIncrement(any(HttpServletRequest.class), any());

        StoryRequest storyRequest = new StoryRequest();
        storyRequest.setPrompt("greet");

        assertThatThrownBy(() -> controller.createStory(storyRequest, new MockHttpServletRequest()))
                .isInstanceOf(DailyLimitExceededException.class);
    }
}
