package com.review.gateway.service.dto;

import com.review.gateway.model.enums.BackendStatus;

import java.time.Instant;

/**
 * Read model backing {@code GET /backends} (architecture §11 {@code BackendView}). {@code running} is
 * derived from the count of currently-{@code RUNNING} jobs on this backend (req. 1.6 — no separate
 * counter is maintained).
 */
public record BackendSnapshot(
        Long id,
        String name,
        String model,
        int capacity,
        BackendStatus status,
        long running,
        Instant lastSeen) {
}
