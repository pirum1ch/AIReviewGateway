package com.review.gateway.service;

import com.review.gateway.exception.DiffIntegrityException;

/**
 * GitLab Webhook Diff Trigger (WHR-27, threat model §4.1(4)): the best-effort diagnostic MR comment is a
 * <b>compile-time-constant template</b> plus a closed reason enum plus {@code head_sha} — never exception
 * text, class names, status codes, endpoint names, file paths, counts, sizes, config values, or any other
 * internal identifier. Because the rendered text is 100% Gateway-authored, no output sanitization of it
 * is needed, which is itself the reason to keep it 100% Gateway-authored.
 */
final class DiagnosticCommentRenderer {

    /**
     * WHR-28: the anti-duplicate lookup ({@code WebhookReviewTriggerService}) filters notes by
     * {@code author.id} (server-side field) AND this Gateway-constant marker string — never by body text
     * alone.
     */
    static final String MARKER = "<!-- ai-review-gateway:diagnostic -->";

    private DiagnosticCommentRenderer() {
    }

    static String renderLlmFailure(String headSha) {
        return MARKER + "\n\n**AI review could not be completed for revision `" + headSha + "`.**\n\n"
                + "The automated review failed after multiple attempts due to an internal error. "
                + "Please assign a human reviewer to this merge request.\n\n"
                + "_(This is a one-time notification — the review will not be retried automatically.)_";
    }

    static String render(DiffIntegrityException.Reason reason, String headSha) {
        String reasonText = switch (reason) {
            case DIFF_TOO_LARGE_OR_TRUNCATED -> "one or more changed files could not be reliably read "
                    + "(the diff may be too large or was truncated)";
            case DIFF_UNAVAILABLE -> "the diff for this revision could not be retrieved (it may have "
                    + "been superseded by a newer push)";
            case DIFF_UNSUPPORTED_CONTENT -> "one or more changed file paths use characters that are "
                    + "not supported for automated review";
        };
        return MARKER + "\n\n**AI review was not run for revision `" + headSha + "`.**\n\n"
                + "Reason: " + reasonText + ".\n\n"
                + "This will be retried automatically on the next push to this merge request.";
    }
}
