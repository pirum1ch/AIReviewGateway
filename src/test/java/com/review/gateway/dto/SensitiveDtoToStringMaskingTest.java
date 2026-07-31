package com.review.gateway.dto;

import com.review.gateway.service.dto.ClaimedJob;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSR-14: {@code dto.JobPayload} and {@code service.dto.ClaimedJob} previously had no masked {@code
 * toString()} at all (unlike the Worker's mirror DTOs, which already did) — both {@code diff} and the
 * new {@code chunkContext} field must never appear in a {@code toString()} rendering, only their
 * lengths, guarding against an accidental {@code log.debug("{}", payload)} dumping proprietary diff
 * content or MR-author-controlled file-path text into logs.
 */
class SensitiveDtoToStringMaskingTest {

    private static final String SECRET_DIFF = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
    private static final String SECRET_CHUNK_CONTEXT = "part 2 of 6\nSECRET-FILE-PATH-CONTENT.java";

    @Test
    void jobPayloadToStringNeverContainsTheRawDiffOrChunkContext() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT);

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain("THE-SECRET-DIFF-CONTENT");
        assertThat(rendered).doesNotContain(SECRET_CHUNK_CONTEXT);
        assertThat(rendered).doesNotContain("SECRET-FILE-PATH-CONTENT");
        assertThat(rendered).contains("masked");
        assertThat(rendered).contains(String.valueOf(SECRET_DIFF.length()));
        assertThat(rendered).contains(String.valueOf(SECRET_CHUNK_CONTEXT.length()));
        assertThat(rendered).contains("v2");
    }

    @Test
    void jobPayloadAccessorsStillReturnFullContentUnmasked() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT);

        assertThat(payload.diff()).isEqualTo(SECRET_DIFF);
        assertThat(payload.chunkContext()).isEqualTo(SECRET_CHUNK_CONTEXT);
    }

    @Test
    void jobPayloadToStringHandlesNullChunkContextGracefully() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1", null);

        assertThat(payload.toString()).contains("chunkContext=<masked, 0 chars>");
    }

    @Test
    void claimedJobToStringNeverContainsTheRawDiffOrChunkContext() {
        ClaimedJob claimedJob = new ClaimedJob(10L, 20L, SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT);

        String rendered = claimedJob.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain(SECRET_CHUNK_CONTEXT);
        assertThat(rendered).contains("jobId=10");
        assertThat(rendered).contains("reviewId=20");
        assertThat(rendered).contains("masked");
    }

    @Test
    void claimedJobAccessorsStillReturnFullContentUnmasked() {
        ClaimedJob claimedJob = new ClaimedJob(10L, 20L, SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT);

        assertThat(claimedJob.diff()).isEqualTo(SECRET_DIFF);
        assertThat(claimedJob.chunkContext()).isEqualTo(SECRET_CHUNK_CONTEXT);
    }
}
