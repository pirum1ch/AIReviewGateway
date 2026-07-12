package com.review.gateway.model.enums;

/**
 * Health status of a {@link com.review.gateway.model.Backend} (llama-server registry entry).
 * {@code ACTIVE} backends are eligible for new job assignments; {@code SUSPECT} is entered on
 * health-check failure and auto-recovers to {@code ACTIVE} on the next successful probe.
 */
public enum BackendStatus {
    ACTIVE,
    SUSPECT,
    MAINTENANCE,
    OFFLINE
}
