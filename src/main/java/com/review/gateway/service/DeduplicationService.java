package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an existing "active" Review for a dedup key (req. 1.5). {@code ReviewService} calls
 * {@link #findActiveReview} both before attempting an insert (the common case) and again after a
 * unique-violation race (the concurrent-create case) — the same query serves both purposes.
 */
@Service
public class DeduplicationService {

    /** Mirrors {@code ux_reviews_dedup_active} exactly (architecture §3). */
    public static final Set<ReviewStatus> ACTIVE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
            ReviewStatus.COMPLETED, ReviewStatus.PUBLISHED);

    /** Statuses eligible to be swept to OBSOLETE when a new head_sha arrives (excludes PUBLISHED, req. 1.5). */
    public static final Set<ReviewStatus> OBSOLETABLE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING, ReviewStatus.COMPLETED);

    private final ReviewRepository reviewRepository;

    public DeduplicationService(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Review> findActiveReview(Long projectId, Long mergeRequestId, String headSha) {
        return reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                projectId, mergeRequestId, headSha, ACTIVE_STATUSES);
    }
}
