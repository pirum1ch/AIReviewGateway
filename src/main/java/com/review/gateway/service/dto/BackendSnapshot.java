package com.review.gateway.service.dto;

import com.review.gateway.model.enums.BackendStatus;

import java.time.Instant;

/**
 * Read model backing {@code GET /backends} (architecture §11 {@code BackendView}). {@code running} is
 * derived from the count of currently-{@code RUNNING} jobs on this backend (req. 1.6 — no separate
 * counter is maintained).
 *
 * <p>{@code probeFailedSince} (WOC-19/WOR-12, Worker Observability &amp; Claim Latency, promoted from
 * SHOULD to MUST by the threat model): lets an ADMIN operator see "failing for 2 of 3 minutes' grace,
 * deferred because at capacity" without reading the DB directly — the compensating visibility control
 * for a WOC-13 deferral an untrusted Worker can prolong (self-declared {@code backendId}).
 */
public record BackendSnapshot(
        Long id,
        String name,
        String model,
        int capacity,
        BackendStatus status,
        long running,
        Instant lastSeen,
        Instant probeFailedSince) {
}
