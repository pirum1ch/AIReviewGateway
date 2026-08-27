package com.review.gateway.exception;

/**
 * Thrown at {@code POST /reviews} when a structured (v3) {@code promptVersion} was requested but the
 * request cannot carry the coverage guarantee the feature promises (architecture §4.3, SRO-16/17/65) —
 * or, more generally, when {@code promptVersion} is not (yet) in {@code
 * gateway.review.allowed-prompt-versions} (threat model SOR-08). Maps to {@code HTTP 422
 * STRUCTURED_OUTPUT_UNSUPPORTED} at the controller layer — the single new error code this feature adds
 * (architecture §4.3 preamble); reused for every edge rejection this feature introduces rather than
 * minting one code per cause.
 */
public class StructuredOutputUnsupportedException extends RuntimeException {

    public StructuredOutputUnsupportedException(String message) {
        super(message);
    }
}
