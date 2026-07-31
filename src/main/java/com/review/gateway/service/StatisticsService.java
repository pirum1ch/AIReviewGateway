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
        // V2 bugfix: count only the job-level RETRY event (job_id set) -- a retry also triggers a
        // second, review-level RETRY event when the parent's derived status transitions back to QUEUED,
        // which would otherwise double this count for the common single-chunk case. See
        // ReviewEventRepository#countByEventTypeAndJobIdIsNotNull javadoc for the full rationale.
        long retries = reviewEventRepository.countByEventTypeAndJobIdIsNotNull(EventType.RETRY);

        // PMR-10/PMR-11: derived from the append-only review_events audit trail, same "no in-memory
        // counters, PostgreSQL is the single source of truth" pattern as every other metric here --
        // how many Reviews were created with the kill-switch off, and how many explicitly-configured
        // override paths were looked up and not found, org-wide.
        //
        // ponytail: F-PM-11(b) (Info) -- PMR-11 originally asked for a labeled metric
        // (prompt_section_absent_total{kind, configured}) so an operator could tell *which* override is
        // broken from /metrics alone, without cross-referencing review_events. These two flat counters
        // are correct in substance but have no dimensions. Not implemented as labeled here because this
        // project deliberately has no Prometheus/labeled-metrics library (requirements §15) -- a
        // label-less GET /metrics is a legitimate reading of the same requirement, and the missing
        // dimension is already recoverable per-incident from the review_events row itself (its `details`
        // column carries `kind=...`). Revisit (either add a per-kind breakdown here, e.g. a
        // Map<PromptSectionKind, Long>, or formally amend PMR-11 in the threat model to match this
        // label-less reading) if an operator ever actually needs the per-kind breakdown from /metrics
        // without querying review_events directly.
        long promptDisabledCount = reviewEventRepository.countByEventType(EventType.PROMPT_DISABLED);
        long promptSectionMissingCount = reviewEventRepository.countByEventType(EventType.PROMPT_SECTION_MISSING);

        return new MetricsSnapshot(total, byStatus, avgQueueMs, avgRunMs, totalComments, retries,
                promptDisabledCount, promptSectionMissingCount);
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }
}
