package com.review.worker.gateway.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FW-05 (SAST report, WSR-10 hardening): {@code toString()} on any record carrying {@code diff} or
 * {@code rawResponse} must never render the raw content, only its length -- a defense against a future
 * accidental {@code log.debug("{}", dto)} dumping proprietary source/model output into logs. Does not
 * affect JSON (de)serialization, which Jackson performs via accessors, not {@code toString()} (verified
 * separately below).
 */
class SensitiveDtoToStringMaskingTest {

    private static final String SECRET_DIFF = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
    private static final String SECRET_RAW_RESPONSE = "THE-SECRET-RAW-MODEL-RESPONSE-CONTENT";

    @Test
    void jobPayloadToStringNeverContainsTheRawDiff() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1");

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_DIFF.length()));
        assertThat(rendered).contains("v1");
    }

    @Test
    void jobPayloadAccessorStillReturnsTheFullDiffUnmasked() {
        // The masking is toString()-only; the actual field/accessor (what Jackson serializes) must be untouched.
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1");

        assertThat(payload.diff()).isEqualTo(SECRET_DIFF);
    }

    @Test
    void resultRequestToStringNeverContainsTheRawResponse() {
        ResultRequest request = new ResultRequest("worker-1", SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x");

        String rendered = request.toString();

        assertThat(rendered).doesNotContain(SECRET_RAW_RESPONSE);
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_RAW_RESPONSE.length()));
        // Non-sensitive fields must still be visible for debugging.
        assertThat(rendered).contains("worker-1");
        assertThat(rendered).contains("model-x");
    }

    @Test
    void resultRequestAccessorStillReturnsTheFullRawResponseUnmasked() {
        ResultRequest request = new ResultRequest("worker-1", SECRET_RAW_RESPONSE, 10, 20, 500L, "model-x");

        assertThat(request.rawResponse()).isEqualTo(SECRET_RAW_RESPONSE);
    }

    @Test
    void claimResponseToStringNeverContainsTheRawDiffEvenViaNestedPayload() {
        ClaimResponse response = new ClaimResponse(1L, 2L, new JobPayload(SECRET_DIFF, "v1"));

        String rendered = response.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).contains("jobId=1");
        assertThat(rendered).contains("reviewId=2");
    }

    @Test
    void toStringMaskingHandlesNullContentGracefully() {
        assertThat(new JobPayload(null, "v1").toString()).contains("0 chars");
        assertThat(new ResultRequest("w", null, null, null, null, null).toString()).contains("0 chars");
    }
}
