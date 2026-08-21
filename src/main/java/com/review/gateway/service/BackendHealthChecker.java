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
 * at-capacity backend is already unclaimable). {@code probe_failed_since} is recorded on the very first
 * failed probe of a streak <em>before</em> the deferral decision is made and is never cleared or
 * restarted by a deferral (F-WOC-02) — only the {@code ACTIVE -> SUSPECT} status flip is deferred.
 * {@code BackendDispatcher} makes dispatch fail-fast, independent of this fail-slow status: it declines
 * any backend with a non-null {@code probe_failed_since} the instant the first probe fails, exempting
 * only that same at-capacity-with-fresh-heartbeat condition (F-WOC-01/WOR-10) — so the deferral stays
 * genuinely dispatch-neutral both at evaluation time and afterward, without waiting for the (much slower)
 * {@code failure-grace} status-flip window. The deferral itself is capped by {@code
 * gateway.backend.defer-demotion-max} so a backend held busy indefinitely (e.g. by a misbehaving Worker
 * pinning {@code backendId}, WOR-11) cannot defer the status flip forever.
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
        checkStuckQueuedJobs();
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
        // F-WOC-02: probe_failed_since MUST be recorded (first failure sets it, a failure mid-streak
        // leaves it unchanged) BEFORE the WOC-13 at-capacity-deferral decision, not after -- otherwise a
        // failure streak that *begins* while the backend is at capacity (a llama-server dying mid-job on
        // a capacity-1, 1:1-paired host -- the documented deployment) is never recorded at all, silently
        // disabling WOR-10 (BackendDispatcher's dispatch decline), WOR-11 (stall-WARN eligibility) and
        // WOR-13 (the deferral cap itself, which is keyed on this same field) in exactly the scenario
        // each exists for.
        if (backend.getProbeFailedSince() == null) {
            backend.setProbeFailedSince(now);
        }
        if (shouldDeferDemotion(backend, now)) {
            // WOC-13: dispatch-neutral deferral -- defers only the ACTIVE -> SUSPECT status flip (and,
            // symmetrically, BackendDispatcher's WOR-10 dispatch decline exempts this same at-capacity-
            // with-fresh-heartbeat condition, F-WOC-01). probe_failed_since itself is never deferred: it
            // was already recorded above and is persisted here unchanged, so the moment the deferral
            // condition no longer holds (job ends, heartbeat goes stale, or the defer-demotion-max cap is
            // reached) the very next pass sees an honest, uninterrupted streak.
            log.info("Backend '{}' failed health probe but is at capacity with a fresh heartbeat; "
                    + "deferring demotion (WOC-13, dispatch-neutral); probe_failed_since recorded at {}",
                    backend.getName(), backend.getProbeFailedSince());
            backendRepository.save(backend);
            return false;
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

    /**
     * WOR-15(a): reuses this same tick (no new scheduled job) to surface a distinct failure mode from
     * {@link #checkQueueStalled()} — a {@code QUEUED} job whose {@code not_before} has been in the past
     * for longer than {@code gateway.job.max-duration} even though backends are otherwise healthy (e.g.
     * a {@code not_before} far in the future from clock skew, a misconfigured {@code requeue-delay}, or a
     * bug). Nothing else sweeps {@code QUEUED} jobs (WOT-08), so without this a stalled queue of this
     * kind is silent forever.
     */
    private void checkStuckQueuedJobs() {
        Instant cutoff = Instant.now().minus(properties.getJob().getMaxDuration());
        long stuck = reviewJobRepository.countStuckQueuedJobs(cutoff);
        if (stuck > 0) {
            log.warn("Queue stalled: {} QUEUED job(s) with not_before in the past for longer than {} (job.max-duration)",
                    stuck, properties.getJob().getMaxDuration());
        }
    }
}
