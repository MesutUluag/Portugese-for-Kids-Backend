package com.mesutuluag.portugeseforkidsbackend.exception;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleDailyLimitExceeded_returns429WithRetryAfterHeader() {
        ResponseEntity<Map<String, Object>> response = handler.handleDailyLimitExceeded(new DailyLimitExceededException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(response.getBody()).containsEntry("error", "daily_limit_exceeded");
    }

    @Test
    void handleIllegalArgument_returns400WithErrorMessage() {
        ResponseEntity<Map<String, Object>> response = handler.handleIllegalArgument(new IllegalArgumentException("Invalid param"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_request");
        assertThat(response.getBody()).containsEntry("message", "Invalid param");
    }

    @Test
    void handleStoryGenerationException_returns502() {
        ResponseEntity<Map<String, Object>> response = handler.handleStoryGenerationException(new StoryGenerationException("LLM unavailable"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry("error", "story_generation_failed");
    }

    @Test
    void handleMalformedRequest_returns400() {
        ResponseEntity<Map<String, Object>> response = handler.handleMalformedRequest(new RuntimeException("JSON parse error"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_request");
    }
}
