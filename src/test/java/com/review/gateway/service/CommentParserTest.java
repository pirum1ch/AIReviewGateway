package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.dto.ParsedComment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentParserTest {

    private GatewayProperties properties() {
        return new GatewayProperties();
    }

    private CommentParser parser() {
        return new CommentParser(properties());
    }

    @Test
    void parsesAWellFormedJsonArray() {
        CommentParser parser = parser();
        String raw = """
                Here is my review:
                [
                  {"file": "Foo.java", "line": 42, "severity": "MAJOR", "comment": "Null check missing"},
                  {"file": "Bar.java", "line": 7, "severity": "minor", "comment": "Consider renaming"}
                ]
                Thanks!
                """;

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).filePath()).isEqualTo("Foo.java");
        assertThat(comments.get(0).lineNumber()).isEqualTo(42);
        assertThat(comments.get(0).severity()).isEqualTo(Severity.MAJOR);
        assertThat(comments.get(0).text()).contains("Null check missing");
        assertThat(comments.get(1).severity()).isEqualTo(Severity.MINOR);
    }

    @Test
    void supportsKeyAliases() {
        CommentParser parser = parser();
        String raw = """
                [{"path": "Baz.java", "lineNumber": 3, "text": "Alias-based fields work too"}]
                """;

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).filePath()).isEqualTo("Baz.java");
        assertThat(comments.get(0).lineNumber()).isEqualTo(3);
        assertThat(comments.get(0).severity()).isEqualTo(Severity.INFO); // no severity field -> default
    }

    @Test
    void malformedJsonFallsBackToWholeResponseAsOneComment() {
        CommentParser parser = parser();
        String raw = "This is just plain prose, no JSON array here at all, just [ unbalanced brackets";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).filePath()).isNull();
        assertThat(comments.get(0).severity()).isEqualTo(Severity.INFO);
        assertThat(comments.get(0).text()).contains("This is just plain prose");
    }

    @Test
    void emptyJsonArrayFallsBackToWholeResponse() {
        CommentParser parser = parser();
        String raw = "No findings. []";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).contains("No findings");
    }

    @Test
    void blankResponseProducesAPlaceholderComment() {
        CommentParser parser = parser();

        List<ParsedComment> comments = parser.parse("   ");

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).contains("empty response");
    }

    @Test
    void quickActionLinesAreStripped() {
        CommentParser parser = parser();
        String raw = "/close\nThis line should survive.\n/assign @someone\nAnd this one too.";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        String text = comments.get(0).text();
        assertThat(text).doesNotContain("/close");
        assertThat(text).doesNotContain("/assign");
        assertThat(text).contains("This line should survive.");
        assertThat(text).contains("And this one too.");
    }

    @Test
    void mentionsAreNeutralizedWithZeroWidthSpace() {
        CommentParser parser = parser();
        String raw = "Great catch @all, please review @security-team too.";

        List<ParsedComment> comments = parser.parse(raw);

        String text = comments.get(0).text();
        // The literal "@all" (no zero-width char immediately after '@') must not appear verbatim.
        assertThat(text).doesNotContain("@all");
        assertThat(text).contains("@​all");
        assertThat(text).contains("@​security-team");
    }

    @Test
    void htmlIsEscaped() {
        CommentParser parser = parser();
        String raw = "Suspicious: <script>alert(1)</script>";

        List<ParsedComment> comments = parser.parse(raw);

        String text = comments.get(0).text();
        assertThat(text).doesNotContain("<script>");
        assertThat(text).contains("&lt;script&gt;");
    }

    @Test
    void commentCountIsCapped() {
        GatewayProperties properties = properties();
        properties.getPublish().setMaxCommentCount(2);
        CommentParser parser = new CommentParser(properties);

        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                raw.append(",");
            }
            raw.append("{\"comment\": \"finding number ").append(i).append("\"}");
        }
        raw.append("]");

        List<ParsedComment> comments = parser.parse(raw.toString());

        assertThat(comments).hasSize(2);
    }

    @Test
    void commentLengthIsCappedWithTruncationMarker() {
        GatewayProperties properties = properties();
        properties.getPublish().setMaxCommentLength(50);
        CommentParser parser = new CommentParser(properties);

        String raw = "x".repeat(500);

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).text().length()).isLessThanOrEqualTo(50);
        assertThat(comments.get(0).text()).endsWith("[truncated]");
    }

    @Test
    void entriesWithNoCommentFieldAreSkipped() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"NoComment.java\", \"line\": 1}, {\"comment\": \"Real finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).contains("Real finding");
    }

    @Test
    void allQuickActionOnlyResponseProducesPlaceholder() {
        CommentParser parser = parser();
        String raw = "/close\n/assign @bob";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).text()).contains("no publishable content");
    }

    // ---- F02-04/KD-2: filePath now goes through the same sanitation pipeline as comment text ----

    @Test
    void oversizedFilePathIsCappedToTheColumnLimitWithATruncationMarker() {
        CommentParser parser = parser();
        String hugeFilePath = "a/".repeat(1000) + "File.java"; // ~3009 chars, column is VARCHAR(1024)
        String raw = "[{\"file\": \"" + hugeFilePath + "\", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).filePath()).hasSizeLessThanOrEqualTo(1024);
        assertThat(comments.get(0).filePath()).endsWith("[truncated]");
    }

    @Test
    void filePathAtExactlyTheColumnLimitIsNotTruncated() {
        CommentParser parser = parser();
        String exactFilePath = "a".repeat(1024);
        String raw = "[{\"file\": \"" + exactFilePath + "\", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).filePath()).isEqualTo(exactFilePath);
    }

    @Test
    void filePathMentionsAreNeutralizedAndHtmlEscaped() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"<script>alert(1)</script>/@all/Foo.java\", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        String filePath = comments.get(0).filePath();
        assertThat(filePath).doesNotContain("<script>");
        assertThat(filePath).contains("&lt;script&gt;");
        assertThat(filePath).doesNotContain("/@all/");
        assertThat(filePath).contains("@​all");
    }

    @Test
    void filePathWithEmbeddedNewlinesIsCollapsedToASingleLine() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"Foo.java\\n/close\\nEvil.java\", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        String filePath = comments.get(0).filePath();
        assertThat(filePath).doesNotContain("\n").doesNotContain("\r");
        assertThat(filePath).contains("Foo.java").contains("Evil.java");
    }

    @Test
    void blankFilePathIsNormalizedToNull() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"   \", \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).filePath()).isNull();
    }

    @Test
    void absentFilePathStaysNull() {
        CommentParser parser = parser();
        String raw = "[{\"comment\": \"finding with no file field at all\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).filePath()).isNull();
    }

    // ---- F02-04/KD-2: lineNumber validation/normalization ----

    @Test
    void negativeLineNumberIsNormalizedToNull() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"Foo.java\", \"line\": -5, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
    }

    @Test
    void zeroLineNumberIsNormalizedToNull() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"Foo.java\", \"line\": 0, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
    }

    @Test
    void positiveLineNumberIsPreserved() {
        CommentParser parser = parser();
        String raw = "[{\"file\": \"Foo.java\", \"line\": 17, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).lineNumber()).isEqualTo(17);
    }

    @Test
    void outOfIntRangeLineNumberIsIgnoredAndStaysNull() {
        CommentParser parser = parser();
        // 9999999999999 does not fit in an int; JsonNode#isInt() is false for it, and it is not textual
        // either, so firstInt(...) must not blow up trying to parse it -- it should just fall through to
        // null, same as "no line field at all".
        String raw = "[{\"file\": \"Foo.java\", \"line\": 9999999999999, \"comment\": \"finding\"}]";

        List<ParsedComment> comments = parser.parse(raw);

        assertThat(comments.get(0).lineNumber()).isNull();
    }
}
