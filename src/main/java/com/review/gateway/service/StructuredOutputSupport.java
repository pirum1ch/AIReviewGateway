package com.review.gateway.service;

import java.util.Set;

/**
 * The single switch (SRO-61) that decides whether a {@code promptVersion} is a Structured Review
 * Output version — i.e. whether {@code DiffChunker} applies {@code max-files-per-chunk} and the
 * SRO-66b edge bounds, {@code POST /reviews} runs the SRO-16/17/65 edge validation, {@code
 * QueueManager.claimJobRow} renders the SRO-64 coverage block and may build a decoder constraint,
 * {@code StructuredResponseParser} (not {@code CommentParser}) parses the result, {@code
 * gateway.structured.answer-reserve} and the computed coverage header reserve apply, and the SRO-68
 * fallback restriction applies.
 *
 * <p>{@code reviews.prompt_version} already persists this immutably at Review-creation time, so the
 * decision is stable and reconstructible for the whole life of a Review, including across Gateway
 * restarts and config changes (SRO-61) — no new column is needed for it.
 */
public final class StructuredOutputSupport {

    /** SRO-61: currently exactly {@code v3}. v1/v2 are never structured, by construction. */
    public static final Set<String> STRUCTURED_OUTPUT_PROMPT_VERSIONS = Set.of("v3");

    private StructuredOutputSupport() {
    }

    public static boolean isStructured(String promptVersion) {
        return promptVersion != null && STRUCTURED_OUTPUT_PROMPT_VERSIONS.contains(promptVersion);
    }
}
