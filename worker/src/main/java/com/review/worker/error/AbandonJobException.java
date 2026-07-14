package com.review.worker.error;

/**
 * Signals that the current job must be abandoned <em>before</em> (or without) submitting any result:
 * an unknown/invalid {@code promptVersion}, an oversized diff, or any other pre-flight condition that
 * makes it unsafe or meaningless to even attempt calling llama-server (architecture doc D6 — "no
 * synthetic error result is ever submitted"; the Gateway's own stale-heartbeat sweep reclaims an
 * abandoned job after ~180s).
 *
 * <p>Never carries the offending content (diff/promptVersion value) verbatim in a way that would leak
 * Gateway-supplied data into logs beyond what the caller explicitly chooses to log (WSR-10/WSR-18).
 */
public class AbandonJobException extends RuntimeException {

    public AbandonJobException(String message) {
        super(message);
    }

    public AbandonJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
