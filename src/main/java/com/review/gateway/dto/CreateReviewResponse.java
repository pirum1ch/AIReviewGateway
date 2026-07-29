package com.review.gateway.dto;

/**
 * {@code POST /reviews} success response (architecture §11): {@code 201} new, {@code 200} deduplicated.
 * {@code chunkCount} (additive, V2 diff chunking) is the number of file-based chunks the diff was split
 * into — {@code 1} for the overwhelming majority of MRs (§8 backward compatibility).
 */
public record CreateReviewResponse(long reviewId, String status, int chunkCount) {
}
