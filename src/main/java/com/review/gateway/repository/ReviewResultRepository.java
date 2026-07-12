package com.review.gateway.repository;

import com.review.gateway.model.ReviewResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link ReviewResult}. {@code review_id} is {@code UNIQUE}, which backs the
 * idempotent-insert guarantee for {@code POST /jobs/{id}/result} — {@link #existsByReviewId} lets
 * {@code ResultProcessor} short-circuit a duplicate submission before attempting an insert.
 */
public interface ReviewResultRepository extends JpaRepository<ReviewResult, Long> {

    boolean existsByReviewId(Long reviewId);

    Optional<ReviewResult> findByReviewId(Long reviewId);
}
