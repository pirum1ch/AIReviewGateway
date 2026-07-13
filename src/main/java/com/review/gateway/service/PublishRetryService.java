package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.PublishOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled-job driver retrying publication for every {@code COMPLETED} Review (architecture §8:
 * {@code PublishRetryService.retryPublications}, {@code gateway.scheduler.publish-retry-interval}).
 * {@code GitLabPublisher} itself already guards against OBSOLETE/CANCELLED and re-checks status
 * freshly per Review, so this driver only needs to select candidates and delegate (req. 1.10).
 * {@code @Scheduled} wiring is added in feature/03-api-security.
 */
@Service
public class PublishRetryService {

    private static final Logger log = LoggerFactory.getLogger(PublishRetryService.class);

    private final ReviewRepository reviewRepository;
    private final GitLabPublisher gitLabPublisher;

    public PublishRetryService(ReviewRepository reviewRepository, GitLabPublisher gitLabPublisher) {
        this.reviewRepository = reviewRepository;
        this.gitLabPublisher = gitLabPublisher;
    }

    /** @return the number of reviews that were fully published during this pass */
    @Transactional(readOnly = true)
    public int retryPublications() {
        List<Review> completed = reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.COMPLETED);
        int publishedCount = 0;
        for (Review review : completed) {
            PublishOutcome outcome = gitLabPublisher.publishReview(review.getId());
            if (outcome == PublishOutcome.PUBLISHED) {
                publishedCount++;
            }
        }
        if (publishedCount > 0) {
            log.info("Publish retry pass: {} review(s) published out of {} candidate(s)", publishedCount, completed.size());
        }
        return publishedCount;
    }
}
