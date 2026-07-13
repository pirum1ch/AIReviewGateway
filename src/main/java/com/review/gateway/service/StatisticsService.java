package com.review.gateway.service;

import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/**
 * Computes the {@code GET /metrics} aggregate (req. 1.11) purely from PostgreSQL — no in-memory
 * counters, consistent with "PostgreSQL is the single source of truth".
 */
@Service
public class StatisticsService {

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewEventRepository reviewEventRepository;

    public StatisticsService(ReviewRepository reviewRepository,
                              ReviewJobRepository reviewJobRepository,
                              ReviewCommentRepository reviewCommentRepository,
                              ReviewEventRepository reviewEventRepository) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewEventRepository = reviewEventRepository;
    }

    @Transactional(readOnly = true)
    public MetricsSnapshot computeMetrics() {
        Map<ReviewStatus, Long> byStatus = new EnumMap<>(ReviewStatus.class);
        long total = 0;
        for (ReviewRepository.StatusCount count : reviewRepository.countByStatusGrouped()) {
            byStatus.put(count.getStatus(), count.getTotal());
            total += count.getTotal();
        }

        double avgQueueMs = nullToZero(reviewJobRepository.averageQueueWaitMillis());
        double avgRunMs = nullToZero(reviewJobRepository.averageRunDurationMillis());
        long totalComments = reviewCommentRepository.count();
        long retries = reviewEventRepository.countByEventType(EventType.RETRY);

        return new MetricsSnapshot(total, byStatus, avgQueueMs, avgRunMs, totalComments, retries);
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }
}
