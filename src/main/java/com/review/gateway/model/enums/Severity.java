package com.review.gateway.model.enums;

/**
 * Severity of a parsed review comment, as classified from the LLM's raw response by
 * {@code CommentParser} (feature/02-core-services).
 */
public enum Severity {
    INFO,
    MINOR,
    MAJOR,
    CRITICAL
}
