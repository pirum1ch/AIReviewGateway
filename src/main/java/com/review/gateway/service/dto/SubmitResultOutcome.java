package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

/**
 * Outcome of {@code POST /jobs/{id}/result} (architecture §11 {@code SubmitResultResponse}, with the
 * idempotent-no-op distinction surfaced for logging/tests). Both {@code reviewId} and
 * {@code currentStatus} are {@code null} for {@code NOT_FOUND} and {@code OWNERSHIP_MISMATCH} — a
 * caller that fails the ownership check must get a response indistinguishable from "this job doesn't
 * exist" (F02-05/SR-04): a worker-token holder guessing a sequential {@code jobId} must not be able to
 * learn another team's review id or status.
 *
 * <p>{@code reviewId} (feature/03-api-security addition): the architecture's own
 * {@code SubmitResultResponse(long reviewId, String status)} contract requires the review id in the
 * response body, but {@code JobController} only ever receives the {@code jobId} path variable — this
 * field is what lets the controller render that response without querying the repository layer
 * directly (which would violate the controller→service→repository layering).
 */
public record SubmitResultOutcome(ResultOutcome outcome, Long reviewId, ReviewStatus currentStatus) {

    public static SubmitResultOutcome notFound() {
        return new SubmitResultOutcome(ResultOutcome.NOT_FOUND, null, null);
    }

    public static SubmitResultOutcome ownershipMismatch() {
        return new SubmitResultOutcome(ResultOutcome.OWNERSHIP_MISMATCH, null, null);
    }

    public static SubmitResultOutcome idempotentNoop(Long reviewId, ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.IDEMPOTENT_NOOP, reviewId, currentStatus);
    }

    public static SubmitResultOutcome accepted(Long reviewId, ReviewStatus currentStatus) {
        return new SubmitResultOutcome(ResultOutcome.ACCEPTED, reviewId, currentStatus);
    }
}
