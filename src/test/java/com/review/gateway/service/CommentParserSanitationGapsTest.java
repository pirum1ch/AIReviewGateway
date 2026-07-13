package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.dto.ParsedComment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the LLM-controlled {@code filePath}/{@code lineNumber} fields' sanitation posture
 * (SR-08/SR-09 scope), updated after the F02-04/KD-2 fix.
 *
 * <p><b>Fixed (previously an accepted informational gap):</b> {@code filePath} now goes through the
 * same length-cap/mention-neutralization/HTML-escape pipeline as the comment text
 * ({@code CommentParser.sanitizeFilePath}), and {@code lineNumber} is now validated to be a positive
 * integer, normalizing non-positive values to {@code null} ({@code CommentParser.normalizeLineNumber}).
 * Path-traversal-*shaped* strings (e.g. {@code ../../../etc/passwd}) are still stored verbatim as far
 * as their textual content goes — they contain no HTML-special or mention characters for the pipeline
 * to touch, and {@code filePath}/{@code lineNumber} are still never used as a filesystem path, URL
 * segment, or GitLab API parameter anywhere in the current call graph
 * ({@link GitLabClient#postDiscussion} only ever receives the sanitized {@code comment} body — see
 * {@code GitLabPublisher.publishOneComment}) — they are purely stored, displayed data
 * (`review_comments.file_path`/`line_number`). This should be revisited if a future feature (e.g.
 * line-anchored diff comments via GitLab's per-line discussion API) starts using {@code filePath} to
 * address a location.
 *
 * <p>The oversized-{@code filePath} length-cap fix itself (this file's genuine, previously DB-crashing
 * defect) is covered end-to-end by {@code ResultProcessorOversizedFilePathTest}; this file covers only
 * {@link CommentParser}'s unit-level behavior.
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
        // No HTML-special/mention characters in this path -> the sanitation pipeline is a no-op on its
        // textual content; filePath is unchanged (still not used as a real path/URL anywhere in scope).
        assertThat(comments.get(0).filePath()).isEqualTo("../../../../etc/passwd");
    }

    @Test
    void absoluteFilePathsAreStoredVerbatim() {
        String raw = "[{\"file\": \"/etc/shadow\", \"line\": 1, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).filePath()).isEqualTo("/etc/shadow");
    }

    @Test
    void negativeLineNumbersAreNormalizedToNull() {
        String raw = "[{\"file\": \"A.java\", \"line\": -999, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
    }

    @Test
    void zeroLineNumberIsNormalizedToNull() {
        String raw = "[{\"file\": \"A.java\", \"line\": 0, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser().parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
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
