package com.review.gateway.service.dto;

import com.review.gateway.model.enums.Severity;

/**
 * A single comment parsed from an LLM's raw review response, per architecture §11. Text is already
 * sanitized (HTML-escaped, quick-actions stripped, mentions neutralized) and capped in length by the
 * time this record leaves {@code CommentParser} — it is safe to persist and, later, publish verbatim.
 */
public record ParsedComment(String filePath, Integer lineNumber, Severity severity, String text) {
}
