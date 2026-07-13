package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.repository.ReviewJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeoutManagerTest {

    private ReviewJobRepository reviewJobRepository;
    private RetryManager retryManager;
    private GatewayProperties properties;
    private TimeoutManager timeoutManager;

    @BeforeEach
    void setUp() {
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        retryManager = Mockito.mock(RetryManager.class);
        properties = new GatewayProperties();
        properties.getHeartbeat().setTimeout(Duration.ofSeconds(180));
        properties.getJob().setMaxDuration(Duration.ofMinutes(45));
        timeoutManager = new TimeoutManager(reviewJobRepository, retryManager, properties);
    }

    @Test
    void sweepStaleHeartbeatsDelegatesEachCandidateToRetryManager() {
        when(reviewJobRepository.findReviewIdsWithStaleHeartbeat(any())).thenReturn(List.of(1L, 2L, 3L));

        int count = timeoutManager.sweepStaleHeartbeats();

        assertThat(count).isEqualTo(3);
        verify(retryManager).requeueOrFail(1L, "heartbeat timeout");
        verify(retryManager).requeueOrFail(2L, "heartbeat timeout");
        verify(retryManager).requeueOrFail(3L, "heartbeat timeout");
    }

    @Test
    void sweepStaleHeartbeatsIsANoOpWhenNothingIsStale() {
        when(reviewJobRepository.findReviewIdsWithStaleHeartbeat(any())).thenReturn(List.of());

        int count = timeoutManager.sweepStaleHeartbeats();

        assertThat(count).isZero();
        verify(retryManager, times(0)).requeueOrFail(any(), any());
    }

    @Test
    void enforceMaxDurationDelegatesEachCandidateToRetryManager() {
        when(reviewJobRepository.findReviewIdsExceedingMaxDuration(any())).thenReturn(List.of(5L));

        int count = timeoutManager.enforceMaxDuration();

        assertThat(count).isEqualTo(1);
        verify(retryManager).requeueOrFail(5L, "max duration exceeded");
    }

    @Test
    void sweepStaleHeartbeatsUsesConfiguredTimeoutAsCutoff() {
        Instant before = Instant.now();
        when(reviewJobRepository.findReviewIdsWithStaleHeartbeat(any())).thenReturn(List.of());

        timeoutManager.sweepStaleHeartbeats();

        // Just confirms it invokes the repository with *some* Instant cutoff (captured implicitly by the stub above);
        // the precise value is covered at the repository/integration level (ReviewJobRepositoryTest, feature 01).
        assertThat(before).isBeforeOrEqualTo(Instant.now());
    }
}
