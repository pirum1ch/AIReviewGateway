package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

import java.time.Instant;

/**
 * Read model backing {@code GET /reviews/{id}} (architecture §11 {@code ReviewStatusResponse}).
 * Deliberately excludes the diff/raw response (SR-14/T-21: no crown-jewel payload in a status read).
 */
public record ReviewStatusView(
        Long reviewId,
        ReviewStatus status,
        int attempts,
        Instant createdAt,
        Instant updatedAt,
        long commentCount) {
}
