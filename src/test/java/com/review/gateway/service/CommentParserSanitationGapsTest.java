package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.dto.ParsedComment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents two sanitation gaps requested for explicit verification (SR-08/SR-09 scope): path
 * traversal / absurd values in the LLM-controlled {@code filePath} and {@code lineNumber} fields.
 * {@link CommentParser} sanitizes the free-text {@code comment} field thoroughly (HTML-escape,
 * quick-action stripping, mention neutralization, length cap — see {@code CommentParserTest}), but
 * applies <b>no</b> validation at all to {@code filePath}/{@code lineNumber}.
 *
 * <p>This is recorded as <b>informational, not a blocking defect</b>: {@code filePath}/{@code
 * lineNumber} are never used as a filesystem path, URL segment, or GitLab API parameter anywhere in
 * the current call graph ({@link GitLabClient#postDiscussion} only ever receives the sanitized
 * {@code comment} body — see {@code GitLabPublisher.publishOneComment}) — they are purely stored,
 * displayed data (`review_comments.file_path`/`line_number`). There is therefore no current traversal
 * or injection sink. This should be revisited if a future feature (e.g. line-anchored diff comments
 * via GitLab's per-line discussion API) starts using {@code filePath} to address a location.
 *
 * <p>The oversized-{@code filePath} length-cap gap (a genuine, DB-crashing defect, distinct from this
 * file) is covered separately by {@code ResultProcessorOversizedFilePathTest}.
 */
class CommentParserSanitationGapsTest {

    private CommentParser parser() {
        return new CommentParser(new GatewayProperties());
    }

    @Test
    void pathTraversalSequencesInFilePathAreStoredVerbatim() {
        String raw = "[{\"file\": \"../../../../etc/passwd\", \"line\": 1, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments).hasSize(1);
        // Current (unsanitized) behavior -- filePath passes through completely untouched.
        assertThat(comments.get(0).filePath()).isEqualTo("../../../../etc/passwd");
    }

    @Test
    void absoluteFilePathsAreStoredVerbatim() {
        String raw = "[{\"file\": \"/etc/shadow\", \"line\": 1, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).filePath()).isEqualTo("/etc/shadow");
    }

    @Test
    void negativeLineNumbersAreAcceptedWithoutValidation() {
        String raw = "[{\"file\": \"A.java\", \"line\": -999, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).lineNumber()).isEqualTo(-999);
    }

    @Test
    void zeroLineNumberIsAcceptedWithoutValidation() {
        String raw = "[{\"file\": \"A.java\", \"line\": 0, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).lineNumber()).isZero();
    }

    @Test
    void absurdlyLargeLineNumberBeyondIntRangeIsSafelyDroppedRatherThanOverflowing() {
        // 99999999999999999999 does not fit in a long, let alone an int; JsonNode.isInt() correctly
        // reports false for it, and CommentParser's firstInt() has no other alias to try, so it falls
        // back to null rather than overflowing or throwing. This locks down that (correct) behavior.
        String raw = "[{\"file\": \"A.java\", \"line\": 99999999999999999999, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
        assertThat(comments.get(0).text()).contains("finding");
    }

    @Test
    void severityStaysUnvalidatedButDefaultsSafelyForGarbageValues() {
        String raw = "[{\"file\": \"A.java\", \"severity\": \"CATASTROPHIC-OMEGA\", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).severity()).isEqualTo(Severity.INFO);
    }
}
