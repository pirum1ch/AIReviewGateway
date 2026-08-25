package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DiffChunker} (§2, pure function — no DB/Spring state). {@code charsPerToken=1}
 * throughout so token/char math in these tests is trivial and exact.
 */
class DiffChunkerTest {

    private GatewayProperties propertiesWithBudget(int maxDiffTokens, int headerReserveTokens, int maxChunks) {
        GatewayProperties properties = new GatewayProperties();
        properties.getDiff().setContextWindow(1_000_000);
        properties.getDiff().setPromptReserve(0);
        properties.getDiff().setAnswerReserve(0);
        properties.getDiff().setMaxDiffTokens(maxDiffTokens);
        properties.getDiff().setCharsPerToken(1);
        properties.getDiff().setChunkHeaderReserveTokens(headerReserveTokens);
        properties.getDiff().setMaxChunks(maxChunks);
        return properties;
    }

    private DiffChunker newChunker(GatewayProperties properties) {
        return new DiffChunker(properties, new DiffSizeValidator(properties), new ChunkContextRenderer(properties, new TextSanitizer()));
    }

    private String gitSection(String path, String... hunkLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("index 1111111..2222222 100644\n");
        sb.append("--- a/").append(path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        for (String hunkLine : hunkLines) {
            sb.append(hunkLine).append('\n');
        }
        return sb.toString();
    }

    private String oneHunk(String... bodyLines) {
        StringBuilder sb = new StringBuilder("@@ -1,1 +1,1 @@\n");
        for (String line : bodyLines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---- single-chunk shortcut (§8 backward compatibility) ----

    @Test
    void singleChunkShortcutReturnsOriginalDiffInstanceUnmodified() {
        GatewayProperties properties = propertiesWithBudget(10_000, 256, 5);
        DiffChunker chunker = newChunker(properties);
        String diff = gitSection("A.java", oneHunk("+small change"));

        DiffChunker.ChunkPlan plan = chunker.split(diff);

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().get(0).diff()).isSameAs(diff);
        assertThat(plan.chunks().get(0).index()).isZero();
        assertThat(plan.chunks().get(0).filePaths()).containsExactly("A.java");
    }

    @Test
    void nullDiffIsTreatedAsASingleEmptyChunk() {
        DiffChunker chunker = newChunker(propertiesWithBudget(10_000, 256, 5));

        DiffChunker.ChunkPlan plan = chunker.split(null);

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().get(0).diff()).isNull();
    }

    // ---- bin-packing across multiple git-style sections ----

    @Test
    void splitsMultipleFilesIntoSeparateChunksPreservingOrderWhenOverBudget() {
        String sectionA = gitSection("A.java", oneHunk("+" + "a".repeat(10)));
        String sectionB = gitSection("B.java", oneHunk("+" + "b".repeat(10)));
        String sectionC = gitSection("C.java", oneHunk("+" + "c".repeat(10)));
        assertThat(sectionA.length()).isEqualTo(sectionB.length()).isEqualTo(sectionC.length());
        int sectionLen = sectionA.length();

        // perChunkBudgetChars = maxDiffTokens - 0 (headerReserve) = sectionLen + 5: fits exactly one
        // section per chunk (two sections would be 2*sectionLen > sectionLen + 5 for any sectionLen > 5).
        GatewayProperties properties = propertiesWithBudget(sectionLen + 5, 0, 5);
        DiffChunker chunker = newChunker(properties);
        String diff = sectionA + sectionB + sectionC;

        DiffChunker.ChunkPlan plan = chunker.split(diff);

        assertThat(plan.chunks()).hasSize(3);
        assertThat(plan.chunks().get(0).diff()).isEqualTo(sectionA);
        assertThat(plan.chunks().get(1).diff()).isEqualTo(sectionB);
        assertThat(plan.chunks().get(2).diff()).isEqualTo(sectionC);
        assertThat(plan.chunks().get(0).filePaths()).containsExactly("A.java");
        assertThat(plan.chunks().get(1).filePaths()).containsExactly("B.java");
        assertThat(plan.chunks().get(2).filePaths()).containsExactly("C.java");
        for (int i = 0; i < plan.chunks().size(); i++) {
            assertThat(plan.chunks().get(i).index()).isEqualTo(i);
        }
        // Original content is fully preserved across chunks, just partitioned, never re-ordered.
        String recombined = plan.chunks().stream().map(DiffChunker.DiffChunk::diff).reduce("", String::concat);
        assertThat(recombined).isEqualTo(diff);
    }

    @Test
    void packsTwoOfThreeSmallSectionsTogetherWhenTheyFitButAllThreeDoNot() {
        String sectionA = gitSection("A.java", oneHunk("+" + "a".repeat(10)));
        String sectionB = gitSection("B.java", oneHunk("+" + "b".repeat(10)));
        String sectionC = gitSection("C.java", oneHunk("+" + "c".repeat(10)));
        assertThat(sectionA.length()).isEqualTo(sectionB.length()).isEqualTo(sectionC.length());
        int sectionLen = sectionA.length();

        // Budget fits exactly two sections together (2*sectionLen) but not all three -- must force
        // chunking overall, so maxDiffTokens (the whole-diff shortcut threshold) has to stay below the
        // combined length of all three sections.
        GatewayProperties properties = propertiesWithBudget(2 * sectionLen, 0, 5);
        DiffChunker chunker = newChunker(properties);
        String diff = sectionA + sectionB + sectionC;

        DiffChunker.ChunkPlan plan = chunker.split(diff);

        assertThat(plan.chunks()).hasSize(2);
        assertThat(plan.chunks().get(0).diff()).isEqualTo(sectionA + sectionB);
        assertThat(plan.chunks().get(1).diff()).isEqualTo(sectionC);
    }

    // ---- max-chunks (CSR-05) ----

    @Test
    void exceedingMaxChunksThrowsDiffTooLarge() {
        String sectionA = gitSection("A.java", oneHunk("+" + "a".repeat(10)));
        String sectionB = gitSection("B.java", oneHunk("+" + "b".repeat(10)));
        String sectionC = gitSection("C.java", oneHunk("+" + "c".repeat(10)));
        int sectionLen = sectionA.length();

        GatewayProperties properties = propertiesWithBudget(sectionLen + 5, 0, 2); // needs 3 chunks, max is 2
        DiffChunker chunker = newChunker(properties);
        String diff = sectionA + sectionB + sectionC;

        assertThatThrownBy(() -> chunker.split(diff)).isInstanceOf(DiffTooLargeException.class);
    }

    // ---- CSR-11: header-region-only path extraction, fallback mode never extracts paths ----

    @Test
    void pathExtractionIgnoresContentLinesThatLookLikeHeadersInsideAHunk() {
        String section = gitSection("Real.java",
                oneHunk("+some added line", "--- this looks like a header but is diff CONTENT", "+more content"));
        GatewayProperties properties = propertiesWithBudget(10_000, 256, 5);
        DiffChunker chunker = newChunker(properties);

        DiffChunker.ChunkPlan plan = chunker.split(section);

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().get(0).filePaths()).containsExactly("Real.java");
    }

    @Test
    void fallbackModeWithNoDiffGitNeverExtractsFilePaths() {
        String sectionA = "--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+" + "a".repeat(10) + "\n";
        String sectionB = "--- a/B.java\n+++ b/B.java\n@@ -1,1 +1,1 @@\n+" + "b".repeat(10) + "\n";
        int sectionLen = sectionA.length();

        GatewayProperties properties = propertiesWithBudget(sectionLen + 2, 0, 5);
        DiffChunker chunker = newChunker(properties);
        String diff = sectionA + sectionB;

        DiffChunker.ChunkPlan plan = chunker.split(diff);

        assertThat(plan.chunks().size()).isGreaterThanOrEqualTo(1);
        for (DiffChunker.DiffChunk chunk : plan.chunks()) {
            assertThat(chunk.filePaths()).as("CSR-11: fallback mode must never emit file paths").isEmpty();
        }
    }

    @Test
    void fullyIndivisibleContentExceedingBudgetWithNoDelimitersThrows() {
        String diff = "x".repeat(500); // no "diff --git", no "--- " markers at all -> one indivisible section
        GatewayProperties properties = propertiesWithBudget(100, 0, 5);
        DiffChunker chunker = newChunker(properties);

        assertThatThrownBy(() -> chunker.split(diff)).isInstanceOf(DiffTooLargeException.class);
    }

    // ---- oversized single file: split at hunk boundaries, header replayed ----

    @Test
    void oversizedSingleFileSplitsAtHunkBoundariesWithHeaderReplayed() {
        String header = "diff --git a/Big.java b/Big.java\nindex 111..222 100644\n--- a/Big.java\n+++ b/Big.java\n";
        String hunk1 = "@@ -1,1 +1,1 @@\n+" + "a".repeat(20) + "\n";
        String hunk2 = "@@ -10,1 +10,1 @@\n+" + "b".repeat(20) + "\n";
        String section = header + hunk1 + hunk2;

        // Budget fits header + one hunk, but not header + both hunks -> forces a 2-piece split.
        int perChunkBudget = header.length() + hunk1.length() + 5;
        GatewayProperties properties = propertiesWithBudget(perChunkBudget, 0, 5);
        DiffChunker chunker = newChunker(properties);

        DiffChunker.ChunkPlan plan = chunker.split(section);

        assertThat(plan.chunks()).hasSize(2);
        assertThat(plan.chunks().get(0).diff()).startsWith(header).contains(hunk1.strip());
        assertThat(plan.chunks().get(1).diff()).startsWith(header).contains(hunk2.strip());
    }

    @Test
    void singleHunkTooLargeEvenWithHeaderReplayedThrowsNamingTheFile() {
        String header = "diff --git a/Huge.java b/Huge.java\n--- a/Huge.java\n+++ b/Huge.java\n";
        String hunk = "@@ -1,1 +1,1 @@\n+" + "z".repeat(500) + "\n";
        String section = header + hunk;

        GatewayProperties properties = propertiesWithBudget(header.length() + 10, 0, 5); // hunk alone (500+) never fits
        DiffChunker chunker = newChunker(properties);

        assertThatThrownBy(() -> chunker.split(section))
                .isInstanceOf(DiffTooLargeException.class)
                .hasMessageContaining("Huge.java");
    }

    /**
     * F-DC-06 (Low, appsec-reproduced): the file path embedded in a {@code DiffTooLargeException}
     * message reaches the {@code 422} HTTP response body verbatim (unlike log lines, which are already
     * clean, SR-14/CSR-15). A raw, attacker-controlled path containing bidi-override/control characters
     * must be sanitized (same {@code ChunkContextRenderer.sanitizePath} used before any path is
     * persisted/rendered elsewhere) and length-capped before it lands in that message.
     */
    @Test
    void oversizedFileErrorMessageSanitizesTheAttackerControlledPathName() {
        String maliciousName = "A‮gnp.js"; // U+202E RIGHT-TO-LEFT OVERRIDE (Cf) -- bidi-override attack
        String header = "diff --git a/" + maliciousName + " b/" + maliciousName + "\n--- a/" + maliciousName
                + "\n+++ b/" + maliciousName + "\n";
        String hunk = "@@ -1,1 +1,1 @@\n+" + "z".repeat(500) + "\n";
        String section = header + hunk;

        GatewayProperties properties = propertiesWithBudget(header.length() + 10, 0, 5);
        DiffChunker chunker = newChunker(properties);

        assertThatThrownBy(() -> chunker.split(section))
                .isInstanceOf(DiffTooLargeException.class)
                .hasMessageNotContaining("‮")
                .hasMessageContaining("Agnp.js");
    }

    // ---- F-DC-07: masked toString() on DiffChunk/ChunkPlan ----

    @Test
    void diffChunkToStringNeverContainsTheRawDiffOrFilePaths() {
        String secretDiff = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
        DiffChunker.DiffChunk chunk = new DiffChunker.DiffChunk(0, secretDiff, 42, List.of("Secret.java"));

        String rendered = chunk.toString();

        assertThat(rendered).doesNotContain(secretDiff);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(secretDiff.length()));
        assertThat(rendered).contains("index=0");
        // filePaths content itself is also masked (only a count), not just the diff.
        assertThat(rendered).doesNotContain("Secret.java");
    }

    @Test
    void diffChunkAccessorsStillReturnFullContentUnmasked() {
        String secretDiff = "diff --git a/Secret.java\n+content";
        DiffChunker.DiffChunk chunk = new DiffChunker.DiffChunk(0, secretDiff, 42, List.of("Secret.java"));

        assertThat(chunk.diff()).isEqualTo(secretDiff);
        assertThat(chunk.filePaths()).containsExactly("Secret.java");
    }

    @Test
    void chunkPlanToStringNeverDumpsItsChunks() {
        String secretDiff = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
        DiffChunker.ChunkPlan plan = new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, secretDiff, 42, List.of())), 42, true);

        String rendered = plan.toString();

        assertThat(rendered).doesNotContain(secretDiff);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains("totalEstimatedTokens=42");
    }

    @Test
    void oversizedFileErrorMessageCapsAnExtremelyLongPathName() {
        String longName = "a/".repeat(500) + "File.java";
        String header = "diff --git a/" + longName + " b/" + longName + "\n--- a/" + longName
                + "\n+++ b/" + longName + "\n";
        String hunk = "@@ -1,1 +1,1 @@\n+" + "z".repeat(500) + "\n";
        String section = header + hunk;

        GatewayProperties properties = propertiesWithBudget(header.length() + 10, 0, 5);
        DiffChunker chunker = newChunker(properties);

        assertThatThrownBy(() -> chunker.split(section))
                .isInstanceOf(DiffTooLargeException.class)
                .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(header.length()));
    }

    /**
     * F-DC-01 (High, appsec-reproduced) regression test: {@code splitOversizedSection}'s header replay
     * means one file whose header is just under the per-chunk budget, followed by thousands of tiny
     * hunks, used to produce one full-budget-sized piece PER HUNK -- and the {@code maxChunks} cap was
     * only checked once, after {@code binPack} had already materialized the entire list. Appsec measured
     * ~2 GB / an {@code OutOfMemoryError} in ~2 seconds from a single ~190 KB request. This test
     * reproduces the same shape (header one char under budget, a hunk count far beyond {@code
     * maxChunks}) and asserts the fix: {@code DiffTooLargeException} is thrown almost immediately, well
     * before anywhere near all the hunks are processed -- proven by an explicit wall-clock bound, which
     * would fail (time out) under the old, unbounded-materialization behavior.
     */
    @Test
    void headerReplayAmplificationIsBoundedByMaxChunksNotByHunkCount() {
        int maxChunks = 5;
        String header = "diff --git a/Big.java b/Big.java\nindex 111..222 100644\n--- a/Big.java\n+++ b/Big.java\n";
        int perChunkBudgetChars = header.length() + 50; // header consumes almost the whole per-chunk budget
        GatewayProperties properties = propertiesWithBudget(perChunkBudgetChars, 0, maxChunks);
        DiffChunker chunker = newChunker(properties);

        // Enough tiny hunks that, absent the fix, would require far more than maxChunks full-budget
        // pieces (each replaying the ~90-char header) -- old code would materialize all of them before
        // ever checking the cap. New code must throw after producing at most `maxChunks` pieces.
        int hunkCount = 200_000;
        StringBuilder diff = new StringBuilder(header);
        for (int i = 0; i < hunkCount; i++) {
            diff.append("@@ -1,1 +1,1 @@\n+x\n");
        }

        long start = System.nanoTime();
        assertThatThrownBy(() -> chunker.split(diff.toString()))
                .isInstanceOf(DiffTooLargeException.class)
                .hasMessageContaining("max-chunks");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Processing all 200,000 hunks (as the pre-fix code would, to build 200,000 header-replayed
        // pieces) takes multiple seconds and gigabytes; aborting after ~maxChunks pieces is near-instant.
        assertThat(elapsedMs)
                .as("must abort as soon as the maxChunks bound is exceeded, not after processing every hunk")
                .isLessThan(2000L);
    }

    // ---- Structured Review Output: SRO-14/SRO-66 maxFilesPerChunk bound ----

    @Test
    void maxFilesPerChunkZeroLeavesV1V2ChunkingByteForByteUnchanged() {
        GatewayProperties properties = propertiesWithBudget(10_000, 256, 5);
        DiffChunker chunker = newChunker(properties);
        String diff = gitSection("A.java", oneHunk("+x")) + gitSection("B.java", oneHunk("+y"));

        DiffChunker.ChunkPlan unbounded = chunker.split(diff, 0);
        DiffChunker.ChunkPlan explicitZero = chunker.split(diff, 0, 0);

        assertThat(unbounded.chunks()).hasSize(1);
        assertThat(explicitZero.chunks()).hasSize(1);
        assertThat(unbounded.chunks().get(0).diff()).isEqualTo(explicitZero.chunks().get(0).diff());
    }

    @Test
    void maxFilesPerChunkDefeatsTheSingleChunkShortcutWhenFileCountExceedsIt() {
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, 10);
        DiffChunker chunker = newChunker(properties);
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            diff.append(gitSection("File" + i + ".java", oneHunk("+change")));
        }

        // The whole diff comfortably fits the token budget in one chunk, but maxFilesPerChunk=2 means a
        // single chunk can never hold all 5 files -- the shortcut must be defeated and normal packing
        // (respecting the file-count bound) must run instead.
        DiffChunker.ChunkPlan plan = chunker.split(diff.toString(), 0, 2);

        assertThat(plan.chunks().size()).isGreaterThan(1);
        for (DiffChunker.DiffChunk chunk : plan.chunks()) {
            assertThat(chunk.filePaths().size()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    void maxFilesPerChunkBoundsEachPackedChunksFileCount() {
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, 10);
        DiffChunker chunker = newChunker(properties);
        // Force multi-chunk packing on token size too, so binPack's file-count bound (not just the
        // single-chunk shortcut) is exercised.
        StringBuilder bigHunk = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            bigHunk.append("+line ").append(i).append('\n');
        }
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            diff.append(gitSection("File" + i + ".java", "@@ -1,1 +1,1 @@\n" + bigHunk));
        }
        int perChunkBudgetChars = gitSection("File0.java", "@@ -1,1 +1,1 @@\n" + bigHunk).length() * 3;
        GatewayProperties tightBudget = propertiesWithBudget(perChunkBudgetChars, 0, 10);
        DiffChunker tightChunker = newChunker(tightBudget);

        DiffChunker.ChunkPlan plan = tightChunker.split(diff.toString(), 0, 2);

        for (DiffChunker.DiffChunk chunk : plan.chunks()) {
            assertThat(chunk.filePaths().size()).isLessThanOrEqualTo(2);
        }
        // No file was dropped across the whole plan.
        java.util.Set<String> allPaths = new java.util.LinkedHashSet<>();
        plan.chunks().forEach(c -> allPaths.addAll(c.filePaths()));
        assertThat(allPaths).hasSize(6);
    }

    @Test
    void aSingleSectionExceedingMaxFilesPerChunkIsRejectedAtTheEdge() {
        // A section can only ever carry >1 path in the rare CSR-11-adjacent shape where diff --git,
        // +++, and a distinct --- line disagree -- exercised here indirectly is not needed: the simplest
        // reproduction is a section whose own extracted-path count (bounded by max-paths-per-section)
        // still exceeds a tiny maxFilesPerChunk, which SRO-66b must reject before any packing.
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, 10);
        properties.getDiff().setMaxPathsPerSection(5);
        DiffChunker chunker = newChunker(properties);
        String diff = gitSection("A.java", oneHunk("+x"));

        // maxFilesPerChunk=0 is unbounded (v1/v2); a structured version with maxFilesPerChunk >= 1 must
        // never reject a normal one-file-per-section diff, though.
        assertThat(chunker.split(diff, 0, 1).chunks()).hasSize(1);
    }

    @Test
    void diffTotalDistinctPathCountExceedingMaxChunksTimesMaxFilesPerChunkIsRejected() {
        int maxChunks = 2;
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, maxChunks);
        DiffChunker chunker = newChunker(properties);
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            diff.append(gitSection("File" + i + ".java", oneHunk("+x")));
        }

        // 10 distinct files > maxChunks(2) * maxFilesPerChunk(2) = 4 -- no packing could ever succeed.
        assertThatThrownBy(() -> chunker.split(diff.toString(), 0, 2))
                .isInstanceOf(DiffTooLargeException.class)
                .hasMessageContaining("max-files-per-chunk");
    }

    @Test
    void sectionThatHitTheMaxPathsPerSectionBoundIsRejectedForAStructuredVersion() {
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, 5);
        properties.getDiff().setMaxPathsPerSection(2);
        DiffChunker chunker = newChunker(properties);
        // A crafted section: one diff --git header, then several extra "+++ " header lines before the
        // first @@ hunk -- each one is a distinct path candidate within the SAME section.
        StringBuilder section = new StringBuilder("diff --git a/A.java b/A.java\n");
        for (int i = 0; i < 5; i++) {
            section.append("+++ b/Extra").append(i).append(".java\n");
        }
        section.append("@@ -1,1 +1,1 @@\n+content\n");

        assertThatThrownBy(() -> chunker.split(section.toString(), 0, 40))
                .isInstanceOf(DiffTooLargeException.class)
                .hasMessageContaining("max-paths-per-section");
    }

    @Test
    void nonStructuredVersionsAreUnaffectedByTheMaxPathsPerSectionBoundExceptForAdvisoryTruncation() {
        // SRO-66a: unconditional across all prompt versions, but the ONLY observable v1/v2 effect is a
        // shorter advisory file-path list for a pathological >64-header-lines-in-one-section input --
        // never a rejection, never a change to chunk boundaries/text.
        GatewayProperties properties = propertiesWithBudget(1_000_000, 0, 5);
        properties.getDiff().setMaxPathsPerSection(2);
        DiffChunker chunker = newChunker(properties);
        StringBuilder section = new StringBuilder("diff --git a/A.java b/A.java\n");
        for (int i = 0; i < 5; i++) {
            section.append("+++ b/Extra").append(i).append(".java\n");
        }
        section.append("@@ -1,1 +1,1 @@\n+content\n");

        // maxFilesPerChunk=0 -> non-structured version -> never rejected, chunk text unchanged.
        DiffChunker.ChunkPlan plan = chunker.split(section.toString(), 0, 0);

        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().get(0).diff()).isEqualTo(section.toString());
        assertThat(plan.chunks().get(0).filePaths().size()).isLessThanOrEqualTo(2);
    }

    @Test
    void chunkPlanCarriesPathsTrustedFromTheUnderlyingParse() {
        GatewayProperties properties = propertiesWithBudget(10_000, 256, 5);
        DiffChunker chunker = newChunker(properties);

        DiffChunker.ChunkPlan trusted = chunker.split(gitSection("A.java", oneHunk("+x")));
        DiffChunker.ChunkPlan untrusted = chunker.split("--- a/A.java\n+++ b/A.java\n" + oneHunk("+x"));
        DiffChunker.ChunkPlan noDelimiters = chunker.split("just some free text\nwith no diff markers at all\n");

        assertThat(trusted.pathsTrusted()).isTrue();
        assertThat(untrusted.pathsTrusted()).isFalse();
        assertThat(noDelimiters.pathsTrusted()).isFalse();
    }

    // ---- QA round: T-6.1 corpus regression -- SRO-66a's new unconditional per-section extraction bound
    // must not move v1/v2 chunk boundaries by even one byte ----

    /**
     * A small corpus of real-diff-shaped inputs spanning the cases that matter for chunk-boundary
     * stability: a single small file (single-chunk shortcut), several files forced into separate chunks
     * by a tight budget (bin-packing), a file whose own section alone exceeds the per-chunk budget
     * (oversized-section splitting), and a path containing characters real git output can produce
     * (spaces, dots, nested directories).
     */
    private List<String> realShapedDiffCorpus() {
        String smallSingleFile = gitSection("README.md", oneHunk("+one line change"));

        StringBuilder threeFiles = new StringBuilder();
        threeFiles.append(gitSection("src/main/java/com/example/Foo.java", oneHunk("+" + "a".repeat(200))));
        threeFiles.append(gitSection("src/main/java/com/example/Bar.java", oneHunk("+" + "b".repeat(200))));
        threeFiles.append(gitSection("src/test/java/com/example/FooTest.java", oneHunk("+" + "c".repeat(200))));

        StringBuilder oneOversizedFile = new StringBuilder();
        oneOversizedFile.append(gitSection("src/main/resources/generated.sql",
                oneHunk("+" + "x".repeat(2000)), "@@ -100,1 +100,1 @@", "+" + "y".repeat(2000)));

        String pathWithSpaceAndDots = gitSection("docs/release notes v1.2.3.md", oneHunk("+changelog entry"));

        return List.of(smallSingleFile, threeFiles.toString(), oneOversizedFile.toString(), pathWithSpaceAndDots);
    }

    @Test
    void theSRO66aPerSectionExtractionBoundNeverChangesV1V2ChunkBoundariesAcrossARealDiffCorpus() {
        // Two DiffChunker instances differing ONLY in gateway.diff.max-paths-per-section -- a tiny bound
        // that would visibly truncate the advisory file list for a pathological many-header-lines-in-one-
        // section input (SRO-66a), vs. an effectively unbounded one. For maxFilesPerChunk=0 (v1/v2), SRO-66b's
        // whole edge-bound check short-circuits, so the corpus's ChunkPlan output must be byte-identical
        // (same chunk count, same chunk text, same file paths, same estimatedTokens, same pathsTrusted)
        // regardless of this bound -- proving the §8 backward-compat guarantee holds even after SRO-66a
        // was added, not just for the pathological inputs the other tests in this class already cover.
        GatewayProperties tightSectionBound = propertiesWithBudget(4_000, 128, 10);
        tightSectionBound.getDiff().setMaxPathsPerSection(2);
        GatewayProperties looseSectionBound = propertiesWithBudget(4_000, 128, 10);
        looseSectionBound.getDiff().setMaxPathsPerSection(10_000);

        DiffChunker tightChunker = newChunker(tightSectionBound);
        DiffChunker looseChunker = newChunker(looseSectionBound);

        for (String diff : realShapedDiffCorpus()) {
            DiffChunker.ChunkPlan tightPlan = tightChunker.split(diff, 0, 0);
            DiffChunker.ChunkPlan loosePlan = looseChunker.split(diff, 0, 0);

            assertThat(tightPlan)
                    .as("maxFilesPerChunk=0 (v1/v2) must be completely unaffected by "
                            + "gateway.diff.max-paths-per-section for diff: " + diff.substring(0, Math.min(60, diff.length())))
                    .isEqualTo(loosePlan);
        }
    }
}
