package com.review.gateway.repository;

import com.review.gateway.model.ReviewResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ReviewResult} (V2, diff chunking: 1:N per review, one row per chunk).
 * {@code (review_id, chunk_index)} is {@code UNIQUE}, which backs the idempotent-insert guarantee for
 * {@code POST /jobs/{id}/result} — {@link #existsByReviewIdAndChunkIndex} lets {@code ResultProcessor}
 * short-circuit a duplicate submission for the same chunk before attempting an insert.
 */
public interface ReviewResultRepository extends JpaRepository<ReviewResult, Long> {

    boolean existsByReviewIdAndChunkIndex(Long reviewId, Integer chunkIndex);

    Optional<ReviewResult> findByReviewIdAndChunkIndex(Long reviewId, Integer chunkIndex);

    List<ReviewResult> findByReviewIdOrderByChunkIndexAsc(Long reviewId);
}
