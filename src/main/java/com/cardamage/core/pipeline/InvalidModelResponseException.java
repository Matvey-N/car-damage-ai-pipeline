package com.cardamage.core.pipeline;

/** The model answered, but the answer is not parseable JSON of the expected shape. */
public class InvalidModelResponseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public InvalidModelResponseException(String message) { super(message); }
    public InvalidModelResponseException(String message, Throwable cause) { super(message, cause); }
}
