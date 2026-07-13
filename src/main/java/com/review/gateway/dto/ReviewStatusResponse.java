package com.review.gateway.dto;

import java.time.Instant;

/** {@code GET /reviews/{id}} response (architecture §11). Deliberately excludes diff/raw response (SR-14/T-21). */
public record ReviewStatusResponse(
        long reviewId,
        String status,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        Integer commentCount) {
}
