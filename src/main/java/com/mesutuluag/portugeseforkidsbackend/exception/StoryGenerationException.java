package com.mesutuluag.portugeseforkidsbackend.exception;

public class StoryGenerationException extends RuntimeException {

    public StoryGenerationException(String message) {
        super(message);
    }

    public StoryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
