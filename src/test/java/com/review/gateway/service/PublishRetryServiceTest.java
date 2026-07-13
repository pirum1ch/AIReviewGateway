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
}
