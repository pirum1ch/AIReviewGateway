package com.review.gateway.dto;

/**
 * Uniform error body (architecture §11) returned by {@code GlobalExceptionHandler}. {@code error} is a
 * short machine-readable code (e.g. {@code "DIFF_TOO_LARGE"}, {@code "NOT_FOUND"}); {@code message} is
 * a human-readable summary. Never carries a stack trace or internal exception detail (SR-17).
 */
public record ErrorResponse(String error, String message) {
}
