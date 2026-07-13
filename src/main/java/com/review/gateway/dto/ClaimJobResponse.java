package com.review.gateway.dto;

/** {@code POST /jobs/claim} success response body (architecture §11); {@code 204} when nothing to claim. */
public record ClaimJobResponse(long jobId, long reviewId, JobPayload payload) {
}
