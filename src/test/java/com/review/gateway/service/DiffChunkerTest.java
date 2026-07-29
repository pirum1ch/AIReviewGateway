package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import org.junit.jupiter.api.Test;

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
        return new DiffChunker(properties, new DiffSizeValidator(properties));
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
}
