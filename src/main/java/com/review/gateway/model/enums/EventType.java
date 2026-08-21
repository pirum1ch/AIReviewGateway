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
    CANCELLED,
    /** Prompt Manager (V3, PMR-10): recorded on every Review created while {@code gateway.prompt.enabled=false}. */
    PROMPT_DISABLED,
    /** Prompt Manager (V3, PMR-11): a 404 on an explicitly-configured override path. */
    PROMPT_SECTION_MISSING,
    /** Prompt Manager (V3, PMR-09): claim-time fail-closed -- mode=REPO but zero CORPORATE_* rows found. */
    PROMPT_SECTIONS_MISSING
}
