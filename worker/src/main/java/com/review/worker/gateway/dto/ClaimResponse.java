package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.ClaimJobResponse} field-for-field. */
public record ClaimResponse(long jobId, long reviewId, JobPayload payload) {
}
