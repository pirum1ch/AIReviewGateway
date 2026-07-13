package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

/**
 * Outcome of {@code POST /jobs/{id}/result} (architecture §11 {@code SubmitResultResponse}, with the
 * idempotent-no-op distinction surfaced for logging/tests). {@code currentStatus} is {@code null} for
 * both {@code NOT_FOUND} and {@code OWNERSHIP_MISMATCH} — a caller that fails the ownership check must
 * get a response indistinguishable from "this job doesn't exist" (F02-05/SR-04): a worker-token holder
 * guessing a sequential {@code jobId} must not be able to learn another team's review status.
 */
public record SubmitResultOutcome(ResultOutcome outcome, ReviewStatus currentStatus) {

    public static SubmitResultOutcome notFound() {
        return new SubmitResultOutcome(ResultOutcome.NOT_FOUND, null);
    }

    public static SubmitResultOutcome ownershipMismatch() {
        return new SubmitResultOutcome(ResultOutcome.OWNERSHIP_MISMATCH, null);
    }

    public static SubmitResultOutcome idempotentNoop(ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.IDEMPOTENT_NOOP, currentStatus);
    }

    public static SubmitResultOutcome accepted(ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.ACCEPTED, currentStatus);
    }
}
