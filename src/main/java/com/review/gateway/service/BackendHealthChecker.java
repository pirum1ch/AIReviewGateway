package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes every {@code ACTIVE}/{@code SUSPECT} backend and flips status on change (req. 1.6), reshaped
 * for Worker Observability &amp; Claim Latency (WOC-14) into three phases so probe HTTP I/O never holds a
 * Hikari connection: <b>A</b> a short read-only transaction loads candidates; <b>B</b> probing happens
 * over HTTP with no transaction open at all; <b>C</b> a short transaction per backend re-reads it by id
 * and applies the status/{@code probe_failed_since}/{@code last_seen} write, guarded by the status it
 * re-reads (so a backend an operator moved to {@code MAINTENANCE}/{@code OFFLINE} mid-pass is left
 * alone, WOC-21). Phases are driven by {@link TransactionTemplate}, not by self-invoking a {@code
 * @Transactional} method on {@code this} — the same self-invocation trap documented on {@code
 * BackendDispatcher}.
 *
 * <p><b>Fail-slow / recover-fast (WOC-11/WOC-12):</b> {@code ACTIVE -> SUSPECT} requires a
 * <em>continuous</em> failed-probe streak of at least {@code gateway.backend.failure-grace}, tracked in
 * the restart-safe {@code backends.probe_failed_since} column; {@code SUSPECT -> ACTIVE} stays
 * single-success. {@code last_seen} is updated only on a successful probe (WOC-15).
 *
 * <p><b>At-capacity deferral (WOC-13, bounded by WOR-13):</b> a failed probe does not demote a backend
 * that is at capacity with a fresh-heartbeat {@code RUNNING} job — dispatch-neutral by construction (an
 * at-capacity backend is already unclaimable), and made dispatch-neutral <em>over time</em> too by {@code
 * BackendDispatcher} independently declining a past-grace {@code probe_failed_since} regardless of
 * persisted status (WOR-10). The deferral itself is capped by {@code gateway.backend.defer-demotion-max}
 * so a backend held busy indefinitely (e.g. by a misbehaving Worker pinning {@code backendId}, WOR-11)
 * cannot defer demotion forever.
 *
 * <p><b>WOC-17:</b> a non-blocking {@link AtomicBoolean} re-entrancy guard — a raised read timeout
 * (WOC-16) plus enough backends can make one pass exceed the scheduler interval; {@code
 * SimpleAsyncTaskScheduler} does not serialize overlapping {@code fixedRate} runs on its own. Process-
 * local re-entrancy control (single Gateway instance), not business state.
 */
@Service
public class BackendHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(BackendHealthChecker.class);

    private final BackendRepository backendRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final BackendProber backendProber;
    private final GatewayProperties properties;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final TransactionTemplate writeTransactionTemplate;

    private final AtomicBoolean passInProgress = new AtomicBoolean(false);

    public BackendHealthChecker(BackendRepository backendRepository,
                                 ReviewJobRepository reviewJobRepository,
                                 BackendProber backendProber,
                                 GatewayProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.backendRepository = backendRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.backendProber = backendProber;
        this.properties = properties;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.readOnlyTransactionTemplate.setName("BackendHealthChecker.loadCandidates");
        this.writeTransactionTemplate = new TransactionTemplate(transactionManager);
        this.writeTransactionTemplate.setName("BackendHealthChecker.applyProbeResult");
    }

    /**
     * @return the number of backends whose status flipped as a result of this probe pass (0 if a pass
     *         was already in progress and this call was skipped, WOC-17).
     */
    public int probeAll() {
        if (!passInProgress.compareAndSet(false, true)) {
            log.warn("Backend health probe pass already in progress; skipping this tick (WOC-17)");
            return 0;
        }
        try {
            return runPass();
        } finally {
            passInProgress.set(false);
        }
    }

    private int runPass() {
        // Phase A: short read-only transaction, load candidates only -- no HTTP I/O inside it.
        List<Backend> candidates = readOnlyTransactionTemplate.execute(status -> {
            List<Backend> result = new ArrayList<>();
            result.addAll(backendRepository.findByStatus(BackendStatus.ACTIVE));
            result.addAll(backendRepository.findByStatus(BackendStatus.SUSPECT));
            return result;
        });
        if (candidates == null) {
            candidates = List.of();
        }

        int flips = 0;
        for (Backend candidate : candidates) {
            // Phase B: HTTP probe, no transaction, no Hikari connection held (WOC-14/16).
            boolean healthy = safeProbe(candidate);
            // Phase C: short transaction, re-read by id, apply the write.
            if (applyProbeResult(candidate.getId(), healthy)) {
                flips++;
            }
        }

        checkQueueStalled();
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

    /** Phase C. @return whether the backend's status flipped. */
    private boolean applyProbeResult(Long backendId, boolean healthy) {
        Boolean flipped = writeTransactionTemplate.execute(status -> {
            Backend backend = backendRepository.findById(backendId).orElse(null);
            if (backend == null) {
                return false;
            }
            if (backend.getStatus() != BackendStatus.ACTIVE && backend.getStatus() != BackendStatus.SUSPECT) {
                // WOC-21: an operator moved it to MAINTENANCE/OFFLINE mid-pass -- leave it alone.
                return false;
            }
            return healthy ? applySuccess(backend) : applyFailure(backend);
        });
        return flipped != null && flipped;
    }

    private boolean applySuccess(Backend backend) {
        backend.setProbeFailedSince(null);
        // WOC-15: last_seen now means "last SUCCESSFUL contact" -- previously written unconditionally.
        backend.setLastSeen(Instant.now());
        boolean flipped = false;
        if (backend.getStatus() == BackendStatus.SUSPECT) {
            backend.setStatus(BackendStatus.ACTIVE);
            flipped = true;
            log.info("Backend '{}' recovered: SUSPECT -> ACTIVE", backend.getName());
        }
        backendRepository.save(backend);
        return flipped;
    }

    private boolean applyFailure(Backend backend) {
        Instant now = Instant.now();
        if (shouldDeferDemotion(backend, now)) {
            // WOC-13: dispatch-neutral deferral -- probe_failed_since is preserved (neither cleared nor
            // restarted), status is untouched, so the very next pass demotes immediately once the grace
            // window has already elapsed.
            log.info("Backend '{}' failed health probe but is at capacity with a fresh heartbeat; "
                    + "deferring demotion (WOC-13, dispatch-neutral)", backend.getName());
            return false;
        }
        if (backend.getProbeFailedSince() == null) {
            backend.setProbeFailedSince(now);
        }
        boolean flipped = false;
        if (backend.getStatus() == BackendStatus.ACTIVE) {
            Duration grace = properties.getBackend().getFailureGrace();
            Duration elapsed = Duration.between(backend.getProbeFailedSince(), now);
            if (elapsed.compareTo(grace) >= 0) {
                backend.setStatus(BackendStatus.SUSPECT);
                flipped = true;
                log.warn("Backend '{}' failed health probe continuously for {} (>= {} grace): ACTIVE -> SUSPECT",
                        backend.getName(), elapsed, grace);
            } else {
                log.info("Backend '{}' failed health probe ({} elapsed of {} grace); still ACTIVE",
                        backend.getName(), elapsed, grace);
            }
        }
        backendRepository.save(backend);
        return flipped;
    }

    /**
     * WOC-13, bounded by WOR-13: defers demotion only while the backend is at capacity with at least one
     * fresh-heartbeat RUNNING job, {@code gateway.backend.defer-demotion-while-busy} is enabled, and the
     * failure streak (if any already exists) has not yet exceeded {@code
     * gateway.backend.defer-demotion-max} — past that cap, demotion proceeds regardless of capacity.
     */
    private boolean shouldDeferDemotion(Backend backend, Instant now) {
        if (!properties.getBackend().isDeferDemotionWhileBusy()) {
            return false;
        }
        Instant probeFailedSince = backend.getProbeFailedSince();
        if (probeFailedSince != null) {
            Duration sinceFirstFailure = Duration.between(probeFailedSince, now);
            if (sinceFirstFailure.compareTo(properties.getBackend().getDeferDemotionMax()) >= 0) {
                return false;
            }
        }
        long running = reviewJobRepository.countRunningJobsForBackend(backend.getId());
        if (running < backend.getCapacity()) {
            return false;
        }
        Instant heartbeatCutoff = now.minus(properties.getHeartbeat().getTimeout());
        return reviewJobRepository.existsFreshRunningJobForBackend(backend.getId(), heartbeatCutoff);
    }

    /**
     * WOC-18/WOR-11: fires at most once per pass. Extended from the architecture doc's original "no
     * ACTIVE backend" condition to "no backend that is ACTIVE <em>and</em> not past a grace-elapsed
     * failure streak" — otherwise a WOC-13-deferred, effectively-dead-but-still-ACTIVE backend would
     * silence the one alarm this branch adds (WOT-07).
     */
    private void checkQueueStalled() {
        long queued = reviewJobRepository.countQueuedJobs();
        if (queued <= 0 || hasEligibleActiveBackend()) {
            return;
        }
        long suspect = backendRepository.findByStatus(BackendStatus.SUSPECT).size();
        long maintenance = backendRepository.findByStatus(BackendStatus.MAINTENANCE).size();
        long offline = backendRepository.findByStatus(BackendStatus.OFFLINE).size();
        log.warn("Queue stalled: {} job(s) QUEUED but 0 eligible ACTIVE backend(s) (suspect={}, maintenance={}, offline={})",
                queued, suspect, maintenance, offline);
    }

    private boolean hasEligibleActiveBackend() {
        Duration grace = properties.getBackend().getFailureGrace();
        Instant now = Instant.now();
        for (Backend backend : backendRepository.findByStatus(BackendStatus.ACTIVE)) {
            Instant probeFailedSince = backend.getProbeFailedSince();
            if (probeFailedSince == null || Duration.between(probeFailedSince, now).compareTo(grace) < 0) {
                return true;
            }
        }
        return false;
    }
}
