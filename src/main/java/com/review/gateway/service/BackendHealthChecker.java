package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes every {@code ACTIVE}/{@code SUSPECT} backend and flips status on change (req. 1.6):
 * {@code ACTIVE -> SUSPECT} on probe failure, {@code SUSPECT -> ACTIVE} on recovery. {@code MAINTENANCE}
 * and {@code OFFLINE} backends are left alone (an operator, not this checker, manages those). The
 * {@code @Scheduled} annotation is added in feature/03-api-security ({@code BackendHealthChecker.probe},
 * {@code gateway.scheduler.backend-health-interval}); {@link #probeAll()} is already safe to call
 * directly and repeatedly (each write is guarded by the backend's current status, so it converges).
 */
@Service
public class BackendHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(BackendHealthChecker.class);

    private final BackendRepository backendRepository;
    private final BackendProber backendProber;

    public BackendHealthChecker(BackendRepository backendRepository, BackendProber backendProber) {
        this.backendRepository = backendRepository;
        this.backendProber = backendProber;
    }

    /** @return the number of backends whose status flipped as a result of this probe pass */
    @Transactional
    public int probeAll() {
        List<Backend> candidates = new ArrayList<>();
        candidates.addAll(backendRepository.findByStatus(BackendStatus.ACTIVE));
        candidates.addAll(backendRepository.findByStatus(BackendStatus.SUSPECT));

        int flips = 0;
        for (Backend backend : candidates) {
            boolean healthy = safeProbe(backend);
            BackendStatus before = backend.getStatus();
            if (healthy && before == BackendStatus.SUSPECT) {
                backend.setStatus(BackendStatus.ACTIVE);
                flips++;
                log.info("Backend '{}' recovered: SUSPECT -> ACTIVE", backend.getName());
            } else if (!healthy && before == BackendStatus.ACTIVE) {
                backend.setStatus(BackendStatus.SUSPECT);
                flips++;
                log.warn("Backend '{}' failed health probe: ACTIVE -> SUSPECT", backend.getName());
            }
            backend.setLastSeen(Instant.now());
            backendRepository.save(backend);
        }
        return flips;
    }

    private boolean safeProbe(Backend backend) {
        try {
            backendProber.probe(backend);
            return true;
        } catch (BackendUnavailableException unavailable) {
            log.debug("Backend '{}' probe failed: {}", backend.getName(), unavailable.getMessage());
            return false;
        } catch (RuntimeException unexpected) {
            log.warn("Backend '{}' probe raised an unexpected exception, treating as unhealthy", backend.getName(), unexpected);
            return false;
        }
    }
}
