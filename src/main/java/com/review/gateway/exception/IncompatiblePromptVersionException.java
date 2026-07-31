package com.review.gateway.exception;

/**
 * CSR-12: thrown when a Review needs more than one chunk but the caller-supplied
 * {@code promptVersion} is not on the Gateway-side allowlist of prompt versions known to contain the
 * {@code {{CHUNK_CONTEXT}}} placeholder. Fail-closed: dispatching a chunked Review under a template
 * that silently ignores {@code chunkContext} would mean the model never learns which files are outside
 * its current chunk, defeating the point of chunking. Maps to {@code HTTP 422} (same family as
 * {@code DIFF_TOO_LARGE} — a request-shape problem, not a server error).
 */
public class IncompatiblePromptVersionException extends RuntimeException {

    public IncompatiblePromptVersionException(String message) {
        super(message);
    }
}
