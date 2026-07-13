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
}
