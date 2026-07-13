package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Detects stuck jobs by two independent, idempotent conditional sweeps (req. 1.7, architecture §8):
 * a missed heartbeat (the primary liveness signal) and a hard max-duration backstop beyond it. Both
 * delegate the actual requeue-or-fail decision to {@link RetryManager}. The {@code @Scheduled}
 * annotation wiring these into a periodic driver is added in feature/03-api-security; the methods
 * here are already safe to call repeatedly (each candidate row is only touched if it is still
 * {@code RUNNING} by the time {@code RetryManager} looks at it).
 */
@Service
public class TimeoutManager {

    private static final Logger log = LoggerFactory.getLogger(TimeoutManager.class);

    private final ReviewJobRepository reviewJobRepository;
    private final RetryManager retryManager;
    private final GatewayProperties properties;

    public TimeoutManager(ReviewJobRepository reviewJobRepository, RetryManager retryManager, GatewayProperties properties) {
        this.reviewJobRepository = reviewJobRepository;
        this.retryManager = retryManager;
        this.properties = properties;
    }

    /**
     * Sweeps every {@code RUNNING} job whose heartbeat is missing or older than
     * {@code gateway.heartbeat.timeout}, requeuing/failing each via {@link RetryManager}.
     *
     * @return the number of candidate reviews swept (not all necessarily still RUNNING by the time
     *         {@code RetryManager} looked, since this is inherently a best-effort snapshot query)
     */
    @Transactional(readOnly = true)
    public int sweepStaleHeartbeats() {
        Instant cutoff = Instant.now().minus(properties.getHeartbeat().getTimeout());
        List<Long> staleReviewIds = reviewJobRepository.findReviewIdsWithStaleHeartbeat(cutoff);
        for (Long reviewId : staleReviewIds) {
            retryManager.requeueOrFail(reviewId, "heartbeat timeout");
        }
        if (!staleReviewIds.isEmpty()) {
            log.info("Heartbeat sweep: {} stale job(s) processed", staleReviewIds.size());
        }
        return staleReviewIds.size();
    }

    /**
     * Backstop sweep beyond heartbeat monitoring: every {@code RUNNING} job whose total execution
     * time has exceeded {@code gateway.job.max-duration}, regardless of heartbeat freshness.
     *
     * @return the number of candidate reviews swept
     */
    @Transactional(readOnly = true)
    public int enforceMaxDuration() {
        Instant cutoff = Instant.now().minus(properties.getJob().getMaxDuration());
        List<Long> exceeded = reviewJobRepository.findReviewIdsExceedingMaxDuration(cutoff);
        for (Long reviewId : exceeded) {
            retryManager.requeueOrFail(reviewId, "max duration exceeded");
        }
        if (!exceeded.isEmpty()) {
            log.info("Max-duration sweep: {} job(s) processed", exceeded.size());
        }
        return exceeded.size();
    }
}
