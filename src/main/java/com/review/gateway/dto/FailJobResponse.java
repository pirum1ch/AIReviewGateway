package com.review.gateway.dto;

/**
 * {@code POST /jobs/{id}/fail} {@code 200} response body (architecture §5.2). Deliberately identical
 * ({@code accepted: true}) whether the report was applied or was an idempotent no-op, and deliberately
 * carries no {@code reviewId}/status — a stricter contract than {@code /result}, since this endpoint has
 * no reason to ever echo Review state to the caller (WOC-28).
 */
public record FailJobResponse(boolean accepted) {
}
