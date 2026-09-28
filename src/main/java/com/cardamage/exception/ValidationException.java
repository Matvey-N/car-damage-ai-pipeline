package com.cardamage.exception;

/**
 * Thrown when user input (uploaded images, request parameters) fails
 * validation - wrong count, wrong format, file too large, etc.
 * Distinct from a failed/invalid model response, see ClaudeApiException.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
