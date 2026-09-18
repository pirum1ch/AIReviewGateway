package com.review.gateway.dto;

/**
 * {@code POST /backends/announce} response body (architecture §2.2). {@code created} is {@code true}
 * only for a fresh INSERT; {@code status} is the row's <em>effective</em> status — if it is {@code
 * MAINTENANCE}/{@code OFFLINE}, announce still succeeds and returns that status (BSR-02), it just never
 * resurrects a parked backend. No {@code url} field (T-17 minimization, BSQ-17).
 */
public record AnnounceBackendResponse(String name, String status, boolean created) {
}
