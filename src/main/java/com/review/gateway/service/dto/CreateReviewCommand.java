package com.review.gateway.service.dto;

/**
 * Input to {@code ReviewService#createReview}. Mirrors the eventual {@code CreateReviewRequest}
 * controller DTO (architecture §11) but lives in {@code service.dto} since no controller exists yet.
 */
public record CreateReviewCommand(
        Long projectId,
        Long mergeRequestId,
        String headSha,
        String baseSha,
        String diff,
        String promptVersion,
        Integer priority) {
}
