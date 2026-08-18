package com.review.gateway.dto;

import java.time.Instant;

/**
 * {@code GET /backends} entry (architecture §11), ADMIN-only. {@code probeFailedSince} (WOR-12,
 * {@code null} when the backend is not currently failing) — see {@code BackendSnapshot}'s javadoc.
 */
public record BackendView(
        long id,
        String name,
        String model,
        int capacity,
        String status,
        int running,
        Instant lastSeen,
        Instant probeFailedSince) {
}
