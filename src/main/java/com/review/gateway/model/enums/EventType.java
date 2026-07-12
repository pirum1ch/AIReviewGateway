package com.review.gateway.model.enums;

/**
 * Type of an audit event appended to the append-only {@code review_events} table. Every state
 * transition of a {@link com.review.gateway.model.Review} produces exactly one such event.
 */
public enum EventType {
    CREATED,
    CLAIMED,
    RUNNING,
    HEARTBEAT,
    RETRY,
    COMPLETED,
    PUBLISHED,
    FAILED,
    OBSOLETE,
    CANCELLED
}
