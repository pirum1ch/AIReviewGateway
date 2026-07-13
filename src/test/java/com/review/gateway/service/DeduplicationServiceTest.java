package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DeduplicationServiceTest {

    @Test
    void activeStatusesMatchTheFiveNonTerminalOnes() {
        assertThat(DeduplicationService.ACTIVE_STATUSES).containsExactlyInAnyOrder(
                ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
                ReviewStatus.COMPLETED, ReviewStatus.PUBLISHED);
    }

    @Test
    void obsoletableStatusesExcludePublished() {
        assertThat(DeduplicationService.OBSOLETABLE_STATUSES).containsExactlyInAnyOrder(
                ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING, ReviewStatus.COMPLETED);
        assertThat(DeduplicationService.OBSOLETABLE_STATUSES).doesNotContain(ReviewStatus.PUBLISHED);
    }

    @Test
    void findActiveReviewDelegatesWithTheActiveStatusSet() {
        ReviewRepository repository = Mockito.mock(ReviewRepository.class);
        Review existing = new Review(1L, 2L, "sha", "base", "v1", 10);
        when(repository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                eq(1L), eq(2L), eq("sha"), any())).thenReturn(Optional.of(existing));

        DeduplicationService service = new DeduplicationService(repository);
        Optional<Review> result = service.findActiveReview(1L, 2L, "sha");

        assertThat(result).contains(existing);
    }

    @Test
    void findActiveReviewReturnsEmptyWhenNoneExists() {
        ReviewRepository repository = Mockito.mock(ReviewRepository.class);
        when(repository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        DeduplicationService service = new DeduplicationService(repository);

        assertThat(service.findActiveReview(1L, 2L, "sha")).isEmpty();
    }
}
