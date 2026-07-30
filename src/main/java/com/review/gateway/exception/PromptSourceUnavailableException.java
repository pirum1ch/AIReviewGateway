package com.review.gateway.exception;

/**
 * Thrown for any network/timeout/5xx/401/403 failure while resolving a prompt source, or when the
 * bounded {@code gateway.prompt.total-timeout} deadline elapses. Maps to {@code HTTP 502
 * PROMPT_RESOLUTION_FAILED} at the controller layer. Deliberately coarse and undifferentiated
 * (PMR-26): distinct causes (project not found / no access / MR not found / bad ref) must not form an
 * oracle for project/MR existence across the organization — the detailed reason goes to server logs and
 * {@code review_events} only.
 */
public class PromptSourceUnavailableException extends RuntimeException {

    public PromptSourceUnavailableException(String message) {
        super(message);
    }

    public PromptSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
