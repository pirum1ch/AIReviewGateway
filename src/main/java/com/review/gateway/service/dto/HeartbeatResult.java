package com.review.gateway.service.dto;

/**
 * Outcome of {@code POST /jobs/{id}/heartbeat} (architecture §11 {@code HeartbeatResponse}).
 * {@code shouldContinue == false} tells the Worker to stop generating (review went
 * OBSOLETE/CANCELLED, or is no longer RUNNING for any other reason) — req. 1.7.
 */
public record HeartbeatResult(HeartbeatOutcome outcome, boolean shouldContinue) {

    public static HeartbeatResult notFound() {
        return new HeartbeatResult(HeartbeatOutcome.NOT_FOUND, false);
    }

    public static HeartbeatResult ownershipMismatch() {
        return new HeartbeatResult(HeartbeatOutcome.OWNERSHIP_MISMATCH, false);
    }

    public static HeartbeatResult accepted(boolean shouldContinue) {
        return new HeartbeatResult(HeartbeatOutcome.ACCEPTED, shouldContinue);
    }
}
