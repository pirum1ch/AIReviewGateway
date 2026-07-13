package com.review.gateway.exception;

/**
 * Thrown by a {@code BackendProber} implementation when a health probe against a registered
 * llama-server backend fails or times out. {@code BackendHealthChecker} catches this and flips the
 * backend {@code ACTIVE -> SUSPECT} (req. 1.6).
 */
public class BackendUnavailableException extends RuntimeException {

    public BackendUnavailableException(String message) {
        super(message);
    }

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
