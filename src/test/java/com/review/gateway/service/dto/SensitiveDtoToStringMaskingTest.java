package com.review.gateway.service.dto;

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
        SubmitResultCommand command = new SubmitResultCommand(SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x", null);

        String rendered = command.toString();

        assertThat(rendered).doesNotContain(SECRET_RAW_RESPONSE);
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_RAW_RESPONSE.length()));
        assertThat(rendered).contains("model-x");
    }

    @Test
    void submitResultCommandAccessorStillReturnsTheFullRawResponseUnmasked() {
        SubmitResultCommand command = new SubmitResultCommand(SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x", null);

        assertThat(command.rawResponse()).isEqualTo(SECRET_RAW_RESPONSE);
    }

    @Test
    void toStringMaskingHandlesNullContentGracefully() {
        assertThat(new CreateReviewCommand(1L, 2L, "sha", "base", null, "v1", 10).toString()).contains("0 chars");
        assertThat(new SubmitResultCommand(null, null, null, null, null, null).toString()).contains("0 chars");
    }

    // ---- F-SRO-08(b): finishReason must never render CR/LF (or any other non-alphanumeric byte) raw ----

    @Test
    void submitResultCommandToStringNeverContainsRawCrLfFromFinishReason() {
        String maliciousFinishReason = "stop\r\nINJECTED LOG LINE: fake event";
        SubmitResultCommand command = new SubmitResultCommand("raw", 1, 2, 3L, "model-x", maliciousFinishReason);

        String rendered = command.toString();

        assertThat(rendered).doesNotContain("\r").doesNotContain("\n");
        assertThat(rendered).doesNotContain("INJECTED LOG LINE");
    }

    @Test
    void submitResultCommandToStringPreservesALegitimateFinishReasonValue() {
        SubmitResultCommand command = new SubmitResultCommand("raw", 1, 2, 3L, "model-x", "length");

        assertThat(command.toString()).contains("finishReason=length");
    }

    @Test
    void submitResultCommandAccessorStillReturnsTheRawFinishReasonUnmasked() {
        String maliciousFinishReason = "stop\r\nINJECTED";
        SubmitResultCommand command = new SubmitResultCommand("raw", 1, 2, 3L, "model-x", maliciousFinishReason);

        assertThat(command.finishReason()).isEqualTo(maliciousFinishReason);
    }
}
