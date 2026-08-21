package com.review.gateway.exception;

/**
 * Thrown when a <b>mandatory</b> (corporate) prompt source file returns 404 with the project/ref
 * already proven reachable (i.e. {@code resolveCommitSha} succeeded first) — a mandatory file being
 * absent is a configuration error, not "no customization available". Maps to {@code HTTP 422
 * PROMPT_SOURCE_MISSING} at the controller layer.
 */
public class PromptSourceMissingException extends RuntimeException {

    public PromptSourceMissingException(String message) {
        super(message);
    }
}
