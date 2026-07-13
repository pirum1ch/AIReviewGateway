package com.review.gateway.service;

import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fills a coverage gap in {@code ReviewServiceTest}: admin cancel on an already-terminal Review
 * (architecture §4 — {@code PUBLISHED}/{@code FAILED}/{@code CANCELLED}/{@code OBSOLETE} have no
 * outgoing transitions) must be rejected, not silently accepted, and must not mutate state or emit
 * an event. {@link ReviewServiceTest} only exercises the cancellable path.
 */
class ReviewServiceAdditionalTest {

    private ReviewRepository reviewRepository;
    private ReviewInputRepository reviewInputRepository;
    private ReviewCommentRepository reviewCommentRepository;
    private DeduplicationService deduplicationService;
    private DiffSizeValidator diffSizeValidator;
    private StateMachine stateMachine;
    private PlatformTransactionManager transactionManager;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        reviewInputRepository = Mockito.mock(ReviewInputRepository.class);
        reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        deduplicationService = Mockito.mock(DeduplicationService.class);
        diffSizeValidator = Mockito.mock(DiffSizeValidator.class);
        stateMachine = Mockito.mock(StateMachine.class);
        transactionManager = Mockito.mock(PlatformTransactionManager.class);

        TransactionStatus fakeStatus = Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(fakeStatus);

        reviewService = new ReviewService(reviewRepository, reviewInputRepository, reviewCommentRepository,
                deduplicationService, diffSizeValidator, stateMachine, transactionManager);
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"PUBLISHED", "FAILED", "CANCELLED", "OBSOLETE"})
    void cancelOnATerminalReviewIsRejectedWithoutMutatingStateOrEmittingAnEvent(ReviewStatus terminalStatus) {
        Review review = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        review.setStatus(terminalStatus);
        when(reviewRepository.findById(5L)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.cancel(5L))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(review.getStatus()).isEqualTo(terminalStatus);
        verify(stateMachine, never()).transition(any(), any(), any(), any());
        verify(reviewRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"NEW", "QUEUED", "RUNNING", "COMPLETED"})
    void cancelOnANonTerminalReviewIsAccepted(ReviewStatus cancellableStatus) {
        Review review = new Review(1L, 2L, "sha-1", "base-sha", "v1", 10);
        review.setStatus(cancellableStatus);
        when(reviewRepository.findById(6L)).thenReturn(Optional.of(review));
        when(reviewCommentRepository.countByReviewId(6L)).thenReturn(0L);

        reviewService.cancel(6L);

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.CANCELLED), any(), any());
        verify(reviewRepository).save(review);
    }
}
