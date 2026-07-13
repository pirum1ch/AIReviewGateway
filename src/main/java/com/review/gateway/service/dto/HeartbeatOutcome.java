package com.review.gateway.service.dto;

/**
 * Result classification for {@code QueueManager#heartbeat} (SR-04). A future {@code JobController}
 * maps {@code NOT_FOUND}/{@code OWNERSHIP_MISMATCH} to 404/403 without mutating any state; only
 * {@code ACCEPTED} touches the database.
 */
public enum HeartbeatOutcome {
    ACCEPTED,
    NOT_FOUND,
    OWNERSHIP_MISMATCH
}
