package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryManagerTest {

    private ReviewRepository reviewRepository;
    private ReviewJobRepository reviewJobRepository;
    private StateMachine stateMachine;
    private GatewayProperties properties;
    private RetryManager retryManager;

    @BeforeEach
    void setUp() {
        reviewRepository = Mockito.mock(ReviewRepository.class);
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        stateMachine = Mockito.mock(StateMachine.class);
        properties = new GatewayProperties();
        properties.getRetry().setMaxAttempts(3);
        retryManager = new RetryManager(reviewRepository, reviewJobRepository, stateMachine, properties);
    }

    private Review runningReviewWithAttempts(int attempts) {
        Review review = new Review(1L, 2L, "sha", "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(attempts);
        return review;
    }

    @Test
    void belowMaxAttemptsRequeues() {
        Review review = runningReviewWithAttempts(2); // 2 < 3 -> still has another try
        when(reviewRepository.findById(10L)).thenReturn(Optional.of(review));
        when(reviewJobRepository.findByReviewId(10L)).thenReturn(Optional.empty());

        retryManager.requeueOrFail(10L, "heartbeat timeout");

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.QUEUED), eq(EventType.RETRY), any(), any(), any());
    }

    @Test
    void atMaxAttemptsFails() {
        Review review = runningReviewWithAttempts(3); // 3 >= 3 -> exhausted
        when(reviewRepository.findById(11L)).thenReturn(Optional.of(review));
        when(reviewJobRepository.findByReviewId(11L)).thenReturn(Optional.empty());

        retryManager.requeueOrFail(11L, "heartbeat timeout");

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.FAILED), eq(EventType.FAILED), any(), any(), any());
    }

    @Test
    void reviewNoLongerRunningIsANoOp() {
        Review review = runningReviewWithAttempts(1);
        review.setStatus(ReviewStatus.COMPLETED);
        when(reviewRepository.findById(12L)).thenReturn(Optional.of(review));

        retryManager.requeueOrFail(12L, "heartbeat timeout");

        verify(stateMachine, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingReviewIsANoOp() {
        when(reviewRepository.findById(13L)).thenReturn(Optional.empty());

        retryManager.requeueOrFail(13L, "heartbeat timeout");

        verify(stateMachine, never()).transition(any(), any(), any(), any(), any(), any());
    }

    @Test
    void attributionIsPulledFromTheCurrentJobWhenPresent() {
        Review review = runningReviewWithAttempts(1);
        ReviewJob job = new ReviewJob(10L, 99L, "worker-7");
        when(reviewRepository.findById(20L)).thenReturn(Optional.of(review));
        when(reviewJobRepository.findByReviewId(20L)).thenReturn(Optional.of(job));

        retryManager.requeueOrFail(20L, "heartbeat timeout");

        verify(stateMachine).transition(eq(review), eq(ReviewStatus.QUEUED), eq(EventType.RETRY),
                eq("worker-7"), eq(99L), any());
    }
}
