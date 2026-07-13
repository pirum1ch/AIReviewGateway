package com.review.gateway.service;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.BackendSnapshot;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the {@code GET /metrics} aggregate (req. 1.11) and the {@code GET /backends} registry view
 * (feature/03-api-security addition, purely additive — no existing method's behavior changes) purely
 * from PostgreSQL — no in-memory counters, consistent with "PostgreSQL is the single source of truth".
 */
@Service
public class StatisticsService {

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewEventRepository reviewEventRepository;
    private final BackendRepository backendRepository;

    public StatisticsService(ReviewRepository reviewRepository,
                              ReviewJobRepository reviewJobRepository,
                              ReviewCommentRepository reviewCommentRepository,
                              ReviewEventRepository reviewEventRepository,
                              BackendRepository backendRepository) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewEventRepository = reviewEventRepository;
        this.backendRepository = backendRepository;
    }

    /** Backs {@code GET /backends} (ADMIN-only). */
    @Transactional(readOnly = true)
    public List<BackendSnapshot> listBackends() {
        return backendRepository.findAll().stream()
                .map(this::toSnapshot)
                .toList();
    }

    private BackendSnapshot toSnapshot(Backend backend) {
        long running = reviewJobRepository.countRunningJobsForBackend(backend.getId());
        return new BackendSnapshot(backend.getId(), backend.getName(), backend.getModel(),
                backend.getCapacity(), backend.getStatus(), running, backend.getLastSeen());
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
