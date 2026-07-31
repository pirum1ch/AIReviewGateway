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
 * Detects stuck chunk JOBS (V2, diff chunking) by two independent, idempotent conditional sweeps (req.
 * 1.7, architecture §8): a missed heartbeat (the primary liveness signal) and a hard max-duration
 * backstop beyond it. Both delegate the actual requeue-or-fail decision to {@link RetryManager}, keyed
 * by job id (not review id) as of V2. Safe to call repeatedly (each candidate row is only touched if it
 * is still {@code RUNNING} by the time {@code RetryManager} looks at it).
 *
 * <p>Read-only at this level: unlike pre-V2, {@link RetryManager#requeueOrFail} now opens its own
 * {@code REQUIRES_NEW} transactions internally (via {@code TransactionTemplate}) rather than joining
 * whatever transaction is already open here, so this method no longer needs to avoid {@code readOnly}
 * for KD-1's sake — it genuinely performs no writes of its own.
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
     * @return the number of candidate jobs swept (not all necessarily still RUNNING by the time
     *         {@code RetryManager} looked, since this is inherently a best-effort snapshot query)
     */
    @Transactional(readOnly = true)
    public int sweepStaleHeartbeats() {
        Instant cutoff = Instant.now().minus(properties.getHeartbeat().getTimeout());
        List<Long> staleJobIds = reviewJobRepository.findJobIdsWithStaleHeartbeat(cutoff);
        for (Long jobId : staleJobIds) {
            retryManager.requeueOrFail(jobId, "heartbeat timeout");
        }
        if (!staleJobIds.isEmpty()) {
            log.info("Heartbeat sweep: {} stale job(s) processed", staleJobIds.size());
        }
        return staleJobIds.size();
    }

    /**
     * Backstop sweep beyond heartbeat monitoring: every {@code RUNNING} job whose total execution
     * time has exceeded {@code gateway.job.max-duration}, regardless of heartbeat freshness.
     *
     * @return the number of candidate jobs swept
     */
    @Transactional(readOnly = true)
    public int enforceMaxDuration() {
        Instant cutoff = Instant.now().minus(properties.getJob().getMaxDuration());
        List<Long> exceeded = reviewJobRepository.findJobIdsExceedingMaxDuration(cutoff);
        for (Long jobId : exceeded) {
            retryManager.requeueOrFail(jobId, "max duration exceeded");
        }
        if (!exceeded.isEmpty()) {
            log.info("Max-duration sweep: {} job(s) processed", exceeded.size());
        }
        return exceeded.size();
    }
}
