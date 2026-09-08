package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffIntegrityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One test per rule (§4.2/WHR-12..21), per the plan's own test list. */
class DiffIntegrityVerifierTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MR_IID = 5L;
    private static final String BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private GitLabClient gitLabClient;
    private DiffIntegrityVerifier verifier;

    @BeforeEach
    void setUp() {
        gitLabClient = mock(GitLabClient.class);
        when(gitLabClient.fetchOverflowFlag(anyLong(), anyLong())).thenReturn(false);
        verifier = new DiffIntegrityVerifier(gitLabClient, new TextSanitizer(), new StructuredPathValidator(),
                new GatewayProperties());
    }

    private void stubCompare(boolean compareTimeout, GitLabClient.DiffEntry... entries) {
        when(gitLabClient.compareDiff(eq(PROJECT_ID), eq(BASE_SHA), eq(HEAD_SHA)))
                .thenReturn(new GitLabClient.CompareResult(compareTimeout, List.of(entries)));
    }

    private GitLabClient.DiffEntry entry(String oldPath, String newPath, String aMode, String bMode,
                                          boolean newFile, boolean deletedFile, boolean renamedFile, String diff) {
        return new GitLabClient.DiffEntry(oldPath, newPath, aMode, bMode, newFile, deletedFile, renamedFile, diff);
    }

    // ---- happy path ----

    @Test
    void acceptsAWellFormedModifiedFile() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-old\n+new\n"));

        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);

        assertThat(result).hasSize(1);
    }

    // ---- WHR-16: whole-MR signals ----

    @Test
    void rejectsWhenOverflowIsTrue() {
        when(gitLabClient.fetchOverflowFlag(PROJECT_ID, MR_IID)).thenReturn(true);

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void rejectsWhenCompareTimeoutIsTrue() {
        stubCompare(true, entry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    // ---- WHR-12: path validation ----

    @Test
    void rejectsAForgedDeltimiterInAPath() {
        stubCompare(false, entry("ok.java\ndiff --git a/safe.java b/safe.java", "ok.java\ndiff --git a/safe.java b/safe.java",
                "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT);
    }

    @Test
    void rejectsALeadingDashPath() {
        stubCompare(false, entry("-a.txt", "-a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class);
    }

    @Test
    void rejectsAMalformedMode() {
        stubCompare(false, entry("a.txt", "a.txt", "bad-mode", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT);
    }

    @Test
    void rejectsAPathIneligibleForStructuredOutputWhenStructured() {
        stubCompare(false, entry("a{b}.txt", "a{b}.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, true))
                .isInstanceOf(DiffIntegrityException.class);
        // v1/v2 (structured=false) is unaffected by the SOR-65 character-class rule.
        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);
        assertThat(result).hasSize(1);
    }

    // ---- WHR-13: line-prefix invariant ----

    @Test
    void rejectsAColumnZeroDelimiterLineInsideAHunk() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                "@@ -1,2 +1,2 @@\n-old\ndiff --git a/x b/x\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT);
    }

    // ---- WHR-14: hunk self-consistency (table-driven) ----

    @Test
    void acceptsOmittedHunkCountsDefaultingToOne() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-old\n+new\n"));

        assertThat(verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).hasSize(1);
    }

    @Test
    void acceptsNoNewlineMarkerCountingTowardNeitherSide() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                "@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file"));

        assertThat(verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).hasSize(1);
    }

    @Test
    void rejectsAnUnderCountedHunk() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                // declares 1 removed/1 added but actually has 2 removed lines
                "@@ -1 +1 @@\n-old1\n-old2\n+new\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void rejectsAGarbageHunkHeader() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false, "not a hunk header\n-x\n+y\n"));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void acceptsContextLinesCountingOnBothSides() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false,
                "@@ -1,3 +1,3 @@\n context\n-old\n+new\n context2\n"));

        assertThat(verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).hasSize(1);
    }

    // ---- WHR-15/15b: empty-diff exemptions ----

    @Test
    void rejectsEmptyDiffOnAModifiedFileWithEqualModes() {
        stubCompare(false, entry("a.txt", "a.txt", "100644", "100644", false, false, false, ""));

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void acceptsAModeOnlyChangeAndExcludesItFromCoverage() {
        stubCompare(false, entry("script.sh", "script.sh", "100644", "100755", false, false, false, ""));

        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);

        assertThat(result).isEmpty(); // excluded from coverage-bearing content (WHT-12)
    }

    @Test
    void acceptsABinaryMarkerFileAndExcludesItFromCoverage() {
        stubCompare(false, entry("img.png", "img.png", "100644", "100644", false, false, false,
                "Binary files a/img.png and b/img.png differ\n"));

        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);

        assertThat(result).isEmpty();
    }

    @Test
    void newFileWithEmptyDiffAndNonZeroHeadSizeIsRejectedAsTruncated() {
        stubCompare(false, entry("big.bin", "big.bin", null, "100644", true, false, false, ""));
        when(gitLabClient.headFileSize(PROJECT_ID, "big.bin", HEAD_SHA)).thenReturn(408_000L);

        assertThatThrownBy(() -> verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void newFileWithEmptyDiffAndZeroHeadSizeIsAcceptedAsGenuinelyEmpty() {
        stubCompare(false, entry("empty.txt", "empty.txt", null, "100644", true, false, false, ""));
        when(gitLabClient.headFileSize(PROJECT_ID, "empty.txt", HEAD_SHA)).thenReturn(0L);

        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);

        assertThat(result).hasSize(1);
    }

    @Test
    void deletedFileWithEmptyDiffChecksSizeAtBaseSha() {
        stubCompare(false, entry("gone.txt", "gone.txt", "100644", null, false, true, false, ""));
        when(gitLabClient.headFileSize(PROJECT_ID, "gone.txt", BASE_SHA)).thenReturn(0L);

        List<GitLabClient.DiffEntry> result = verifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false);

        assertThat(result).hasSize(1);
        verify(gitLabClient, never()).headFileSize(eq(PROJECT_ID), anyString(), eq(HEAD_SHA));
    }
}
