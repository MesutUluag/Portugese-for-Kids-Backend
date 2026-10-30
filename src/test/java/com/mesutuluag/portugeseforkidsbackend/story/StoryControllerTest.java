package com.mesutuluag.portugeseforkidsbackend.story;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
        verify(rateLimitService).checkAndIncrement("198.51.100.22");
        verify(storyService).generateStory(storyRequest);
    }

    @Test
    void createStory_rateLimitExceeded_throwsDailyLimitExceededException() {
        doThrow(new DailyLimitExceededException())
                .when(rateLimitService).checkAndIncrement(anyString());

        StoryRequest storyRequest = new StoryRequest();
        storyRequest.setPrompt("greet");

        assertThatThrownBy(() -> controller.createStory(storyRequest, new MockHttpServletRequest()))
                .isInstanceOf(DailyLimitExceededException.class);
    }
}
