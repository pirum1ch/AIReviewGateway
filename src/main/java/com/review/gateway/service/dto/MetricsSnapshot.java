package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

import java.util.Map;

/**
 * Aggregate backing {@code GET /metrics} (architecture §11 {@code MetricsResponse}).
 */
public record MetricsSnapshot(
        long total,
        Map<ReviewStatus, Long> byStatus,
        double avgQueueMs,
        double avgRunMs,
        long totalComments,
        long retries) {
}
