package com.review.gateway.exception;

/**
 * Thrown when a fetched prompt source file is structurally invalid: exceeds {@code
 * gateway.prompt.limits.max-file-bytes}, is not valid UTF-8, contains a NUL byte, or is empty. Maps to
 * {@code HTTP 422 PROMPT_SOURCE_INVALID} at the controller layer.
 */
public class PromptSourceInvalidException extends RuntimeException {

    public PromptSourceInvalidException(String message) {
        super(message);
    }

    public PromptSourceInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
