package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
 *
 * <p><b>WOR-10 / F-WOC-01 (Worker Observability &amp; Claim Latency):</b> declines a backend that has
 * failed <em>any</em> health probe — i.e. carries a non-null {@code probe_failed_since} — the instant the
 * very first probe fails, <em>regardless of its persisted {@code status}</em> and regardless of whether
 * {@code gateway.backend.failure-grace} has elapsed. This is deliberately fail-<em>fast</em> dispatch
 * decoupled from the fail-<em>slow</em> {@code ACTIVE -> SUSPECT} status flip (WOC-11/12, unchanged,
 * still gated on a continuous {@code failure-grace}-length streak for alerting/operator-visibility
 * purposes only): the original WOR-10 rule (decline only once a streak was already past {@code
 * failure-grace}) left the attempt budget exhaustible against a backend the Gateway already knew was
 * dead — see F-WOC-01 / WOT-01 — because the two clocks (the Worker's attempt-retry clock, which starts
 * within milliseconds of a backend dying, and the demotion grace clock) start at different instants.
 * Declining on <em>any</em> failure closes that gap: the worst case a healthy backend now pays is one
 * probe interval of no new claims, restoring the pre-branch "park in {@code QUEUED}" behavior for a dead
 * backend without reintroducing single-probe status flapping.
 *
 * <p><b>WOC-13 exemption, mirrored exactly (F-WOC-01):</b> a backend that is currently at capacity with
 * at least one fresh-heartbeat {@code RUNNING} job is exempt from this fail-fast decline — the same
 * condition {@code BackendHealthChecker.shouldDeferDemotion} uses to defer the status flip. This exists
 * so a backend that is merely busy mid-generation (and, per F-WOC-02, may now carry a non-null {@code
 * probe_failed_since} even while deferred) is not additionally penalized here: it is already unclaimable
 * via the capacity check below regardless of the exemption, so the exemption changes no observable claim
 * outcome for that case — it exists to keep the two "is this the same busy-not-dead backend" checks
 * provably in sync as either evolves, and so the decline reason logged is the honest one (at-capacity,
 * not probe-failure).
 */
@Service
public class BackendDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BackendDispatcher.class);

    private final BackendRepository backendRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final GatewayProperties properties;

    public BackendDispatcher(BackendRepository backendRepository, ReviewJobRepository reviewJobRepository,
                              GatewayProperties properties) {
        this.backendRepository = backendRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.properties = properties;
    }

    /**
     * @return the backend if it exists, is {@code ACTIVE}, has free capacity, and (WOR-10/F-WOC-01) has
     *         either never failed a health probe or is currently exempt from the fail-fast decline
     *         (at capacity with a fresh heartbeat, WOC-13); {@link Optional#empty()} otherwise. {@code
     *         QueueManager} treats every empty case identically as "no job available right now" (204).
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
        boolean atCapacity = running >= backend.getCapacity();

        // F-WOC-01: fail-fast on ANY recorded probe failure, not only a past-grace one -- see the class
        // javadoc. Exempt only the WOC-13 at-capacity-with-fresh-heartbeat condition, mirrored exactly
        // from BackendHealthChecker.shouldDeferDemotion; short-circuits on atCapacity so the extra
        // heartbeat-freshness query only ever runs for a backend that is genuinely full.
        if (backend.getProbeFailedSince() != null && !(atCapacity && hasFreshRunningHeartbeat(backend))) {
            log.debug("Claim declined: backend '{}' has failed at least one health probe (probe_failed_since={}) "
                    + "and is not at capacity with a fresh heartbeat (WOR-10, F-WOC-01 fail-fast dispatch decline)",
                    backendName, backend.getProbeFailedSince());
            return Optional.empty();
        }

        if (atCapacity) {
            log.debug("Claim declined: backend '{}' at capacity ({}/{})", backendName, running, backend.getCapacity());
            return Optional.empty();
        }

        return Optional.of(backend);
    }

    /**
     * WOC-13/F-WOC-01: whether {@code backend} has at least one currently-{@code RUNNING} job whose
     * heartbeat is still fresh — the same "usefully busy, not dead" signal {@code BackendHealthChecker}
     * uses to defer the status flip, reused here to exempt the fail-fast dispatch decline identically.
     */
    private boolean hasFreshRunningHeartbeat(Backend backend) {
        Instant heartbeatCutoff = Instant.now().minus(properties.getHeartbeat().getTimeout());
        return reviewJobRepository.existsFreshRunningJobForBackend(backend.getId(), heartbeatCutoff);
    }
}
