package com.review.gateway.repository;

import com.review.gateway.model.ReviewInput;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for the immutable {@link ReviewInput} payload. Enables re-running a Review without
 * hitting GitLab again (req. 1.2/4.3).
 */
public interface ReviewInputRepository extends JpaRepository<ReviewInput, Long> {

    Optional<ReviewInput> findByReviewId(Long reviewId);
}
