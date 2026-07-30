package com.review.gateway.exception;

/**
 * Thrown when the assembled system prompt exceeds {@code gateway.prompt.limits.max-system-prompt-tokens}
 * (aggregate, over all sections + preamble/trailer/delimiters), or when the remaining diff budget after
 * subtracting the resolved system-prompt size falls below {@code
 * gateway.prompt.limits.min-diff-budget-tokens}. Maps to {@code HTTP 422 PROMPT_TOO_LARGE} at the
 * controller layer — distinct from {@code DIFF_TOO_LARGE} so an operator can tell the two causes apart.
 */
public class PromptTooLargeException extends RuntimeException {

    public PromptTooLargeException(String message) {
        super(message);
    }
}
