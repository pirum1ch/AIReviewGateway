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
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private DeduplicationService deduplicationService;
    private DiffSizeValidator diffSizeValidator;
    private DiffChunker diffChunker;
    private ChunkContextRenderer chunkContextRenderer;
    private StateMachine stateMachine;
    private JobStateMachine jobStateMachine;
    private PlatformTransactionManager transactionManager;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        reviewInputRepository = Mockito.mock(ReviewInputRepository.class);
        reviewChunkRepository = Mockito.mock(ReviewChunkRepository.class);
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        deduplicationService = Mockito.mock(DeduplicationService.class);
        diffSizeValidator = Mockito.mock(DiffSizeValidator.class);
        diffChunker = Mockito.mock(DiffChunker.class);
        chunkContextRenderer = Mockito.mock(ChunkContextRenderer.class);
        stateMachine = Mockito.mock(StateMachine.class);
        jobStateMachine = Mockito.mock(JobStateMachine.class);
        transactionManager = Mockito.mock(PlatformTransactionManager.class);

        // Single-chunk plan by default -- most tests don't care about chunking specifics.
        when(diffChunker.split(anyString())).thenAnswer(inv -> new DiffChunker.ChunkPlan(
                List.of(new DiffChunker.DiffChunk(0, inv.getArgument(0), 10, List.of())), 10));
        when(reviewJobRepository.findNonTerminalJobs(any())).thenReturn(List.of());
        when(reviewChunkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Simulate TransactionTemplate.execute(...) by just running the callback synchronously,
        // as if the (fake) transaction always commits. This lets us unit-test ReviewService without
        // a real database while still exercising the exact REQUIRES_NEW code path.
        TransactionStatus fakeStatus = Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(fakeStatus);

        reviewService = new ReviewService(reviewRepository, reviewInputRepository, reviewChunkRepository,
                reviewJobRepository, reviewCommentRepository, deduplicationService, diffSizeValidator,
                diffChunker, chunkContextRenderer, stateMachine, jobStateMachine, transactionManager);
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
        when(reviewRepository.findByIdForUpdate(any())).thenReturn(Optional.of(review));
        when(reviewCommentRepository.countByReviewId(any())).thenReturn(0L);

        reviewService.cancel(1L);

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.CANCELLED), eq(EventType.CANCELLED), any());
    }
}
