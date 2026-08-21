package com.review.worker.error;

/**
 * Closed-set classification of a Worker-side job failure, sent as the {@code reason} field of {@code
 * POST /jobs/{id}/fail} (architecture §5.3, WOC-22). Audit-only on the Gateway side (WOC-24: never
 * influences the retry-vs-fail decision) — mirrors {@code com.review.gateway.model.enums.JobFailureReason}
 * by name so the wire value round-trips, but the two enums are intentionally independent types (the
 * Gateway treats the wire value as untrusted and whitelist-parses it, WOC-23; it never shares a class
 * with this one).
 */
public enum JobFailureReason {
    LLM_EMPTY_RESPONSE,
    LLM_ERROR,
    LLM_TIMEOUT,
    LLM_RESPONSE_TOO_LARGE,
    PROMPT_INVALID,
    WORKER_ERROR
}
