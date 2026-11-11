package com.mesutuluag.portugeseforkidsbackend.exception;

public class UserStoreException extends RuntimeException {

    public UserStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserStoreException(String message) {
        super(message);
    }
}
