package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

/**
 * Outcome of {@code ReviewService#createReview}. {@code deduplicated == true} means an existing
 * active Review for the dedup key was returned instead of creating a new one (req. 1.5); the HTTP
 * response is identical either way (200/QUEUED-or-current-status), only logging/metrics care about
 * the distinction.
 */
public record CreateReviewResult(Long reviewId, ReviewStatus status, boolean deduplicated) {
}
