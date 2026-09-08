package com.review.gateway.exception;

/**
 * GitLab Webhook Diff Trigger: thrown for any transient failure while fetching a Merge Request's diff
 * from GitLab (network error, timeout, 5xx, unexpected non-2xx, oversized response). <b>Retryable</b> —
 * GitLab's own webhook-delivery retry and the hourly {@code ReviewerSweepService} backstop are what
 * eventually succeed; no diagnostic MR comment is posted for this class (architecture decision #2,
 * threat model §4.4) since the failure is expected to resolve itself. Maps to {@code HTTP 502
 * DIFF_FETCH_FAILED} in {@code GlobalExceptionHandler} — never actually reaches an HTTP caller in
 * shipped code, since {@code WebhookController} always responds with the same coarse outcome regardless
 * (WHR-07); the mapping exists for defense in depth and consistency with the rest of this codebase's
 * exception-handling discipline.
 */
public class DiffFetchUnavailableException extends RuntimeException {

    public DiffFetchUnavailableException(String message) {
        super(message);
    }

    public DiffFetchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
