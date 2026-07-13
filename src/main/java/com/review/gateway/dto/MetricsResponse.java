package com.review.gateway.dto;

import java.util.Map;

/** {@code GET /metrics} response (architecture §11), ADMIN-only. */
public record MetricsResponse(
        long total,
        Map<String, Long> byStatus,
        double avgQueueMs,
        double avgRunMs,
        long totalComments,
        long retries) {
}
