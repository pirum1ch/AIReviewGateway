package com.review.gateway.dto;

import java.util.Map;

/**
 * {@code GET /metrics} response (architecture §11), ADMIN-only. {@code ownershipMismatches} is broken
 * down by endpoint ({@code "heartbeat"}/{@code "result"}/{@code "fail"}); {@code
 * workerFailureReportsIgnored} counts every rejected or no-op {@code POST /jobs/{id}/fail} report
 * (WOR-03).
 */
public record MetricsResponse(
        long total,
        Map<String, Long> byStatus,
        double avgQueueMs,
        double avgRunMs,
        long totalComments,
        long retries,
        boolean promptManagerEnabled,
        long promptDisabledCount,
        long promptSectionMissingCount,
        Map<String, Long> ownershipMismatches,
        long workerFailureReportsIgnored,
        boolean positionAnchoringEnabled,
        long positionsAnchored,
        long positionsUnresolved,
        long diffRefsUnavailable,
        long positionRejectedByGitLab) {
}
