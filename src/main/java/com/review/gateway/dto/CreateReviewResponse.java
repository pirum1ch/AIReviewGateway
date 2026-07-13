package com.review.gateway.dto;

/** {@code POST /reviews} success response (architecture §11): {@code 201} new, {@code 200} deduplicated. */
public record CreateReviewResponse(long reviewId, String status) {
}
