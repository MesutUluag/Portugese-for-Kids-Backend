package com.mesutuluag.portugeseforkidsbackend.exception;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(DailyLimitExceededException.class)
	public ResponseEntity<Map<String, Object>> handleDailyLimitExceeded(DailyLimitExceededException ex) {
		long secondsUntilMidnight = Duration.between(
				Instant.now(),
				LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
		).getSeconds();

		long retryAfter = Math.max(1, secondsUntilMidnight);
		log.warn("[rate-limit] Daily limit exceeded. Retry-After={}s", retryAfter);

		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
				.body(Map.of(
						"error", "daily_limit_exceeded",
						"message", "Daily request limit exceeded. Please try again tomorrow."
				));
	}

	@ExceptionHandler(StoryGenerationException.class)
	public ResponseEntity<Map<String, Object>> handleStoryGenerationException(StoryGenerationException ex) {
		log.error("[story-error] Story generation failed: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of(
						"error", "story_generation_failed",
						"message", "Failed to generate story. Please try again later."
				));
	}

	@ExceptionHandler(UserStoreException.class)
	public ResponseEntity<Map<String, Object>> handleUserStoreException(UserStoreException ex) {
		log.error("[user-store-error] User store operation failed: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of(
						"error", "user_store_unavailable",
						"message", "User profile service is temporarily unavailable. Please try again later."
				));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
		log.warn("[bad-request] Invalid request: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of(
						"error", "invalid_request",
						"message", ex.getMessage() != null ? ex.getMessage() : "Invalid request"
				));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
		String msg = ex.getBindingResult().getAllErrors().isEmpty()
				? "Validation failed"
				: ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
		log.warn("[bad-request] Validation error: {}", msg);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of(
						"error", "validation_failed",
						"message", msg != null ? msg : "Validation failed"
				));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception ex) {
		log.warn("[bad-request] Malformed request payload or parameter: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of(
						"error", "invalid_request",
						"message", "Malformed request payload or query parameter."
				));
	}
}
