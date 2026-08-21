package com.review.gateway.dto;

import com.review.gateway.model.ReviewPromptSection;
import com.review.gateway.model.enums.PromptSectionKind;
import com.review.gateway.model.enums.PromptSectionStatus;
import com.review.gateway.service.PromptAssembler;
import com.review.gateway.service.dto.ClaimedJob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSR-14: {@code dto.JobPayload} and {@code service.dto.ClaimedJob} previously had no masked {@code
 * toString()} at all (unlike the Worker's mirror DTOs, which already did) — both {@code diff} and the
 * new {@code chunkContext} field must never appear in a {@code toString()} rendering, only their
 * lengths, guarding against an accidental {@code log.debug("{}", payload)} dumping proprietary diff
 * content or MR-author-controlled file-path text into logs.
 *
 * <p>Prompt Manager (V3, PMR-25): the new {@code systemMessages} field must be masked identically —
 * counts + total chars only, never section content.
 */
class SensitiveDtoToStringMaskingTest {

    private static final String SECRET_DIFF = "diff --git a/Secret.java\n+String apiKey = \"THE-SECRET-DIFF-CONTENT\";";
    private static final String SECRET_CHUNK_CONTEXT = "part 2 of 6\nSECRET-FILE-PATH-CONTENT.java";
    private static final String SECRET_SYSTEM_MESSAGE_1 = "SECRET-CORPORATE-RULEBOOK-CONTENT";
    private static final String SECRET_SYSTEM_MESSAGE_2 = "SECRET-PROJECT-ARCHITECTURE-CONTENT";

    @Test
    void jobPayloadToStringNeverContainsTheRawDiffOrChunkContext() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT, null);

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
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT, null);

        assertThat(payload.diff()).isEqualTo(SECRET_DIFF);
        assertThat(payload.chunkContext()).isEqualTo(SECRET_CHUNK_CONTEXT);
    }

    @Test
    void jobPayloadToStringHandlesNullChunkContextGracefully() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v1", null, null);

        assertThat(payload.toString()).contains("chunkContext=<masked, 0 chars>");
    }

    @Test
    void jobPayloadToStringNeverContainsRawSystemMessages() {
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", null,
                List.of(SECRET_SYSTEM_MESSAGE_1, SECRET_SYSTEM_MESSAGE_2));

        String rendered = payload.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_1);
        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_2);
        assertThat(rendered).contains("systemMessages=<masked, 2 msg, "
                + (SECRET_SYSTEM_MESSAGE_1.length() + SECRET_SYSTEM_MESSAGE_2.length()) + " chars>");
    }

    @Test
    void jobPayloadToStringDistinguishesNullFromEmptySystemMessages() {
        // PMR-24: null (legacy/kill-switch-off) vs empty list must render distinguishably.
        JobPayload nullMessages = new JobPayload(SECRET_DIFF, "v1", null, null);
        JobPayload emptyMessages = new JobPayload(SECRET_DIFF, "v1", null, List.of());

        assertThat(nullMessages.toString()).contains("systemMessages=null");
        assertThat(emptyMessages.toString()).contains("systemMessages=<masked, 0 msg, 0 chars>");
    }

    @Test
    void jobPayloadAccessorStillReturnsSystemMessagesUnmasked() {
        List<String> messages = List.of(SECRET_SYSTEM_MESSAGE_1, SECRET_SYSTEM_MESSAGE_2);
        JobPayload payload = new JobPayload(SECRET_DIFF, "v2", null, messages);

        assertThat(payload.systemMessages()).isEqualTo(messages);
    }

    @Test
    void claimedJobToStringNeverContainsTheRawDiffOrChunkContext() {
        ClaimedJob claimedJob = new ClaimedJob(10L, 20L, SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT, null);

        String rendered = claimedJob.toString();

        assertThat(rendered).doesNotContain(SECRET_DIFF);
        assertThat(rendered).doesNotContain(SECRET_CHUNK_CONTEXT);
        assertThat(rendered).contains("jobId=10");
        assertThat(rendered).contains("reviewId=20");
        assertThat(rendered).contains("masked");
    }

    @Test
    void claimedJobAccessorsStillReturnFullContentUnmasked() {
        ClaimedJob claimedJob = new ClaimedJob(10L, 20L, SECRET_DIFF, "v2", SECRET_CHUNK_CONTEXT, null);

        assertThat(claimedJob.diff()).isEqualTo(SECRET_DIFF);
        assertThat(claimedJob.chunkContext()).isEqualTo(SECRET_CHUNK_CONTEXT);
    }

    @Test
    void claimedJobToStringNeverContainsRawSystemMessages() {
        ClaimedJob claimedJob = new ClaimedJob(10L, 20L, SECRET_DIFF, "v2", null,
                List.of(SECRET_SYSTEM_MESSAGE_1, SECRET_SYSTEM_MESSAGE_2));

        String rendered = claimedJob.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_1);
        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_2);
        assertThat(rendered).contains("masked, 2 msg");
    }

    // ---- ReviewPromptSection entity (PMR-25): same masking contract as the DTOs above ----

    @Test
    void reviewPromptSectionToStringNeverContainsRawContentEvenForAProjectSourcedSection() {
        ReviewPromptSection section = new ReviewPromptSection(1L, 2, PromptSectionKind.PROJECT_ARCHITECTURE,
                PromptSectionStatus.PRESENT, SECRET_SYSTEM_MESSAGE_1, "some/project", "path.md", "main",
                "a".repeat(40), "hash-prefix-abc123", 42);

        String rendered = section.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_1);
        assertThat(rendered).contains("masked, " + SECRET_SYSTEM_MESSAGE_1.length() + " chars");
        assertThat(rendered).contains("kind=PROJECT_ARCHITECTURE");
        assertThat(rendered).contains("status=PRESENT");
        // Provenance metadata (never content) is exactly what PMR-07/PMR-25 want reconstructible from logs.
        assertThat(rendered).contains("sourceProject=some/project");
        assertThat(rendered).contains("contentSha256=hash-prefix-abc123");
    }

    @Test
    void reviewPromptSectionAccessorStillReturnsRawContentUnmasked() {
        ReviewPromptSection section = new ReviewPromptSection(1L, 0, PromptSectionKind.CORPORATE_BASE,
                PromptSectionStatus.PRESENT, SECRET_SYSTEM_MESSAGE_2, "corp/repo", "base.md", "main",
                "b".repeat(40), "hash", 10);

        assertThat(section.getContent()).isEqualTo(SECRET_SYSTEM_MESSAGE_2);
    }

    // ---- F-PM-05: the pre-persistence carriers of the same content (appsec SAST round) ----

    /**
     * {@code PromptAssembler.SectionCandidate} holds the untrusted {@code PROJECT_*} body in its rawest
     * in-process form (post-sanitization, pre-delimiter-wrapping). It shipped with the default record
     * {@code toString()}, i.e. exactly the latent F-DC-07 leak channel that finding closed for
     * {@code DiffChunk}/{@code ChunkPlan}/{@code CreateReviewCommand}/{@code SubmitResultCommand}.
     */
    @Test
    void sectionCandidateToStringNeverContainsRawSectionContent() {
        PromptAssembler.SectionCandidate candidate = new PromptAssembler.SectionCandidate(
                PromptSectionKind.PROJECT_CODE_RULES, true, SECRET_SYSTEM_MESSAGE_1,
                "1042", ".ai-review/code-rules.md", "main", "b".repeat(40));

        String rendered = candidate.toString();

        assertThat(rendered).doesNotContain(SECRET_SYSTEM_MESSAGE_1);
        assertThat(rendered).contains("masked, " + SECRET_SYSTEM_MESSAGE_1.length() + " chars");
        assertThat(rendered).contains("kind=PROJECT_CODE_RULES");
        assertThat(rendered).contains("sourceCommit=" + "b".repeat(40));
    }

    /** Interpolation shapes must not fall back to a default rendering either (F-DC-07's own sub-check). */
    @Test
    void sectionCandidateMaskingSurvivesListAndFormatInterpolation() {
        PromptAssembler.SectionCandidate candidate = new PromptAssembler.SectionCandidate(
                PromptSectionKind.PROJECT_ARCHITECTURE, true, SECRET_SYSTEM_MESSAGE_2,
                "1042", ".ai-review/architecture.md", "main", "c".repeat(40));

        assertThat(java.util.List.of(candidate).toString()).doesNotContain(SECRET_SYSTEM_MESSAGE_2);
        assertThat(String.format("%s", candidate)).doesNotContain(SECRET_SYSTEM_MESSAGE_2);
        assertThat("" + candidate).doesNotContain(SECRET_SYSTEM_MESSAGE_2);
    }

    /** The accessor must still hand back the real text — masking is a rendering concern only. */
    @Test
    void sectionCandidateAccessorStillReturnsRawContentUnmasked() {
        PromptAssembler.SectionCandidate candidate = new PromptAssembler.SectionCandidate(
                PromptSectionKind.CORPORATE_BASE, true, SECRET_SYSTEM_MESSAGE_1,
                "corp/repo", "base.md", "main", "d".repeat(40));

        assertThat(candidate.sanitizedContent()).isEqualTo(SECRET_SYSTEM_MESSAGE_1);
    }
}
