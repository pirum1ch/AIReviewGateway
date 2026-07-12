package com.review.gateway.repository;

import com.review.gateway.model.ReviewComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link ReviewComment}. {@code GitLabPublisher} only ever selects and mutates rows
 * returned by {@link #findByReviewIdAndPublishedAtIsNull}, which is what makes comment publication
 * idempotent across retries (req. 1.10).
 */
public interface ReviewCommentRepository extends JpaRepository<ReviewComment, Long> {

    List<ReviewComment> findByReviewId(Long reviewId);

    List<ReviewComment> findByReviewIdAndPublishedAtIsNull(Long reviewId);

    long countByReviewId(Long reviewId);
}
