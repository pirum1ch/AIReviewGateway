package com.review.gateway.service;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves a backend by name and enforces the claim-time eligibility check (architecture §5 step 1):
 * the backend must exist, be {@code ACTIVE}, and have free capacity — capacity being derived purely
 * from the count of currently-{@code RUNNING} jobs on it (req. 1.6, no separate counter).
 *
 * <p><b>Not {@code @Transactional}, deliberately</b> (QA-critical fix): this method is only ever
 * called from within {@code QueueManager.claim}'s already-open {@code REQUIRES_NEW} transaction, so it
 * needs none of its own. A previous version was {@code @Transactional(readOnly = true)} and threw
 * {@code JobNotClaimableException} for the "not claimable" cases; because that method-level
 * {@code @Transactional} advice is a *separate* AOP interceptor joining the same physical transaction
 * (propagation REQUIRED), Spring marked the whole shared transaction rollback-only the instant the
 * exception crossed that inner proxy boundary — even though {@code QueueManager.claim} immediately
 * caught it and returned normally. The transaction then failed at commit with
 * {@code UnexpectedRollbackException}, turning every routine "backend unknown / not ACTIVE / at
 * capacity" decline (the single most common {@code POST /jobs/claim} outcome under normal load) into
 * an opaque {@code 500} instead of the documented {@code 204}
 * ({@code BackendDispatcherClaimDeclineTransactionBugTest}). Returning {@link Optional#empty()} instead
 * of throwing avoids the transactional-AOP boundary entirely; no exception ever crosses a proxy here.
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
     * @return the backend if it exists, is {@code ACTIVE}, and has free capacity; {@link
     *         Optional#empty()} otherwise. {@code QueueManager} treats every empty case identically as
     *         "no job available right now" (204).
     */
    public Optional<Backend> resolveClaimableBackend(String backendName) {
        Optional<Backend> backendOpt = backendRepository.findByName(backendName);
        if (backendOpt.isEmpty()) {
            log.debug("Claim declined: unknown backend '{}'", backendName);
            return Optional.empty();
        }

        Backend backend = backendOpt.get();
        if (backend.getStatus() != BackendStatus.ACTIVE) {
            log.debug("Claim declined: backend '{}' is not ACTIVE (status={})", backendName, backend.getStatus());
            return Optional.empty();
        }

        long running = reviewJobRepository.countRunningJobsForBackend(backend.getId());
        if (running >= backend.getCapacity()) {
            log.debug("Claim declined: backend '{}' at capacity ({}/{})", backendName, running, backend.getCapacity());
            return Optional.empty();
        }

        return Optional.of(backend);
    }
}
