package com.review.gateway.service.dto;

/**
 * Result classification for {@code QueueManager#submitResult}. {@code IDEMPOTENT_NOOP} is the
 * req. 1.9 "duplicate delivery" path: the job is no longer RUNNING, so the submission is acknowledged
 * without changing any state. {@code NOT_FOUND}/{@code OWNERSHIP_MISMATCH} mutate nothing (SR-04).
 */
public enum ResultOutcome {
    ACCEPTED,
    IDEMPOTENT_NOOP,
    NOT_FOUND,
    OWNERSHIP_MISMATCH
}
