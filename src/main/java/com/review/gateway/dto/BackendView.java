package com.review.gateway.dto;

import java.time.Instant;

/**
 * {@code GET /backends} entry (architecture §11), ADMIN-only. {@code probeFailedSince} (WOR-12,
 * {@code null} when the backend is not currently failing) — see {@code BackendSnapshot}'s javadoc.
 * {@code announcedBy} (Backend Self-Registration, BSR-08): the {@code workerId} that currently holds
 * self-registration of this row, or {@code null} if unclaimed — an operator's answer to "which Worker
 * owns this row / why am I getting BACKEND_NAME_TAKEN". Also returned (built from the persisted entity,
 * never the request body — BSQ-17) by {@code POST /backends} and {@code DELETE /backends/{name}}. No
 * {@code url} field — T-17 minimization is unchanged by this feature.
 */
public record BackendView(
        long id,
        String name,
        String model,
        int capacity,
        String status,
        int running,
        Instant lastSeen,
        Instant probeFailedSince,
        String announcedBy) {
}
