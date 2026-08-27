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

    // ---- F-SRO-06: an altered/truncated code block is marked, never presented as verbatim ----

    @Test
    void unalteredSuggestionCarriesNoAlteredMarker() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", "int x = 1;", null);

        assertThat(rendered).doesNotContain("normalized for display");
    }

    @Test
    void aSuggestionWithAStrippedQuickActionLineCarriesTheAlteredMarker() {
        CommentRenderer renderer = newRenderer();
        String suggestion = "int x = 1;\n/close\nint y = 2;";

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", suggestion, null);

        assertThat(rendered)
                .as("sanitizeCodeBlock stripped the /close quick-action line -- the reader must be told "
                        + "this block was altered")
                .contains("normalized for display; not a verbatim quotation");
    }

    @Test
    void aTruncatedSuggestionCarriesTheAlteredMarker() {
        GatewayProperties properties = new GatewayProperties();
        properties.getStructured().setMaxSuggestionChars(10);
        CommentRenderer renderer = new CommentRenderer(new CommentParser(properties, new MetricsCounters()), new TextSanitizer(), properties);
        String suggestion = "x".repeat(50);

        String rendered = renderer.render("A.java", null, Severity.MINOR, "comment", suggestion, null);

        assertThat(rendered).contains("normalized for display");
    }

    @Test
    void aDiffContextBlockWithAStrippedQuickActionLineCarriesTheAlteredMarker() {
        // F-SRO-06: SRO-56 strips a quick-action-shaped line even from a diff-context excerpt (a context
        // line carries a single leading space, so " /close" still matches) -- real source content can
        // vanish mid-excerpt, so the reader must be told the block was altered.
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,3 +1,3 @@", " /close", "+target", " line3");

        String rendered = renderer.render("A.java", 2, Severity.MAJOR, "issue", "", diff);

        assertThat(rendered).contains("Context (excerpt from the reviewed diff):");
        assertThat(rendered).doesNotContain("/close");
        assertThat(rendered).contains("normalized for display");
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
    void diffContextBlockIsAlwaysLabelledAsAnExcerptEvenWhenUnaltered() {
        // F-SRO-06: the diff-context block is by definition an excerpt (±N lines around the finding),
        // never the whole file -- labelled unconditionally, whether or not sanitizeCodeBlock changed
        // anything.
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("src/A.java", "@@ -1,5 +1,5 @@", " line1", " line2", "+line3", " line4", " line5");

        String rendered = renderer.render("src/A.java", 3, Severity.MAJOR, "issue here", "", diff);

        assertThat(rendered).contains("Context (excerpt from the reviewed diff):");
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

    // ---- F-SRO-07: a literal 4+-backtick run in the PROSE field must not defeat fence-balance ----

    @Test
    void aFourBacktickRunInTheProseStillYieldsABalancedBodyWithBothCodeBlocksIntact() {
        // Before F-SRO-07, sanitizeCodeBlock guaranteed code content could never contain a 3+ backtick
        // run, but the prose pipeline (CommentParser.sanitizeProseText) left backticks untouched -- a
        // literal 4-backtick run in the model's comment text made hasBalancedFences see an ODD total
        // count, which dropped BOTH the diff-context and suggestion blocks. Now the prose itself is
        // backtick-collapsed before assembly, so the model-controlled text can never contain a 3+
        // backtick run either, and both code blocks survive.
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+content");
        String proseWithFourBackticks = "See ```` for details";

        String rendered = renderer.render("A.java", 1, Severity.MAJOR, proseWithFourBackticks, "fix code", diff);

        assertThat(countOccurrences(rendered, "````") % 2)
                .as("the assembled body must always have a balanced fence-marker count")
                .isEqualTo(0);
        assertThat(rendered).contains("content"); // the diff-context block survived
        assertThat(rendered).contains("fix code"); // the suggestion block survived
    }

    @Test
    void proseBackticksAreCollapsedButTheAccessibleCommentTextConceptIsUnaffectedForV1V2() {
        // F-SRO-07 is deliberately CommentRenderer-local: CommentParser.sanitizeProseText (the v1/v2
        // pipeline) must stay byte-identical -- this class collapses backticks on ITS OWN COPY of the
        // already-sanitized prose, never inside CommentParser itself.
        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        String proseWithBackticks = "some ```` backticks";

        String v1v2Sanitized = commentParser.sanitizeProseText(proseWithBackticks);

        assertThat(v1v2Sanitized).contains("````");
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

    // ---- F-SRO-04: ChunkDiffIndex / renderIndexed must be behaviorally identical to the String overload ----

    @Test
    void renderIndexedProducesTheExactSameOutputAsTheStringOverload() {
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("src/A.java", "@@ -1,3 +1,3 @@", " line1", "+line2", " line3");

        String viaString = renderer.render("src/A.java", 2, Severity.MAJOR, "Something is wrong", "sug", diff);
        String viaIndex = renderer.renderIndexed("src/A.java", 2, Severity.MAJOR, "Something is wrong", "sug",
                renderer.prepareChunkDiffIndex(diff));

        assertThat(viaIndex).isEqualTo(viaString);
    }

    @Test
    void oneChunkDiffIndexCanBeReusedAcrossMultipleFilesInTheSameChunk() {
        // F-SRO-04: the whole point of the index is to be built ONCE per chunk and reused across every
        // finding's render call, including findings for DIFFERENT files within the same chunk diff.
        CommentRenderer renderer = newRenderer();
        String diff = gitSection("A.java", "@@ -1,1 +1,1 @@", "+aaa") + gitSection("B.java", "@@ -1,1 +1,1 @@", "+bbb");
        CommentRenderer.ChunkDiffIndex index = renderer.prepareChunkDiffIndex(diff);

        String renderedA = renderer.renderIndexed("A.java", 1, Severity.MAJOR, "issue in A", "", index);
        String renderedB = renderer.renderIndexed("B.java", 1, Severity.MAJOR, "issue in B", "", index);

        assertThat(renderedA).contains("issue in A").contains("+aaa");
        assertThat(renderedB).contains("issue in B").contains("+bbb");
    }

    @Test
    void prepareChunkDiffIndexOnANullChunkDiffYieldsNoDiffContextBlockJustLikeTheOldNullShortCircuit() {
        CommentRenderer renderer = newRenderer();

        String rendered = renderer.renderIndexed("A.java", 1, Severity.MAJOR, "comment", "",
                renderer.prepareChunkDiffIndex(null));

        assertThat(rendered).doesNotContain("````diff");
    }

    // ---- QA round (task item 2, "double truncation"): a field already truncated upstream by
    // StructuredResponseParser (SGB-03, marker appended) can ALSO get cut again downstream by
    // CommentRenderer's own independent gateway.publish.max-comment-length cap (SRO-53's pre-existing
    // block-dropping/assembleWithTruncatedProse fallback). Confirms the assembled body is still
    // fence-balanced and never contains an unpaired UTF-16 surrogate across a wide range of length
    // budgets -- brute-forced rather than a single hand-picked offset, since the exact byte where a
    // surrogate pair straddles the cut depends on header length, which this test does not hard-code.

    @Test
    void doubleTruncationNeverProducesAnUnpairedSurrogateOrUnbalancedFenceAcrossManyLengthBudgets() {
        GatewayProperties properties = new GatewayProperties();
        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        CommentRenderer renderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        String emoji = "😀"; // U+1F600, a two-char UTF-16 surrogate pair
        // Already truncated upstream (commentTruncatedUpstream=true) -- CommentRenderer appends
        // TRUNCATED_COMMENT_MARKER to this BEFORE its own maxCommentLength cap ever runs, so the marker
        // itself is part of what the second (downstream) cut can land on too.
        String rawComment = "x".repeat(40) + emoji + "y".repeat(40);

        for (int maxLength = 5; maxLength <= 140; maxLength++) {
            properties.getPublish().setMaxCommentLength(maxLength);

            String rendered = renderer.renderIndexed("A.java", 1, Severity.INFO, rawComment, true, "", false,
                    renderer.prepareChunkDiffIndex(null));

            assertThat(isEncodableUtf8(rendered))
                    .as("maxCommentLength=%d must never leave an unpaired surrogate in the published body", maxLength)
                    .isTrue();
            long fenceCount = countOccurrences(rendered, "````");
            assertThat(fenceCount % 2)
                    .as("maxCommentLength=%d: the Gateway's own four-backtick fence marker must still pair up", maxLength)
                    .isEqualTo(0);
        }
    }

    private boolean isEncodableUtf8(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
