package com.cardamage.core.pipeline;

/** The model call failed (network, HTTP status, empty or truncated answer). */
public class ModelCallException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ModelCallException(String message) { super(message); }
    public ModelCallException(String message, Throwable cause) { super(message, cause); }
}
