package com.review.gateway.service;

import com.review.gateway.exception.JobNotClaimableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a backend by name and enforces the claim-time eligibility check (architecture §5 step 1):
 * the backend must exist, be {@code ACTIVE}, and have free capacity — capacity being derived purely
 * from the count of currently-{@code RUNNING} jobs on it (req. 1.6, no separate counter).
 */
@Service
public class BackendDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BackendDispatcher.class);

    private final BackendRepository backendRepository;
    private final ReviewJobRepository reviewJobRepository;

    public BackendDispatcher(BackendRepository backendRepository, ReviewJobRepository reviewJobRepository) {
        this.backendRepository = backendRepository;
        this.reviewJobRepository = reviewJobRepository;
    }

    /**
     * @throws JobNotClaimableException if the backend is unknown, not ACTIVE, or at capacity.
     *         {@code QueueManager} interprets all three as "no job available right now" (204).
     */
    @Transactional(readOnly = true)
    public Backend resolveClaimableBackend(String backendName) {
        Backend backend = backendRepository.findByName(backendName)
                .orElseThrow(() -> new JobNotClaimableException("Unknown backend: " + backendName));

        if (backend.getStatus() != BackendStatus.ACTIVE) {
            throw new JobNotClaimableException(
                    "Backend '" + backendName + "' is not ACTIVE (status=" + backend.getStatus() + ")");
        }

        long running = reviewJobRepository.countRunningJobsForBackend(backend.getId());
        if (running >= backend.getCapacity()) {
            log.debug("Backend '{}' at capacity: running={} capacity={}", backendName, running, backend.getCapacity());
            throw new JobNotClaimableException(
                    "Backend '" + backendName + "' is at capacity (" + running + "/" + backend.getCapacity() + ")");
        }

        return backend;
    }
}
