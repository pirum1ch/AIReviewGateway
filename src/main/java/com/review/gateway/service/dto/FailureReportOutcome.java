package com.review.gateway.service.dto;

/**
 * Outcome of {@code QueueManager.reportFailure} ({@code POST /jobs/{id}/fail}, architecture §5.2).
 * {@code ACCEPTED} is deliberately identical whether the report was actually applied (job was RUNNING
 * and owned) or was an idempotent no-op (job no longer RUNNING) — the Worker has no use for the
 * distinction and a distinguishable response would echo internal state to a caller who may not own the
 * current attempt (WOC-28/F02-05 parity with {@code submitResult}).
 */
public enum FailureReportOutcome {
    ACCEPTED,
    NOT_FOUND,
    OWNERSHIP_MISMATCH
}
