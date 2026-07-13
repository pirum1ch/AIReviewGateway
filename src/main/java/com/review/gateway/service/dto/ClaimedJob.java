package com.review.gateway.service.dto;

/**
 * Successful {@code POST /jobs/claim} outcome (architecture §11 {@code ClaimJobResponse} +
 * {@code JobPayload}, flattened). The payload is built from {@code review_inputs}.
 */
public record ClaimedJob(Long jobId, Long reviewId, String diff, String promptVersion) {
}
