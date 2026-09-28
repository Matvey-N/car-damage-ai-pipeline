package com.cardamage.exception;

/**
 * Thrown when a call to the Claude Vision API fails (network/HTTP error),
 * or when the model's response cannot be parsed/validated as a well-formed
 * DamageAssessment. This is the exception type that
 * {@code @Retryable(retryFor = ClaudeApiException.class)} in
 * DamageAnalysisService retries on; after the retry budget is exhausted,
 * the @Recover method converts this into a DamageAssessment.error(...)
 * response instead of propagating further.
 */
public class ClaudeApiException extends RuntimeException {

    public ClaudeApiException(String message) {
        super(message);
    }

    public ClaudeApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
