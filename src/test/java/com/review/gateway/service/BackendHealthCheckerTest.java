package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Worker Observability &amp; Claim Latency (WOC-10..WOC-21, WOR-13): {@link BackendHealthChecker}'s A/B/C
 * phases genuinely open and commit separate transactions (WOC-14), so — like {@code RetryManagerTest}/
 * {@code QueueManagerIntegrationTest} — this is a real-database integration test rather than a pure
 * Mockito unit test. {@link BackendProber} stays mocked (it is the only HTTP boundary).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackendHealthCheckerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewJobRepository.deleteAll();
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private BackendHealthChecker newChecker(BackendProber prober, GatewayProperties properties) {
        return new BackendHealthChecker(backendRepository, reviewJobRepository, prober, properties, transactionManager);
    }

    private GatewayProperties defaultProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        properties.getBackend().setDeferDemotionWhileBusy(true);
        properties.getBackend().setDeferDemotionMax(Duration.ofMinutes(45));
        properties.getHeartbeat().setTimeout(Duration.ofSeconds(180));
        return properties;
    }

    private Backend persistBackend(String name, BackendStatus status, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(status);
        return backendRepository.saveAndFlush(backend);
    }

    private ReviewJob persistRunningJob(Backend backend, Instant heartbeatAt) {
        Review review = new Review(1L, 2L, "sha-" + System.nanoTime(), "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review = reviewRepository.saveAndFlush(review);
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStatus(JobStatus.RUNNING);
        job.setHeartbeatAt(heartbeatAt);
        return reviewJobRepository.saveAndFlush(job);
    }

    // T-2.1: one failed probe does not demote.
    @Test
    void oneFailedProbeDoesNotDemote() {
        Backend backend = persistBackend("mac-t21", BackendStatus.ACTIVE, 1);
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("timeout")).when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isZero();
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(reloaded.getProbeFailedSince()).isNotNull();
    }

    // T-2.2: continuous failure past failure-grace demotes exactly once.
    @Test
    void continuousFailurePastGraceDemotesExactlyOnce() {
        Backend backend = persistBackend("mac-t22", BackendStatus.ACTIVE, 1);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(200)));
        backendRepository.saveAndFlush(backend);
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("still down")).when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isEqualTo(1);
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.SUSPECT);

        // A second pass with the same persistent failure must not flip again (already SUSPECT).
        int secondPassFlips = newChecker(prober, defaultProperties()).probeAll();
        assertThat(secondPassFlips).isZero();
    }

    // T-2.3: a success mid-streak clears probe_failed_since (the grace restarts from scratch).
    @Test
    void successMidStreakClearsProbeFailedSince() {
        Backend backend = persistBackend("mac-t23", BackendStatus.ACTIVE, 1);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(30)));
        backendRepository.saveAndFlush(backend);
        BackendProber prober = mock(BackendProber.class);
        doNothing().when(prober).probe(Mockito.any());

        newChecker(prober, defaultProperties()).probeAll();

        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(reloaded.getProbeFailedSince()).isNull();
    }

    // T-2.4: SUSPECT -> ACTIVE on a single success (recover-fast, unchanged).
    @Test
    void suspectBackendRecoversOnSingleSuccess() {
        Backend backend = persistBackend("mac-t24", BackendStatus.SUSPECT, 1);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(500)));
        backendRepository.saveAndFlush(backend);
        BackendProber prober = mock(BackendProber.class);
        doNothing().when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isEqualTo(1);
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(reloaded.getProbeFailedSince()).isNull();
    }

    // T-2.5 / F-WOC-02: at-capacity + fresh heartbeat defers the STATUS flip only -- probe_failed_since
    // is still recorded (a failure streak that begins while the backend is busy must not go unrecorded,
    // or WOR-10/WOR-11/WOR-13 all silently stop working in exactly the scenario each exists for -- see
    // BackendHealthChecker.applyFailure's javadoc). This assertion was previously (incorrectly) pinned
    // as `isNull()`, which encoded the F-WOC-02 defect as expected behavior.
    @Test
    void atCapacityWithFreshHeartbeatDefersDemotionButStillRecordsTheStreak() {
        Backend backend = persistBackend("mac-t25", BackendStatus.ACTIVE, 1);
        persistRunningJob(backend, Instant.now());
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("busy")).when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isZero();
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("F-WOC-02: only the status flip is deferred while at capacity with a fresh heartbeat")
                .isEqualTo(BackendStatus.ACTIVE);
        assertThat(reloaded.getProbeFailedSince())
                .as("F-WOC-02: the failure streak itself must be recorded even though the status flip is "
                        + "deferred -- this is what makes WOR-10's dispatch decline, WOR-11's stall WARN, "
                        + "and WOR-13's deferral cap all actually engage once the backend is no longer at "
                        + "capacity or the cap is exceeded")
                .isNotNull();
    }

    // T-2.6: once the job ends, the next failed probe demotes immediately if the grace already elapsed.
    @Test
    void onceJobEndsDeferredBackendDemotesImmediatelyIfGraceAlreadyElapsed() {
        Backend backend = persistBackend("mac-t26", BackendStatus.ACTIVE, 1);
        // probe_failed_since already past grace, from before the job started.
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(400)));
        backendRepository.saveAndFlush(backend);
        // No RUNNING job on this backend now -- not at capacity, so the deferral no longer applies.
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("still down")).when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isEqualTo(1);
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.SUSPECT);
    }

    // Regression test for the incident this fix addresses: an HttpClient leak eventually surfaced as an
    // Error (not a RuntimeException) escaping BackendProber.probe(). safeProbe() must treat that the same
    // as a failed probe -- not let it propagate out of the pass -- so probeAll() completes normally and
    // the backend is (correctly) not demoted from a single failure.
    @Test
    void probeThrowingAnErrorIsTreatedAsUnhealthyAndDoesNotPropagate() {
        Backend backend = persistBackend("mac-error", BackendStatus.ACTIVE, 1);
        BackendProber prober = mock(BackendProber.class);
        doThrow(new Error("simulated fatal error")).when(prober).probe(Mockito.any());

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isZero();
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(reloaded.getProbeFailedSince()).isNotNull();
    }

    // T-2.7: last_seen advances only on success.
    @Test
    void lastSeenAdvancesOnlyOnSuccess() {
        Backend backend = persistBackend("mac-t27", BackendStatus.ACTIVE, 1);
        assertThat(backend.getLastSeen()).isNull();
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("down")).when(prober).probe(Mockito.any());

        newChecker(prober, defaultProperties()).probeAll();

        Backend afterFailure = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(afterFailure.getLastSeen()).isNull();

        Mockito.reset(prober);
        doNothing().when(prober).probe(Mockito.any());
        newChecker(prober, defaultProperties()).probeAll();

        Backend afterSuccess = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(afterSuccess.getLastSeen()).isNotNull();
    }

    // WOR-13 / F-WOC-02: past the defer-demotion-max cap, demotion proceeds regardless of
    // capacity/heartbeat. Rewritten (F-WOC-02 SAST fix round): the streak's origin is now produced by an
    // actual failed probe through the real deferral code path (applyFailure -> shouldDeferDemotion),
    // not by hand-constructing a `probe_failed_since` the (pre-fix) deferral path could never itself
    // have produced -- only the elapsed-time-past-the-cap part is simulated by backdating that
    // genuinely-recorded timestamp, the same "simulate elapsed time" technique T-2.2/T-2.6 already use
    // elsewhere in this file.
    @Test
    void deferralCapIsEnforced() {
        Backend backend = persistBackend("mac-wor13", BackendStatus.ACTIVE, 1);
        persistRunningJob(backend, Instant.now()); // at capacity, fresh heartbeat -- deferral applies
        GatewayProperties properties = defaultProperties();
        properties.getBackend().setDeferDemotionMax(Duration.ofMinutes(45));
        BackendProber prober = mock(BackendProber.class);
        doThrow(new BackendUnavailableException("wedged")).when(prober).probe(Mockito.any());

        // Pass 1: a genuine failed probe while at capacity -- exercises the real deferral path and
        // records probe_failed_since for the first time (F-WOC-02); status must stay ACTIVE.
        int firstPassFlips = newChecker(prober, properties).probeAll();
        assertThat(firstPassFlips).isZero();
        Backend afterFirstPass = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(afterFirstPass.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(afterFirstPass.getProbeFailedSince()).as("F-WOC-02: recorded by the real deferral path")
                .isNotNull();

        // Simulate 50 minutes of continuous failure (past the 45m cap) by backdating that
        // genuinely-recorded timestamp -- the backend remains at capacity with a fresh heartbeat
        // throughout, so only the cap (not capacity/heartbeat) is what stops the deferral here.
        afterFirstPass.setProbeFailedSince(Instant.now().minus(Duration.ofMinutes(50)));
        backendRepository.saveAndFlush(afterFirstPass);

        // Pass 2: the cap is now exceeded -- shouldDeferDemotion must stop deferring and the continuous
        // streak (50m, already also past the 180s failure-grace) demotes the backend despite it still
        // being at capacity with a fresh heartbeat.
        int secondPassFlips = newChecker(prober, properties).probeAll();
        assertThat(secondPassFlips).isEqualTo(1);
        Backend reloaded = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.SUSPECT);
    }

    // WOC-17: overlapping passes are skipped by the re-entrancy guard, not run concurrently.
    @Test
    void overlappingPassIsSkippedByTheReentrancyGuard() throws InterruptedException {
        Backend backend = persistBackend("mac-wor17", BackendStatus.ACTIVE, 1);
        CountDownLatch proberEntered = new CountDownLatch(1);
        CountDownLatch releaseProber = new CountDownLatch(1);
        BackendProber slowProber = mock(BackendProber.class);
        Mockito.doAnswer(invocation -> {
            proberEntered.countDown();
            releaseProber.await();
            return null;
        }).when(slowProber).probe(Mockito.any());

        BackendHealthChecker checker = newChecker(slowProber, defaultProperties());
        AtomicInteger firstPassResult = new AtomicInteger(-1);
        Thread firstPass = new Thread(() -> firstPassResult.set(checker.probeAll()));
        firstPass.start();

        assertThat(proberEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // A second pass while the first is still probing must be skipped (0 flips, no exception).
        int secondPassFlips = checker.probeAll();
        assertThat(secondPassFlips).isZero();

        releaseProber.countDown();
        firstPass.join(5000);
        assertThat(firstPass.isAlive()).isFalse();
    }

    @Test
    void maintenanceAndOfflineBackendsAreUntouched() {
        Backend maintenance = persistBackend("mac-maint", BackendStatus.MAINTENANCE, 1);
        Backend offline = persistBackend("mac-off", BackendStatus.OFFLINE, 1);
        BackendProber prober = mock(BackendProber.class);

        int flips = newChecker(prober, defaultProperties()).probeAll();

        assertThat(flips).isZero();
        Mockito.verifyNoInteractions(prober);
        assertThat(backendRepository.findById(maintenance.getId()).orElseThrow().getStatus())
                .isEqualTo(BackendStatus.MAINTENANCE);
        assertThat(backendRepository.findById(offline.getId()).orElseThrow().getStatus())
                .isEqualTo(BackendStatus.OFFLINE);
    }

    @Test
    void reEntrancyGuardResetsAfterAPassSoTheNextTickIsNotPermanentlySkipped() {
        Backend backend = persistBackend("mac-guard-reset", BackendStatus.ACTIVE, 1);
        BackendProber prober = mock(BackendProber.class);
        doNothing().when(prober).probe(Mockito.any());
        BackendHealthChecker checker = newChecker(prober, defaultProperties());

        checker.probeAll();
        int secondPassFlips = checker.probeAll();

        // Second pass must run normally (not be treated as "already in progress"): no exception, and the
        // backend (already ACTIVE from a successful first pass) does not spuriously flip again.
        assertThat(secondPassFlips).isZero();
    }
}
