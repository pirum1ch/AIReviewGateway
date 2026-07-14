package com.review.worker.error;

/**
 * Raised for any failure while calling the local llama-server: a 5xx status, a malformed/unparseable
 * body, a connection error, or a response that exceeds {@code worker.limits.max-response-bytes}
 * (WSR-04/WSR-05 — the oversize case is deliberately represented here rather than as a distinct
 * exception type: every {@code LlamaException}, regardless of the specific cause, results in the same
 * "abandon this job" handling one level up (architecture §6/§7 error taxonomy), so a separate type
 * would add no behavioral distinction).
 */
public class LlamaException extends RuntimeException {

    public LlamaException(String message) {
        super(message);
    }

    public LlamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
