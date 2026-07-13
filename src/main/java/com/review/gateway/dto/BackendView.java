package com.review.gateway.dto;

import java.time.Instant;

/** {@code GET /backends} entry (architecture §11), ADMIN-only. */
public record BackendView(
        long id,
        String name,
        String model,
        int capacity,
        String status,
        int running,
        Instant lastSeen) {
}
