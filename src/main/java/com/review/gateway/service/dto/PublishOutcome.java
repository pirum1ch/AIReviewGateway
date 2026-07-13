package com.review.gateway.service.dto;

/**
 * Outcome of {@code GitLabPublisher#publishReview}.
 */
public enum PublishOutcome {
    /** Review was not eligible for publishing (not COMPLETED — e.g. already PUBLISHED, OBSOLETE, CANCELLED, or still in progress). */
    NOT_APPLICABLE,
    /** Every unpublished comment (possibly zero) was published; Review transitioned to PUBLISHED. */
    PUBLISHED,
    /** At least one comment failed to publish (transient GitLab error); Review stays COMPLETED for retry. */
    PARTIAL
}
