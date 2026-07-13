package com.review.gateway.exception;

/**
 * Thrown by {@code DiffSizeValidator} when a submitted diff exceeds the configured LLM context
 * budget. Maps to {@code HTTP 422 DIFF_TOO_LARGE} at the controller layer (feature/03-api-security).
 * Raised at the edge of {@code POST /reviews}, before any persistence happens (fail-fast principle).
 */
public class DiffTooLargeException extends RuntimeException {

    public DiffTooLargeException(String message) {
        super(message);
    }
}
