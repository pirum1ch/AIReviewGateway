package com.review.gateway.repository;

import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.enums.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for the append-only {@link ReviewEvent} audit trail.
 */
public interface ReviewEventRepository extends JpaRepository<ReviewEvent, Long> {

    List<ReviewEvent> findByReviewIdOrderByCreatedAtAsc(Long reviewId);

    /** Total retry count across all reviews, backing {@code StatisticsService}/{@code GET /metrics}. */
    long countByEventType(EventType eventType);

    /**
     * V2 (diff chunking) bugfix: a chunk-job retry now writes <em>two</em> {@code RETRY} events — one
     * job-level ({@code job_id} set, via {@code JobStateMachine}, one per actual retry) and, only when
     * the parent Review's derived status also transitions back to {@code QUEUED}, a second review-level
     * one ({@code job_id} {@code null}, via {@code ChunkCoordinator}/{@code StateMachine}). Counting
     * {@link #countByEventType} would double the reported retry count for the common single-chunk case
     * (both events always fire together then). This variant counts only the job-level event, which is
     * emitted exactly once per actual retry regardless of chunk count — the correct semantics for "how
     * many times has a job been retried" (req. 1.11).
     */
    long countByEventTypeAndJobIdIsNotNull(EventType eventType);
}
