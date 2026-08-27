package com.review.gateway.service.dto;

import com.review.gateway.model.enums.ReviewStatus;

import java.util.Map;

/**
 * Aggregate backing {@code GET /metrics} (architecture §11 {@code MetricsResponse}).
 *
 * <p>{@code ownershipMismatches}/{@code workerFailureReportsIgnored} (WOR-03, Worker Observability &amp;
 * Claim Latency): process-local, in-memory counters from {@code MetricsCounters} — deliberately not
 * persisted to {@code review_events} (writing an audit row for a rejected {@code POST /jobs/{id}/fail}
 * report would be an authenticated, unbounded {@code INSERT} primitive; see that class's javadoc).
 */
public record MetricsSnapshot(
        long total,
        Map<ReviewStatus, Long> byStatus,
        double avgQueueMs,
        double avgRunMs,
        long totalComments,
        long retries,
        long promptDisabledCount,
        long promptSectionMissingCount,
        Map<String, Long> ownershipMismatches,
        long workerFailureReportsIgnored,
        long legacyParseFallback,
        Map<String, Long> structuredValidationFailures,
        Map<String, Long> structuredConstraintSent,
        long structuredFallbackUsed,
        Map<String, Long> structuredFieldTruncated) {
}
