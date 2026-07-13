package com.review.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin scheduled-job driver for {@link TimeoutManager#sweepStaleHeartbeats()} (architecture §8:
 * {@code HeartbeatChecker.sweepStalled}, interval {@code gateway.scheduler.heartbeat-check-interval}).
 * The {@code @Scheduled} annotation is added in feature/03-api-security; this method is already
 * public and safe to invoke directly (e.g. from a test, or manually).
 */
@Service
public class HeartbeatChecker {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatChecker.class);

    private final TimeoutManager timeoutManager;

    public HeartbeatChecker(TimeoutManager timeoutManager) {
        this.timeoutManager = timeoutManager;
    }

    public void sweepStalled() {
        int swept = timeoutManager.sweepStaleHeartbeats();
        log.debug("HeartbeatChecker.sweepStalled: {} job(s) swept", swept);
    }
}
