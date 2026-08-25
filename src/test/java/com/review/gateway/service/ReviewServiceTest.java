package com.review.gateway.service;

import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private ReviewInputRepository reviewInputRepository;
    private ReviewChunkRepository reviewChunkRepository;
    private ReviewJobRepository reviewJobRepository;
    private ReviewCommentRepository reviewCommentRepository;
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    private DeduplicationService deduplicationService;
    private DiffSizeValidator diffSizeValidator;
    private DiffChunker diffChunker;
    private ChunkContextRenderer chunkContextRenderer;
    private PromptManager promptManager;
    private EventService eventService;
    private StateMachine stateMachine;
    private JobStateMachine jobStateMachine;
    private StructuredPathValidator structuredPathValidator;
    private com.review.gateway.config.GatewayProperties properties;
    private EntityManager entityManager;
    private PlatformTransactionManager transactionManager;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        reviewInputRepository = Mockito.mock(ReviewInputRepository.class);
        reviewChunkRepository = Mockito.mock(ReviewChunkRepository.class);
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        reviewPromptSectionRepository = Mockito.mock(ReviewPromptSectionRepository.class);
        deduplicationService = Mockito.mock(DeduplicationService.class);
        diffSizeValidator = Mockito.mock(DiffSizeValidator.class);
        diffChunker = Mockito.mock(DiffChunker.class);
        chunkContextRenderer = Mockito.mock(ChunkContextRenderer.class);
        promptManager = Mockito.mock(PromptManager.class);
        eventService = Mockito.mock(EventService.class);
        stateMachine = Mockito.mock(StateMachine.class);
        jobStateMachine = Mockito.mock(JobStateMachine.class);
        structuredPathValidator = new StructuredPathValidator();
        properties = new com.review.gateway.config.GatewayProperties();
        transactionManager = Mockito.mock(PlatformTransactionManager.class);
        entityManager = Mockito.mock(EntityManager.class);
        Query lockTimeoutQuery = Mockito.mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockTimeoutQuery);

        // Kill-switch off by default -- most tests don't care about Prompt Manager specifics.
        when(promptManager.resolve(any())).thenReturn(PromptManager.PromptResolution.none());
        // Single-chunk plan by default -- most tests don't care about chunking specifics.
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of())), 10, true));
        when(reviewJobRepository.findNonTerminalJobs(any())).thenReturn(List.of());
        when(reviewChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate TransactionTemplate.execute(...) by just running the callback synchronously,
        // as if the (fake) transaction always commits. This lets us unit-test ReviewService without
        // a real database while still exercising the exact REQUIRES_NEW code path.
        TransactionStatus fakeStatus = Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(fakeStatus);

        reviewService = new ReviewService(reviewRepository, reviewInputRepository, reviewChunkRepository,
                reviewJobRepository, reviewCommentRepository, reviewPromptSectionRepository, deduplicationService,
                diffSizeValidator, diffChunker, chunkContextRenderer, promptManager, eventService, stateMachine,
                jobStateMachine, structuredPathValidator, properties, entityManager, transactionManager);
    }

    private CreateReviewCommand command(String headSha) {
        return new CreateReviewCommand(1L, 2L, headSha, "base-sha", "diff content", "v1", 10);
    }

    @Test
    void diffTooLargeIsRejectedBeforeAnyPersistence() {
        doThrow(new DiffTooLargeException("too big")).when(diffSizeValidator).rejectIfAbsurdlyLarge("diff content");

        assertThatThrownBy(() -> reviewService.createReview(command("sha-1")))
                .isInstanceOf(DiffTooLargeException.class);

        verify(reviewRepository, never()).findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any());
        verify(deduplicationService, never()).findActiveReview(any(), any(), any());
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void dedupReturnsExistingReviewIdWithoutCreatingANewOne() {
        Review existing = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        existing.setStatus(ReviewStatus.RUNNING);
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-1")).thenReturn(Optional.of(existing));

        CreateReviewResult result = reviewService.createReview(command("sha-1"));

        assertThat(result.deduplicated()).isTrue();
        assertThat(result.reviewId()).isEqualTo(existing.getId());
        assertThat(result.status()).isEqualTo(ReviewStatus.RUNNING);
        // sweepObsolete now always opens its own small transaction (the CSR-18 locking query requires
        // an already-active transaction), even when it finds nothing to obsolete -- so the meaningful
        // assertion here is that no new Review row is ever actually inserted, not that zero
        // transactions were opened at all.
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void newHeadShaObsoletesPriorNonPublishedReviewsOfTheSameMr() {
        Review stale = ReviewTestSupport.withId(new Review(1L, 2L, "sha-old", "base-sha", "v1", 10), 100L);
        stale.setStatus(ReviewStatus.QUEUED);
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(
                eq(1L), eq(2L), eq("sha-new"), eq(DeduplicationService.OBSOLETABLE_STATUSES)))
                .thenReturn(List.of(stale));
        // F-DC-03: sweepObsolete now locks each candidate individually (FOR NO KEY UPDATE) after the
        // unlocked candidate read above, re-checking it's still obsoletable before acting on it.
        when(reviewRepository.findByIdForNoKeyUpdate(100L)).thenReturn(Optional.of(stale));
        when(deduplicationService.findActiveReview(1L, 2L, "sha-new")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 200L));

        reviewService.createReview(command("sha-new"));

        verify(stateMachine).transition(eq(stale), eq(ReviewStatus.OBSOLETE), eq(EventType.OBSOLETE), any());
        verify(reviewRepository).save(stale);
    }

    @Test
    void createsANewReviewWhenNoActiveDuplicateExists() {
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-1")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 300L));
        when(diffSizeValidator.estimateTokens("diff content")).thenReturn(42);

        CreateReviewResult result = reviewService.createReview(command("sha-1"));

        assertThat(result.deduplicated()).isFalse();
        verify(reviewInputRepository).save(any(ReviewInput.class));
        verify(stateMachine).transition(any(Review.class), eq(ReviewStatus.QUEUED), eq(EventType.CREATED), any());
    }

    // ---- PMR-10: the kill-switch being off must be traceable per-Review, not just at startup ----

    @Test
    void killSwitchOffRecordsPromptDisabledEventOnEveryCreatedReview() {
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-1")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 301L));
        when(diffSizeValidator.estimateTokens("diff content")).thenReturn(42);
        // promptManager.resolve(...) is stubbed to PromptResolution.none() in setUp -- the kill-switch-off case.

        reviewService.createReview(command("sha-1"));

        verify(eventService).record(eq(301L), eq(EventType.PROMPT_DISABLED), isNull(), isNull(), anyString());
    }

    @Test
    void repoModeSuccessfulResolutionNeverRecordsPromptDisabledOrSectionMissingEvents() {
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-1")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 302L));
        when(diffSizeValidator.estimateTokens("diff content")).thenReturn(42);
        when(promptManager.resolve(1L)).thenReturn(new PromptManager.PromptResolution(
                com.review.gateway.model.enums.PromptBundleMode.REPO, List.of(), 10, false, List.of()));

        reviewService.createReview(command("sha-1"));

        verify(eventService, never()).record(any(), eq(EventType.PROMPT_DISABLED), any(), any(), anyString());
        verify(eventService, never()).record(any(), eq(EventType.PROMPT_SECTION_MISSING), any(), any(), anyString());
    }

    @Test
    void raceOnInsertReReadsAndReturnsTheWinner() {
        Review winner = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        winner.setStatus(ReviewStatus.QUEUED);
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-1"))
                .thenReturn(Optional.empty(), Optional.of(winner)); // first call: not found; second (post-race): found
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        CreateReviewResult result = reviewService.createReview(command("sha-1"));

        assertThat(result.deduplicated()).isTrue();
        assertThat(result.reviewId()).isEqualTo(winner.getId());
        verify(deduplicationService, times(2)).findActiveReview(1L, 2L, "sha-1");
    }

    @Test
    void getStatusReturnsAViewWithCommentCount() {
        Review review = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        review.setStatus(ReviewStatus.COMPLETED);
        when(reviewRepository.findById(any())).thenReturn(Optional.of(review));
        when(reviewCommentRepository.countByReviewId(any())).thenReturn(5L);

        ReviewStatusView view = reviewService.getStatus(1L);

        assertThat(view.status()).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(view.commentCount()).isEqualTo(5L);
    }

    @Test
    void getStatusThrowsWhenReviewIsMissing() {
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.getStatus(99L)).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void cancelTransitionsACancellableReview() {
        Review review = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        when(reviewRepository.findByIdForNoKeyUpdate(any())).thenReturn(Optional.of(review));
        when(reviewCommentRepository.countByReviewId(any())).thenReturn(0L);

        reviewService.cancel(1L);

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.CANCELLED), eq(EventType.CANCELLED), any());
    }

    // ---- Structured Review Output: promptVersion allowlist (threat model SOR-08, CRITICAL) ----

    @Test
    void v1AndV2RemainAllowedByTheShippedDefaultAllowlist() {
        assertThat(properties.getReview().getAllowedPromptVersions()).containsExactlyInAnyOrder("v1", "v2");
    }

    @Test
    void aV1RequestIsNotRejectedByTheAllowlist() {
        // "createsANewReviewWhenNoActiveDuplicateExists" already exercises the full v1 happy path end to
        // end (including the allowlist check, since it is the very first thing createReview does); this
        // test isolates that the allowlist itself does not throw for the default-allowed "v1".
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-v1")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 400L));

        CreateReviewResult result = reviewService.createReview(command("sha-v1"));

        assertThat(result.deduplicated()).isFalse();
    }

    @Test
    void v3IsNotInTheShippedDefaultAllowlistAndIsRejectedAtTheEdge() {
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-v3", "base-sha", "diff content", "v3", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
        verify(diffChunker, never()).split(any(), anyInt(), anyInt());
    }

    @Test
    void anUnknownPromptVersionIsRejectedAtTheEdge() {
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-v99", "base-sha", "diff content", "v99", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void anUnknownPromptVersionNeverEchoesTheRawValueButNamesTheAllowlist() {
        // F-SRO-09: the 422 STRUCTURED_OUTPUT_UNSUPPORTED body must never reflect caller-controlled text
        // back into the response -- this exercises the ReviewService throw site directly (bypassing the
        // CreateReviewRequest DTO's edge-level @Pattern, exactly like a future caller of this service
        // method that isn't the REST controller would), so the discipline must hold here too, not only
        // via the DTO-level validation.
        String maliciousMarker = "INJECTED-MARKER-<script>alert(1)</script>";
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-injection", "base-sha", "diff content",
                maliciousMarker, 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class)
                .hasMessageNotContaining(maliciousMarker)
                .hasMessageNotContaining("INJECTED-MARKER")
                .hasMessageContaining("gateway.review.allowed-prompt-versions");

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    // ---- Structured Review Output: edge validation once v3 is allowlisted (SRO-16/17/65) ----

    @Test
    void structuredVersionWithUntrustedPathsIsRejected() {
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of())), 10, false));
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-untrusted", "base-sha", "diff content", "v3", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class)
                .hasMessageContaining("diff --git");

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void structuredVersionWithAnIneligiblePathIsRejected() {
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of("a`.java"))), 10, true));
        when(chunkContextRenderer.sanitizePath("a`.java")).thenReturn("a`.java");
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-ineligible", "base-sha", "diff content", "v3", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void structuredVersionWithADroppedPathIsRejected() {
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of("A.java"))), 10, true));
        // sanitizePath returns null -- the whole path was stripped away (control/format characters only).
        when(chunkContextRenderer.sanitizePath("A.java")).thenReturn(null);
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-dropped", "base-sha", "diff content", "v3", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void structuredVersionWithTwoPathsThatCollideAfterSanitizationIsRejected() {
        // F-SRO-02: "src/A.java" and "src/A<>.java" are two distinct raw paths, but sanitizePath strips
        // '<'/'>' so both map to "src/A.java". The pre-fix check only compared list SIZES (2 raw -> 2
        // sanitized), which is unchanged here and would have let this slip through as a silent duplicate
        // key in review_chunks.file_paths.
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of("src/A.java", "src/A<>.java"))),
                10, true));
        when(chunkContextRenderer.sanitizePath("src/A.java")).thenReturn("src/A.java");
        when(chunkContextRenderer.sanitizePath("src/A<>.java")).thenReturn("src/A.java");
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-collision", "base-sha", "diff content", "v3", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(com.review.gateway.exception.StructuredOutputUnsupportedException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void nonStructuredVersionAssertsPromptFitsWithTheDiffAnswerReserve() {
        // F-SRO-03: assertPromptFits must be called with the version-appropriate answer reserve --
        // gateway.diff.answer-reserve for v1/v2, computed once and threaded through, not silently
        // defaulted inside DiffSizeValidator.
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-v1")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 500L));

        reviewService.createReview(command("sha-v1"));

        verify(diffSizeValidator).assertPromptFits(anyInt(), eq(properties.getDiff().getAnswerReserve()));
    }

    @Test
    void structuredVersionAssertsPromptFitsWithTheStructuredAnswerReserve() {
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of("src/A.java"))), 10, true));
        when(chunkContextRenderer.sanitizePath("src/A.java")).thenReturn("src/A.java");
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-v3")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 501L));
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-v3", "base-sha", "diff content", "v3", 10);

        reviewService.createReview(command);

        verify(diffSizeValidator).assertPromptFits(anyInt(), eq(properties.getStructured().getAnswerReserve()));
    }

    @Test
    void structuredVersionWithEligibleTrustedPathsPassesEdgeValidation() {
        properties.getReview().getAllowedPromptVersions().add("v3");
        when(diffChunker.split(anyString(), anyInt(), anyInt())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of("src/A.java"))), 10, true));
        when(chunkContextRenderer.sanitizePath("src/A.java")).thenReturn("src/A.java");
        when(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(deduplicationService.findActiveReview(1L, 2L, "sha-eligible")).thenReturn(Optional.empty());
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenAnswer(inv -> ReviewTestSupport.withId(inv.getArgument(0), 401L));
        CreateReviewCommand command = new CreateReviewCommand(1L, 2L, "sha-eligible", "base-sha", "diff content", "v3", 10);

        CreateReviewResult result = reviewService.createReview(command);

        assertThat(result.deduplicated()).isFalse();
        verify(reviewRepository).saveAndFlush(any());
    }
}
