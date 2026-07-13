package com.review.gateway.dto;

/** Nested payload of {@link ClaimJobResponse} (architecture §11), built from {@code review_inputs}. */
public record JobPayload(String diff, String promptVersion) {
}
