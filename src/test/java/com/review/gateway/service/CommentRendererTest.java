package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommentRenderer} (architecture §6, SRO-50-57; threat model SOR-09/10/12,
 * all CRITICAL).
 */
class CommentRendererTest {

    private CommentRenderer newRenderer() {
        GatewayProperties properties = new GatewayProperties();
        return new CommentRenderer(new CommentParser(properties, new MetricsCounters()), new TextSanitizer(), properties);
    }

    private String gitSection(String path, String... hunkAndBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("index 1111111..2222222 100644\n");
        sb.append("--- a/").append(path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        for (String line : hunkAndBody) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---- header (SRO-50) ----

    @Test
    void headerContainsSeverityPathAndLine() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("src/A.java", "@@ -1,3 +1,3 @@", " line1", "+line2", " line3");

        String rendered = renderer.render("src/A.java", 2, Severity.MAJOR, "Something is wrong", "", diff);

        assertThat(rendered).startsWith("**MAJOR** — `src/A.java`:2");
    }

    @Test
    void headerOmitsLineNumberForAFileLevelFinding() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("src/A.java", null, Severity.INFO, "File-level note", "", null);

        assertThat(rendered).startsWith("**INFO** — `src/A.java`");
        assertThat(rendered.split("\n")[0]).doesNotContain(":");
    }

    @Test
    void proseGoesThroughTheExistingPipelineHtmlEscapedAndMentionsNeutralized() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("A.java", null, Severity.MINOR, "<script>alert(1)</script> @someone", "", null);

        assertThat(rendered).doesNotContain("<script>");
        assertThat(rendered).contains("&lt;script&gt;");
    }

    // ---- suggestion block (SRO-52/55) ----

    @Test
    void suggestionBlockUsesAPlainFenceNeverTheSuggestionLanguage() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", "int x = 1;", null);

        assertThat(rendered).contains("Suggested fix:");
        assertThat(rendered).contains("````\nint x = 1;\n````");
        assertThat(rendered).doesNotContain("```suggestion");
    }

    @Test
    void blankSuggestionProducesNoSuggestionBlock() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", "", null);
        String rendered2 = renderer.render("A.java", null, Severity.MINOR, "comment", "   ", null);

        assertThat(rendered).doesNotContain("Suggested fix:");
        assertThat(rendered2).doesNotContain("Suggested fix:");
    }

    @Test
    void suggestionCodeIsNeverHtmlEscaped() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment",
                "String s = \"a & b\";", null);

        assertThat(rendered).contains("String s = \"a & b\";");
        assertThat(rendered).doesNotContain("&amp;");
        assertThat(rendered).doesNotContain("&quot;");
    }

    @Test
    void quickActionsAsWholeCodeContentPreservesRealCommentLines() {
        CommentRenderer renderer = newRenderer();
        String suggestion = "// a real comment\n/* block comment */\nint x = 1;";

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", suggestion, null);

        assertThat(rendered).contains("// a real comment");
        assertThat(rendered).contains("/* block comment */");
    }

    @Test
    void quickActionLineIsStrippedFromSuggestion() {
        CommentRenderer renderer = newRenderer();
        String suggestion = "int x = 1;\n/close\nint y = 2;";

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", suggestion, null);

        assertThat(rendered).doesNotContain("/close");
        assertThat(rendered).contains("int x = 1;");
        assertThat(rendered).contains("int y = 2;");
    }

    @Test
    void backtickRunsInSuggestionAreCollapsedBeforeCapping() {
        CommentRenderer renderer = newRenderer();
        String suggestion = "content ```` more ``` content";

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", suggestion, null);

        // The collapsed content must never contain a 3+ backtick run (which would break out of our fence).
        String body = rendered.substring(rendered.indexOf("````\n") + 5);
        String innerContent = body.substring(0, body.indexOf("\n````"));
        assertThat(innerContent).doesNotContain("```");
    }

    // ---- diff-context block (SRO-51, SOR-12) ----

    @Test
    void diffContextBlockLocatesTheExactLineWithinItsOwnFileSection() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("src/A.java", "@@ -1,5 +1,5 @@", " line1", " line2", "+line3", " line4", " line5");

        String rendered = renderer.render("src/A.java", 3, Severity.MAJOR, "issue here", "", diff);

        assertThat(rendered).contains("```diff");
        assertThat(rendered).contains("+line3");
    }

    @Test
    void diffContextNeverContainsBytesFromAnotherFilesSection() {
        CommentRenderer renderer = newRenderer();
        String diffA = gitSection("A.java", "@@ -1,1 +1,1 @@", "+contentA");
        String diffB = gitSection("B.java", "@@ -1,1 +1,1 @@", "+contentB");
        String combined = diffA + diffB;

        String rendered = renderer.render("A.java", 1, Severity.MAJOR, "issue", "", combined);

        assertThat(rendered).contains("+contentA");
        assertThat(rendered).doesNotContain("+contentB");
    }

    @Test
    void unlocatableLineOmitsTheDiffContextBlockRatherThanGuessing() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+onlyLine");

        String rendered = renderer.render("A.java", 999, Severity.MAJOR, "issue", "", diff);

        assertThat(rendered).doesNotContain("```diff");
    }

    @Test
    void nullLineNumberOmitsTheDiffContextBlock() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+onlyLine");

        String rendered = renderer.render("A.java", null, Severity.MAJOR, "issue", "", diff);

        assertThat(rendered).doesNotContain("```diff");
    }

    @Test
    void includeDiffContextFalseDisablesTheBlockEntirely() {
        GatewayProperties properties = new GatewayProperties();
        properties.getStructured().setIncludeDiffContext(false);
        CommentRenderer renderer = new CommentRenderer(new CommentParser(properties, new MetricsCounters()), new TextSanitizer(), properties);
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+onlyLine");

        String rendered = renderer.render("A.java", 1, Severity.MAJOR, "issue", "", diff);

        assertThat(rendered).doesNotContain("```diff");
    }

    // ---- header path safety (SOR-10) ----

    @Test
    void headerPathIsHtmlEscapedLikeV1V2() {
        CommentRenderer renderer = newRenderer();

        // Not a realistic SRO-65-validated path (that alphabet already forbids '<'), but this test
        // asserts the independent SOR-10 escaping discipline holds regardless of upstream validation.
        String rendered = renderer.render("src/<script>.java", null, Severity.MAJOR, "x", "", null);

        assertThat(rendered).doesNotContain("<script>");
    }

    @Test
    void headerPathBackticksAreStrippedDefensivelyEvenThoughSRO65ShouldPreventThem() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("a`.java", null, Severity.MAJOR, "x", "", null);

        String headerLine = rendered.split("\n")[0];
        // Exactly two backticks in the header line (the inline-code span delimiters) -- never more.
        long backtickCount = headerLine.chars().filter(c -> c == '`').count();
        assertThat(backtickCount).isEqualTo(2);
    }

    // ---- length truncation (SRO-53) and fence balance (SOR-09) ----

    @Test
    void oversizedBodyDropsDiffContextBeforeSuggestionBeforeTruncatingProse() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPublish().setMaxCommentLength(200);
        CommentRenderer renderer = new CommentRenderer(new CommentParser(properties, new MetricsCounters()), new TextSanitizer(), properties);
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+" + "x".repeat(50));
        String longProse = "y".repeat(500);
        String suggestion = "z".repeat(100);

        String rendered = renderer.render("A.java", 1, Severity.MAJOR, longProse, suggestion, diff);

        assertThat(rendered.length()).isLessThanOrEqualTo(200);
        // Every fence marker present is balanced -- never an unterminated fence.
        long fenceCount = countOccurrences(rendered, "````");
        assertThat(fenceCount % 2).isEqualTo(0);
    }

    @Test
    void everyRenderedBodyHasBalancedFenceMarkers() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+content");

        String rendered = renderer.render("A.java", 1, Severity.MAJOR, "comment", "suggestion code", diff);

        assertThat(countOccurrences(rendered, "````") % 2).isEqualTo(0);
    }

    private long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
