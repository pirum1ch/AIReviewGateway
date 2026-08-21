package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.PublishOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublishRetryServiceTest {

    @Test
    void countsOnlyFullyPublishedReviews() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        GitLabPublisher gitLabPublisher = Mockito.mock(GitLabPublisher.class);

        Review published = ReviewTestSupport.withId(new Review(1L, 1L, "sha-1", "base", "v1", 10), 1L);
        Review partial = ReviewTestSupport.withId(new Review(1L, 2L, "sha-2", "base", "v1", 10), 2L);
        when(reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.COMPLETED))
                .thenReturn(List.of(published, partial));
        when(gitLabPublisher.publishReview(published.getId())).thenReturn(PublishOutcome.PUBLISHED);
        when(gitLabPublisher.publishReview(partial.getId())).thenReturn(PublishOutcome.PARTIAL);

        PublishRetryService service = new PublishRetryService(reviewRepository, gitLabPublisher);
        int publishedCount = service.retryPublications();

        assertThat(publishedCount).isEqualTo(1);
        verify(gitLabPublisher).publishReview(published.getId());
        verify(gitLabPublisher).publishReview(partial.getId());
    }

    @Test
    void noCandidatesMeansNoWork() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        GitLabPublisher gitLabPublisher = Mockito.mock(GitLabPublisher.class);
        when(reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.COMPLETED)).thenReturn(List.of());

        PublishRetryService service = new PublishRetryService(reviewRepository, gitLabPublisher);

        assertThat(service.retryPublications()).isZero();
    }

    /**
     * DPR-01b (Diff Position Anchoring): before this feature, {@code publishReview} was effectively
     * non-throwing, so this loop had no per-review guard. Position resolution is the first thing that can
     * throw out of it; a poisoned first candidate (ordered {@code createdAt ASC}, so always at the head of
     * every pass) must not prevent the other candidates in the same pass from being attempted.
     */
    @Test
    void aReviewThatThrowsDuringPublishDoesNotBlockTheRemainingCandidatesInThePass() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        GitLabPublisher gitLabPublisher = Mockito.mock(GitLabPublisher.class);

        Review poisoned = ReviewTestSupport.withId(new Review(1L, 1L, "sha-1", "base", "v1", 10), 1L);
        Review second = ReviewTestSupport.withId(new Review(1L, 2L, "sha-2", "base", "v1", 10), 2L);
        Review third = ReviewTestSupport.withId(new Review(1L, 3L, "sha-3", "base", "v1", 10), 3L);
        when(reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.COMPLETED))
                .thenReturn(List.of(poisoned, second, third));
        when(gitLabPublisher.publishReview(poisoned.getId()))
                .thenThrow(new RuntimeException("boom: crafted hunk header"));
        when(gitLabPublisher.publishReview(second.getId())).thenReturn(PublishOutcome.PUBLISHED);
        when(gitLabPublisher.publishReview(third.getId())).thenReturn(PublishOutcome.PUBLISHED);

        PublishRetryService service = new PublishRetryService(reviewRepository, gitLabPublisher);
        int publishedCount = service.retryPublications();

        assertThat(publishedCount).isEqualTo(2);
        verify(gitLabPublisher).publishReview(poisoned.getId());
        verify(gitLabPublisher).publishReview(second.getId());
        verify(gitLabPublisher).publishReview(third.getId());
    }
}
