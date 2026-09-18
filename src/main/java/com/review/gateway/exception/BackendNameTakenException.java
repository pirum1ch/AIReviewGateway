package com.review.gateway.exception;

/**
 * Backend Self-Registration (BSQ-01/BSQ-10): thrown when an announce targets a {@code backends} row
 * whose {@code announced_by} is held by a <em>different</em> {@code workerId}, or when a first claim of
 * an unowned row also tries to change its {@code url} in the same call (BSQ-01's corrected decision-table
 * row 2). Mapped to {@code 409 BACKEND_NAME_TAKEN} by {@code GlobalExceptionHandler}.
 *
 * <p><b>This is a misconfiguration guard, not an authorization boundary</b> (BSQ-10): {@code workerId} is
 * a self-declared claim under the shared {@code WORKER_TOKEN} (T-03/T-15/WT-16), never a verified
 * identity. What it actually buys: two hosts with a copy-pasted {@code BACKEND_ID} and distinct {@code
 * WORKER_ID}s get a loud, blocked startup failure on the second host instead of silently sharing one
 * registry row.
 */
public class BackendNameTakenException extends RuntimeException {

    public BackendNameTakenException(String message) {
        super(message);
    }
}
