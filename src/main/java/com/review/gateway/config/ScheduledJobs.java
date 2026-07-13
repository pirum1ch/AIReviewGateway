package com.review.gateway.config;

import com.review.gateway.service.BackendHealthChecker;
import com.review.gateway.service.HeartbeatChecker;
import com.review.gateway.service.PublishRetryService;
import com.review.gateway.service.TimeoutManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wires the already-idempotent feature/02-core-services driver methods (architecture §8) into
 * {@code @Scheduled} ticks. Deliberately a separate class rather than annotating the service methods
 * directly: this feature's scope is REST/security/scheduling wiring, not service-layer changes, and
 * keeping the annotations here means the service classes stay exactly as verified in feature/02.
 *
 * <p>Every tick is wrapped in its own {@code try/catch}: an exception on one run is logged and
 * swallowed so it can never de-schedule the recurring task or take down any of the other three
 * independent jobs. Single Gateway instance (architecture §12) — no distributed lock (e.g. ShedLock)
 * is needed, there is only ever one scheduler.
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final HeartbeatChecker heartbeatChecker;
    private final TimeoutManager timeoutManager;
    private final BackendHealthChecker backendHealthChecker;
    private final PublishRetryService publishRetryService;

    public ScheduledJobs(HeartbeatChecker heartbeatChecker,
                          TimeoutManager timeoutManager,
                          BackendHealthChecker backendHealthChecker,
                          PublishRetryService publishRetryService) {
        this.heartbeatChecker = heartbeatChecker;
        this.timeoutManager = timeoutManager;
        this.backendHealthChecker = backendHealthChecker;
        this.publishRetryService = publishRetryService;
    }

    /** {@code HeartbeatChecker.sweepStalled} (architecture §8), {@code gateway.scheduler.heartbeat-check-interval}. */
    @Scheduled(fixedRateString = "#{@gatewayProperties.scheduler.heartbeatCheckInterval.toMillis()}")
    public void sweepStaleHeartbeats() {
        try {
            heartbeatChecker.sweepStalled();
        } catch (Exception e) {
            log.error("Heartbeat sweep tick failed; will retry on the next scheduled run", e);
        }
    }

    /** {@code TimeoutManager.enforceMaxDuration} (architecture §8), reuses the heartbeat-check interval. */
    @Scheduled(fixedRateString = "#{@gatewayProperties.scheduler.heartbeatCheckInterval.toMillis()}")
    public void enforceMaxDuration() {
        try {
            timeoutManager.enforceMaxDuration();
        } catch (Exception e) {
            log.error("Max-duration sweep tick failed; will retry on the next scheduled run", e);
        }
    }

    /** {@code BackendHealthChecker.probe} (architecture §8), {@code gateway.scheduler.backend-health-interval}. */
    @Scheduled(fixedRateString = "#{@gatewayProperties.scheduler.backendHealthInterval.toMillis()}")
    public void probeBackends() {
        try {
            backendHealthChecker.probeAll();
        } catch (Exception e) {
            log.error("Backend health probe tick failed; will retry on the next scheduled run", e);
        }
    }

    /** {@code PublishRetryService.retryPublications} (architecture §8), {@code gateway.scheduler.publish-retry-interval}. */
    @Scheduled(fixedRateString = "#{@gatewayProperties.scheduler.publishRetryInterval.toMillis()}")
    public void retryPublications() {
        try {
            publishRetryService.retryPublications();
        } catch (Exception e) {
            log.error("Publish retry tick failed; will retry on the next scheduled run", e);
        }
    }
}
