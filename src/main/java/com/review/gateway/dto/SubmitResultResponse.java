package com.review.gateway.dto;

/** {@code POST /jobs/{id}/result} response (architecture §11); idempotent {@code 200} either way (req. 1.9). */
public record SubmitResultResponse(long reviewId, String status) {
}
