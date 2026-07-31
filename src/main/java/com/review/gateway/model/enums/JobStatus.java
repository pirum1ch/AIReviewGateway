package com.review.gateway.model.enums;

/**
 * Lifecycle status of a single {@link com.review.gateway.model.ReviewJob} — the per-chunk unit of
 * execution (V2, diff chunking). Distinct from {@link ReviewStatus}: a job can never be {@code NEW}
 * (every job is created already {@code QUEUED}) or {@code PUBLISHED} (publishing is a review-level,
 * not a job-level, concept).
 *
 * <p>Legal transitions: {@code QUEUED -> RUNNING | CANCELLED | OBSOLETE}; {@code RUNNING ->
 * COMPLETED | FAILED | QUEUED (retry) | CANCELLED | OBSOLETE}; everything else is terminal. Enforced
 * by {@code JobStateMachine}. The parent {@code reviews.status} is derived from the set of a
 * review's job statuses by {@code ChunkCoordinator}, then applied through the existing
 * {@code StateMachine}.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    OBSOLETE
}
