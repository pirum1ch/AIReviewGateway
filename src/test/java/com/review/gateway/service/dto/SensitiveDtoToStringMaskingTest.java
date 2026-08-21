package com.review.gateway.service.dto;

import com.review.gateway.service.DiffPositionResolver.PathLine;
import com.review.gateway.service.DiffPositionResolver.ResolvedLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-DC-07 (Info, appsec-found): {@code CreateReviewCommand} (full diff) and {@code
 * SubmitResultCommand} (full raw LLM response) had no masked {@code toString()} — latent (no current
 * call site logs either record), but fixed here consistent with the CSR-14 pattern already applied to
 * {@code dto.JobPayload}/{@code service.dto.ClaimedJob} elsewhere in this feature.
 */
class SensitiveDtoToStringMaskingTest {

    private static final String SECRET_DIFF = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
    private static final String SECRET_RAW_RESPONSE = "THE-SECRET-RAW-MODEL-RESPONSE-CONTENT";

    @Test
    void createReviewCommandToStringNeverContainsTheRawDiff() {
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha", "base", SECRET_DIFF, "v1", 10);

        String rendered = command.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_DIFF.length()));
        assertThat(rendered).contains("sha").contains("v1");
    }

    @Test
    void createReviewCommandAccessorStillReturnsTheFullDiffUnmasked() {
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha", "base", SECRET_DIFF, "v1", 10);

        assertThat(command.diff()).isEqualTo(SECRET_DIFF);
    }

    @Test
    void submitResultCommandToStringNeverContainsTheRawResponse() {
        SubmitResultCommand command = new SubmitResultCommand(SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x");

        String rendered = command.toString();

        assertThat(rendered).doesNotContain(SECRET_RAW_RESPONSE);
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_RAW_RESPONSE.length()));
        assertThat(rendered).contains("model-x");
    }

    @Test
    void submitResultCommandAccessorStillReturnsTheFullRawResponseUnmasked() {
        SubmitResultCommand command = new SubmitResultCommand(SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x");

        assertThat(command.rawResponse()).isEqualTo(SECRET_RAW_RESPONSE);
    }

    @Test
    void toStringMaskingHandlesNullContentGracefully() {
        assertThat(new CreateReviewCommand(1L, 2L, "sha", "base", null, "v1", 10).toString()).contains("0 chars");
        assertThat(new SubmitResultCommand(null, null, null, null, null).toString()).contains("0 chars");
    }

    // ---- Diff Position Anchoring (DPR-15, SHOULD): ResolvedLine / DiffPosition / DiffRefs masking ----

    private static final String SECRET_PATH = "src/main/java/com/example/Secret.java";
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);

    @Test
    void pathLineToStringNeverContainsTheRawLlmSuppliedPath() {
        PathLine pathLine = new PathLine(SECRET_PATH, 42);

        String rendered = pathLine.toString();

        assertThat(rendered).doesNotContain(SECRET_PATH);
        assertThat(rendered).contains("masked").contains("line=42");
    }

    @Test
    void pathLineAccessorStillReturnsTheRawPathUnmasked() {
        PathLine pathLine = new PathLine(SECRET_PATH, 42);

        assertThat(pathLine.path()).isEqualTo(SECRET_PATH);
    }

    @Test
    void resolvedLineToStringNeverContainsRawPaths() {
        ResolvedLine resolvedLine = new ResolvedLine(SECRET_PATH, SECRET_PATH + "-new", 10, 11);

        String rendered = resolvedLine.toString();

        assertThat(rendered).doesNotContain(SECRET_PATH);
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains("oldLine=10").contains("newLine=11");
    }

    @Test
    void resolvedLineAccessorsStillReturnRawPathsUnmasked() {
        ResolvedLine resolvedLine = new ResolvedLine(SECRET_PATH, SECRET_PATH + "-new", 10, 11);

        assertThat(resolvedLine.oldPath()).isEqualTo(SECRET_PATH);
        assertThat(resolvedLine.newPath()).isEqualTo(SECRET_PATH + "-new");
    }

    @Test
    void diffPositionToStringNeverContainsRawPathsOrFullShas() {
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, SECRET_PATH, SECRET_PATH, 10, 11);

        String rendered = position.toString();

        assertThat(rendered).doesNotContain(SECRET_PATH);
        assertThat(rendered).doesNotContain(SHA_A).doesNotContain(SHA_B).doesNotContain(SHA_C);
        assertThat(rendered).contains("masked");
    }

    @Test
    void diffPositionAccessorsStillReturnUnmaskedValues() {
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, SECRET_PATH, SECRET_PATH, 10, 11);

        assertThat(position.oldPath()).isEqualTo(SECRET_PATH);
        assertThat(position.baseSha()).isEqualTo(SHA_A);
    }

    @Test
    void diffRefsToStringNeverContainsFullShas() {
        DiffRefs refs = new DiffRefs(SHA_A, SHA_B, SHA_C);

        String rendered = refs.toString();

        assertThat(rendered).doesNotContain(SHA_A).doesNotContain(SHA_B).doesNotContain(SHA_C);
        assertThat(rendered).contains(SHA_A.substring(0, 7));
    }

    @Test
    void diffRefsAccessorsStillReturnFullShasUnmasked() {
        DiffRefs refs = new DiffRefs(SHA_A, SHA_B, SHA_C);

        assertThat(refs.headSha()).isEqualTo(SHA_C);
    }

    @Test
    void diffPositionAndDiffRefsMaskingSurvivesListAndFormatInterpolation() {
        // WOR-17-class trap: a Map/Collection in an slf4j placeholder bypasses per-record masking.
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, SECRET_PATH, SECRET_PATH, 10, 11);

        assertThat(java.util.List.of(position).toString()).doesNotContain(SECRET_PATH).doesNotContain(SHA_A);
        assertThat(String.format("%s", position)).doesNotContain(SECRET_PATH);
        assertThat("" + position).doesNotContain(SECRET_PATH);
    }
}
