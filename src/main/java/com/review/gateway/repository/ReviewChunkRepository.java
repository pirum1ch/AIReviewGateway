package com.review.gateway.repository;

import com.review.gateway.model.ReviewChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ReviewChunk} (V2, diff chunking). Chunks are immutable once written by
 * {@code ReviewService} at Review-creation time (exactly like {@code review_inputs}).
 */
public interface ReviewChunkRepository extends JpaRepository<ReviewChunk, Long> {

    List<ReviewChunk> findByReviewIdOrderByChunkIndexAsc(Long reviewId);

    Optional<ReviewChunk> findByReviewIdAndChunkIndex(Long reviewId, Integer chunkIndex);
}
