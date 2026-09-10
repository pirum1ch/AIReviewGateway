package com.review.gateway.exception;

/**
 * Backend Self-Registration (BSQ-04/BSQ-13): thrown when a submitted {@code url} fails {@link
 * com.review.gateway.service.BackendUrlValidator}. Mapped to {@code 422 BACKEND_URL_REJECTED} by {@code
 * GlobalExceptionHandler}. The WORKER path (announce) always constructs this with the single fixed
 * message {@code "Backend URL was rejected"} — every distinct validator cause collapses to the same
 * wire-visible text (BSQ-13), since the five validator messages otherwise form a DNS-resolution /
 * allowlist-membership oracle for a shared-token holder. The ADMIN path preserves the validator's own
 * specific (constant, non-reflecting) message instead — an operator registering a backend needs to know
 * which rule it failed.
 */
public class BackendUrlRejectedException extends RuntimeException {

    public BackendUrlRejectedException(String message) {
        super(message);
    }
}
