package com.mesutuluag.portugeseforkidsbackend.image;

import java.util.Map;

import com.mesutuluag.portugeseforkidsbackend.commons.RateLimitService;
import com.mesutuluag.portugeseforkidsbackend.exception.DailyLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageControllerTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private ImageService imageService;

    private ImageController controller;

    @BeforeEach
    void setUp() {
        controller = new ImageController(rateLimitService, imageService);
    }

    @Test
    void generateImage_success_returnsJpeg() throws Exception {
        byte[] jpegBytes = new byte[]{1, 2, 3};
        when(imageService.generateJpeg("classroom scene", 768, 368)).thenReturn(jpegBytes);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195");

        ResponseEntity<?> response = controller.generateImage("classroom scene", 768, 368, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getBody()).isEqualTo(jpegBytes);
        verify(rateLimitService).checkAndIncrement(request, null);
    }

    @Test
    void generateImage_withAuthenticatedUser_passesUserIdToRateLimiter() throws Exception {
        byte[] jpegBytes = new byte[]{1, 2, 3};
        when(imageService.generateJpeg("classroom scene", 768, 368)).thenReturn(jpegBytes);

        MockHttpServletRequest request = new MockHttpServletRequest();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("test-user-456");

        ResponseEntity<?> response = controller.generateImage("classroom scene", 768, 368, jwt, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(rateLimitService).checkAndIncrement(request, "test-user-456");
    }

    @Test
    void generateImage_allProvidersFail_returns503Json() throws Exception {
        when(imageService.generateJpeg("unknown scene", 768, 368)).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");

        ResponseEntity<?> response = controller.generateImage("unknown scene", 768, 368, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        verify(rateLimitService).checkAndIncrement(request, null);
    }

    @Test
    void generateImage_rateLimitExceeded_throwsDailyLimitExceededException() {
        doThrow(new DailyLimitExceededException())
                .when(rateLimitService).checkAndIncrement(any(HttpServletRequest.class), any());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.195");

        assertThatThrownBy(() -> controller.generateImage("prompt", 768, 368, request))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    void generateImage_blankPrompt_throwsIllegalArgumentException() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.generateImage("   ", 768, 368, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagePrompt must not be blank");

        assertThatThrownBy(() -> controller.generateImage(null, 768, 368, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagePrompt must not be blank");
    }

    @Test
    void generateImage_promptTooLong_throwsIllegalArgumentException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String longPrompt = "a".repeat(501);

        assertThatThrownBy(() -> controller.generateImage(longPrompt, 768, 368, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagePrompt must not exceed 500 characters");
    }

    @Test
    void generateImage_clampsDimensions_toMinAndMax() throws Exception {
        byte[] jpegBytes = new byte[]{9};
        when(imageService.generateJpeg(eq("prompt"), eq(64), eq(1024))).thenReturn(jpegBytes);

        MockHttpServletRequest request = new MockHttpServletRequest();
        ResponseEntity<?> response = controller.generateImage("prompt", 10, 2048, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(imageService).generateJpeg("prompt", 64, 1024);
    }
}
