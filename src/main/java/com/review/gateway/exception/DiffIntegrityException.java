package com.review.gateway.exception;

/**
 * GitLab Webhook Diff Trigger ({@code DiffIntegrityVerifier}, threat model §4.2/WHR-12..21): thrown for
 * any <b>deterministic</b> diff-integrity failure — whole-MR {@code overflow}/{@code compare_timeout},
 * an unparsable/inconsistent hunk, a forged/unsupported file path, or an empty diff on a file that is
 * not one of WHR-15's two sound exemptions. <b>Non-retryable</b>: no Review, chunk, or job is ever
 * created for this MR/head_sha — retrying would produce the identical failure. {@link #reason()} is a
 * closed, small vocabulary (never exception text/paths/counts/sizes) safe to render into the
 * best-effort diagnostic MR comment (WHR-27) and the authoritative structured log line (WHR-28); {@link
 * Throwable#getMessage()} may contain more detail and is for server-side logs only, never the HTTP
 * response or the MR comment. Maps to {@code HTTP 422 DIFF_INTEGRITY_CHECK_FAILED} in {@code
 * GlobalExceptionHandler} — never actually reaches an HTTP caller in shipped code, since {@code
 * WebhookController} always responds with the same coarse outcome regardless (WHR-07); the mapping
 * exists for defense in depth and consistency with the rest of this codebase's exception-handling
 * discipline.
 */
public class DiffIntegrityException extends RuntimeException {

    /**
     * Closed reason vocabulary (threat model §4.1(4)) — the only thing about this failure ever rendered
     * into the diagnostic MR comment.
     */
    public enum Reason {
        DIFF_TOO_LARGE_OR_TRUNCATED,
        DIFF_UNAVAILABLE,
        DIFF_UNSUPPORTED_CONTENT
    }

    private final Reason reason;

    public DiffIntegrityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DiffIntegrityException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
