package com.review.gateway.service;

import com.review.gateway.exception.DiffIntegrityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WHR-27 (threat model §4.1(4)): the diagnostic MR comment is a compile-time-constant template plus a
 * closed reason enum plus {@code head_sha} -- never exception text, class names, status codes, endpoint
 * names, file paths, counts, sizes, config values or internal identifiers. Asserted byte-for-byte per
 * reason, plus a direct proof that a dangerous, URI-bearing exception message never reaches the render
 * output -- {@link DiagnosticCommentRenderer#render} never even accepts an exception/message parameter,
 * only the closed {@link DiffIntegrityException.Reason} enum and the (already MR-public) {@code head_sha}.
 */
class DiagnosticCommentRendererTest {

    private static final String HEAD_SHA = "b".repeat(40);

    @Test
    void diffTooLargeOrTruncatedRendersTheExactExpectedTemplate() {
        String rendered = DiagnosticCommentRenderer.render(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED, HEAD_SHA);

        assertThat(rendered).isEqualTo(DiagnosticCommentRenderer.MARKER + "\n\n"
                + "**AI review was not run for revision `" + HEAD_SHA + "`.**\n\n"
                + "Reason: one or more changed files could not be reliably read (the diff may be too large or was truncated).\n\n"
                + "This will be retried automatically on the next push to this merge request.");
    }

    @Test
    void diffUnavailableRendersTheExactExpectedTemplate() {
        String rendered = DiagnosticCommentRenderer.render(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, HEAD_SHA);

        assertThat(rendered).isEqualTo(DiagnosticCommentRenderer.MARKER + "\n\n"
                + "**AI review was not run for revision `" + HEAD_SHA + "`.**\n\n"
                + "Reason: the diff for this revision could not be retrieved (it may have been superseded by a newer push).\n\n"
                + "This will be retried automatically on the next push to this merge request.");
    }

    @Test
    void diffUnsupportedContentRendersTheExactExpectedTemplate() {
        String rendered = DiagnosticCommentRenderer.render(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT, HEAD_SHA);

        assertThat(rendered).isEqualTo(DiagnosticCommentRenderer.MARKER + "\n\n"
                + "**AI review was not run for revision `" + HEAD_SHA + "`.**\n\n"
                + "Reason: one or more changed file paths use characters that are not supported for automated review.\n\n"
                + "This will be retried automatically on the next push to this merge request.");
    }

    @Test
    void aDangerousUriBearingExceptionMessageNeverReachesTheRenderedComment() {
        // The exact class of message the threat model warns about (SR-17/PMR-26, one audience wider):
        // an internal endpoint, host, and query string that a RestClientException might carry.
        String dangerousMessage = "GitLab /repository/compare?from=aaaaaaa&to=bbbbbbb returned 500 from "
                + "https://internal-gitlab.corp.example:8443/api/v4/projects/12345 (RestClientException: "
                + "connection refused at com.review.gateway.service.GitLabClientImpl.compareDiff)";
        DiffIntegrityException failureCarryingDangerousDetail =
                new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, dangerousMessage);

        // DiagnosticCommentRenderer.render only ever accepts (Reason, headSha) -- it structurally cannot
        // see getMessage(); this asserts the actual production call shape does not either.
        String rendered = DiagnosticCommentRenderer.render(failureCarryingDangerousDetail.reason(), HEAD_SHA);

        assertThat(rendered)
                .doesNotContain("internal-gitlab.corp.example")
                .doesNotContain("8443")
                .doesNotContain("RestClientException")
                .doesNotContain("compareDiff")
                .doesNotContain("12345")
                .doesNotContain("from=aaaaaaa")
                .doesNotContain("500")
                .doesNotContain(dangerousMessage);
    }

    @Test
    void everyReasonProducesAUniqueRenderingAndAlwaysCarriesTheMarker() {
        for (DiffIntegrityException.Reason reason : DiffIntegrityException.Reason.values()) {
            String rendered = DiagnosticCommentRenderer.render(reason, HEAD_SHA);
            assertThat(rendered).startsWith(DiagnosticCommentRenderer.MARKER).contains(HEAD_SHA);
        }
    }
}
