package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

/**
 * Outcome of {@code POST /jobs/{id}/result} (architecture §11 {@code SubmitResultResponse}, with the
 * idempotent-no-op distinction surfaced for logging/tests). {@code currentStatus} is {@code null}
 * only when {@code outcome} is {@code NOT_FOUND}.
 */
public record SubmitResultOutcome(ResultOutcome outcome, ReviewStatus currentStatus) {

    public static SubmitResultOutcome notFound() {
        return new SubmitResultOutcome(ResultOutcome.NOT_FOUND, null);
    }

    public static SubmitResultOutcome ownershipMismatch(ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.OWNERSHIP_MISMATCH, currentStatus);
    }

    public static SubmitResultOutcome idempotentNoop(ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.IDEMPOTENT_NOOP, currentStatus);
    }

    public static SubmitResultOutcome accepted(ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.ACCEPTED, currentStatus);
    }
}
