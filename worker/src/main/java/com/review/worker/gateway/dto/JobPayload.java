package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.JobPayload} field-for-field. */
public record JobPayload(String diff, String promptVersion) {
}
