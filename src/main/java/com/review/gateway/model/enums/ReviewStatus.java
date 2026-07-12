package com.review.gateway.model.enums;

/**
 * Lifecycle status of a {@link com.review.gateway.model.Review}. {@code reviews.status} is the
 * single source of truth for Review state; every transition is validated by {@code StateMachine}
 * (feature/02-core-services) and mirrored in {@code review_events}.
 *
 * <p>Terminal states: {@link #PUBLISHED}, {@link #FAILED}, {@link #CANCELLED}, {@link #OBSOLETE}.
 */
public enum ReviewStatus {
    NEW,
    QUEUED,
    RUNNING,
    COMPLETED,
    PUBLISHED,
    FAILED,
    CANCELLED,
    OBSOLETE
}
