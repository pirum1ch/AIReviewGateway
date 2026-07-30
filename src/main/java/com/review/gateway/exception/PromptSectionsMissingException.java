package com.review.gateway.exception;

/**
 * Claim-time (PMR-09): thrown when a Review's {@code prompt_bundle_mode} is {@code REPO} but zero
 * {@code CORPORATE_*} rows are found in {@code review_prompt_sections} (kill-switch flipped between
 * create and claim, a retention job, a partial transaction, a bug). Not an HTTP-level exception — there
 * is no client request in flight at claim assembly time inside {@code QueueManager}; the caller catches
 * this and fails the job explicitly (never lets it run with an empty/degraded system prompt).
 */
public class PromptSectionsMissingException extends RuntimeException {

    public PromptSectionsMissingException(String message) {
        super(message);
    }
}
