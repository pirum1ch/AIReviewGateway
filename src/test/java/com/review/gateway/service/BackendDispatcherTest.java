package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class BackendDispatcherTest {

    private BackendRepository backendRepository;
    private ReviewJobRepository reviewJobRepository;
    private BackendDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        backendRepository = Mockito.mock(BackendRepository.class);
        reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        dispatcher = new BackendDispatcher(backendRepository, reviewJobRepository, properties);
    }

    private Backend activeBackend(String name, int capacity) {
        return new Backend(name, "https://" + name + ".local", "model-x", capacity);
    }

    @Test
    void resolvesAnActiveBackendWithFreeCapacity() {
        Backend backend = activeBackend("mac-mini-1", 2);
        when(backendRepository.findByName("mac-mini-1")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        Optional<Backend> resolved = dispatcher.resolveClaimableBackend("mac-mini-1");

        assertThat(resolved).contains(backend);
    }

    @Test
    void unknownBackendNameReturnsEmpty() {
        when(backendRepository.findByName("ghost")).thenReturn(Optional.empty());

        assertThat(dispatcher.resolveClaimableBackend("ghost")).isEmpty();
    }

    @Test
    void nonActiveBackendReturnsEmpty() {
        Backend backend = activeBackend("mac-mini-2", 2);
        backend.setStatus(BackendStatus.SUSPECT);
        when(backendRepository.findByName("mac-mini-2")).thenReturn(Optional.of(backend));

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-2")).isEmpty();
    }

    @Test
    void backendAtCapacityReturnsEmpty() {
        Backend backend = activeBackend("mac-mini-3", 1);
        when(backendRepository.findByName("mac-mini-3")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-3")).isEmpty();
    }

    @Test
    void pastGraceFailureStreakDeclinesEvenIfStatusIsStillActive() {
        // WOR-10: BackendDispatcher must consult probe_failed_since itself, independent of the persisted
        // status a BackendHealthChecker pass last wrote -- this is what makes WOC-13's at-capacity
        // deferral dispatch-neutral OVER TIME, not just at the instant a probe pass evaluated it. A
        // past-grace streak is a (now strict) subset of "any non-null streak" (F-WOC-01), so this stays
        // declined under the fail-fast rule too.
        Backend backend = activeBackend("mac-mini-4", 2);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(200)));
        when(backendRepository.findByName("mac-mini-4")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(0L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-4")).isEmpty();
    }

    @Test
    void anyRecordedProbeFailureDeclinesImmediatelyEvenWellWithinTheOldGraceWindow() {
        // F-WOC-01: the fixed defect -- under the old (WOR-10, pre-fix) rule, a streak that had not yet
        // reached failure-grace (here, 10s old against a 180s grace) stayed claimable, which is exactly
        // what let the third and final attempt of RetryManager's attempt budget land on a backend the
        // Gateway already knew (via this same probe_failed_since) was unresponsive -- see this class's
        // javadoc and docs/security/feature-worker-observability-and-claim-latency-sast-report.md. Now
        // dispatch is fail-fast on ANY non-null streak, independent of failure-grace entirely.
        Backend backend = activeBackend("mac-mini-5", 2);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(10)));
        when(backendRepository.findByName("mac-mini-5")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(0L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-5")).isEmpty();
    }

    @Test
    void aBackendThatHasNeverFailedAProbeStaysClaimable() {
        // The counterpart to the two tests above: a null probe_failed_since (never failed a probe, or
        // cleared by BackendHealthChecker.applySuccess) is not declined by F-WOC-01's fail-fast rule.
        Backend backend = activeBackend("mac-mini-5b", 2);
        when(backendRepository.findByName("mac-mini-5b")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(0L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-5b")).contains(backend);
    }

    @Test
    void aRecoveredBackendBecomesClaimableAgainOncePreviousStreakIsClearedEvenAfterOneProbeFailure() {
        // Restores the pre-branch "park in QUEUED" behavior (F-WOC-01's stated design goal): a backend
        // that failed exactly one probe and has since had it cleared (BackendHealthChecker.applySuccess)
        // is claimable immediately -- the fail-fast rule only ever blocks while probe_failed_since is
        // actually non-null, never afterward.
        Backend backend = activeBackend("mac-mini-5c", 2);
        backend.setProbeFailedSince(null);
        when(backendRepository.findByName("mac-mini-5c")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(0L);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-5c")).contains(backend);
    }

    @Test
    void aFreedUpFormerlyAtCapacityBackendWithAnUnclearedStreakIsDeclinedNotJustDeferred() {
        // F-WOC-01: proves the WOC-13 exemption is scoped to "currently at capacity", not "was at
        // capacity when the streak started". Once the job that made it exempt finishes (running < capacity)
        // a lingering probe_failed_since (recorded during the deferral, per F-WOC-02) still declines new
        // claims -- this is deliberate: it is what closes WOT-07 (a deferred, at-capacity, past-grace
        // backend becoming claimable the instant a slot frees, "while known-dead").
        Backend backend = activeBackend("mac-mini-6", 1);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(5)));
        when(backendRepository.findByName("mac-mini-6")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(0L); // job just ended

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-6")).isEmpty();
        // The heartbeat-freshness exemption query must never even run for a backend that is not at
        // capacity -- it is irrelevant to the outcome and would be a wasted DB round trip on every claim.
        Mockito.verify(reviewJobRepository, Mockito.never())
                .existsFreshRunningJobForBackend(Mockito.anyLong(), Mockito.any());
    }

    @Test
    void atCapacityWithFreshHeartbeatExemptionIsConsultedButStillDeclinedByTheCapacityCheck() {
        // F-WOC-01: the WOC-13-mirrored exemption exists and is genuinely evaluated (proven here via the
        // heartbeat-freshness query being invoked), but a backend that is truly at capacity is declined
        // by the ordinary capacity check regardless -- the exemption changes no observable claim outcome
        // for this case, only which reason is logged (see BackendDispatcher's javadoc).
        Backend backend = activeBackend("mac-mini-7", 1);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(5)));
        when(backendRepository.findByName("mac-mini-7")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L); // at capacity
        when(reviewJobRepository.existsFreshRunningJobForBackend(Mockito.eq(backend.getId()), Mockito.any()))
                .thenReturn(true);

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-7")).isEmpty();
        Mockito.verify(reviewJobRepository).existsFreshRunningJobForBackend(Mockito.eq(backend.getId()), Mockito.any());
    }

    @Test
    void nonAtCapacityBackendWithAFailedProbeNeverQueriesHeartbeatFreshness() {
        // The exemption's heartbeat-freshness query is short-circuited away entirely for the common case
        // (a backend with spare/no capacity): it can never be exempt, so there is no reason to pay for
        // the extra query.
        Backend backend = activeBackend("mac-mini-8", 2);
        backend.setProbeFailedSince(Instant.now().minus(Duration.ofSeconds(5)));
        when(backendRepository.findByName("mac-mini-8")).thenReturn(Optional.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L); // spare capacity

        assertThat(dispatcher.resolveClaimableBackend("mac-mini-8")).isEmpty();
        Mockito.verify(reviewJobRepository, Mockito.never())
                .existsFreshRunningJobForBackend(Mockito.anyLong(), Mockito.any());
    }

    @Test
    void neverThrowsForAnyDeclineReason() {
        // QA-critical regression guard: resolveClaimableBackend must return Optional.empty() for every
        // decline reason, never throw -- throwing here (even if caught by the caller) crosses this
        // method's own transactional-AOP boundary were it ever re-annotated @Transactional, which is
        // exactly what caused the UnexpectedRollbackException bug this contract change fixes.
        when(backendRepository.findByName("anything")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> dispatcher.resolveClaimableBackend("anything"))
                .doesNotThrowAnyException();
    }
}
