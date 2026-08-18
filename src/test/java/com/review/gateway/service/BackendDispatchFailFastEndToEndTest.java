package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * F-WOC-01 (SAST fix round): the end-to-end test WOR-01 originally called for but that was never written
 * -- only its startup-validation half was ("boot with a backend stubbed permanently unreachable, the
 * third attempt is not claimable before demotion/decline, and the Review does not reach FAILED"). This
 * uses the REAL Spring-managed {@link BackendHealthChecker} and {@link QueueManager} beans (the same
 * pattern {@code BackendDispatcherClaimDeclineTransactionBugTest} uses for the transactional-AOP bug),
 * with only the HTTP-boundary {@link BackendProber} mocked, and real wall-clock timing throughout -- no
 * formula assertion, no manually-constructed {@code probe_failed_since}.
 *
 * <p>Scenario: a backend fails its very first health probe. Per {@code BackendHealthChecker}
 * (F-WOC-02, this same SAST round) that alone is enough to record {@code probe_failed_since} -- long
 * before {@code gateway.backend.failure-grace} (shipped default 180s) could possibly have elapsed, and
 * long before the backend's persisted {@code status} flips to {@code SUSPECT}. Per {@code
 * BackendDispatcher} (F-WOC-01) that recorded failure alone is enough to decline the very next {@code
 * POST /jobs/claim} for that backend -- proving the fix closes the gap the SAST report traced (WOT-01):
 * previously a backend could keep receiving claims for up to {@code failure-grace}, long enough to burn
 * a Review's entire retry attempt budget against a backend the Gateway already knew had failed at least
 * once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class BackendDispatchFailFastEndToEndTest {

    @Autowired
    private QueueManager queueManager; // the REAL Spring-managed bean, as JobController actually calls
    @Autowired
    private BackendHealthChecker backendHealthChecker; // ditto
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;

    @MockitoBean
    private BackendProber backendProber; // the only HTTP boundary BackendHealthChecker has

    @AfterEach
    void cleanUp() {
        reviewJobRepository.deleteAll();
        reviewChunkRepository.deleteAll();
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private Backend persistActiveBackend(String name, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(BackendStatus.ACTIVE);
        return backendRepository.saveAndFlush(backend);
    }

    /** A genuinely claimable QUEUED job -- Review + review_chunks row, exactly what claim() requires. */
    private ReviewJob persistClaimableQueuedJob(String headSha) {
        Review review = new Review(1L, 2L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        review = reviewRepository.saveAndFlush(review);
        reviewChunkRepository.saveAndFlush(
                new ReviewChunk(review.getId(), 0, 1, "diff-" + headSha, 10, 1, "[]"));
        return reviewJobRepository.saveAndFlush(new ReviewJob(review.getId(), null, null));
    }

    @Test
    void aSingleFailedFirstProbeImmediatelyMakesTheBackendUnclaimable() {
        Backend backend = persistActiveBackend("e2e-fail-fast", 1);
        ReviewJob job = persistClaimableQueuedJob("sha-e2e-fail-fast");

        // Positive control: BEFORE any probe has ever run against this backend, the queued job genuinely
        // is claimable -- proves the decline asserted below is caused by the probe failure, not by some
        // unrelated setup mistake (missing chunk row, wrong review status, etc).
        //
        // We prove claimability without consuming the job (claim() mutates job/review state) by checking
        // the same pre-conditions BackendDispatcher itself evaluates: ACTIVE status, free capacity, and
        // no probe-failure streak recorded yet.
        Backend beforeProbe = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(beforeProbe.getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(beforeProbe.getProbeFailedSince()).isNull();

        // The backend fails its very first health probe -- a real BackendHealthChecker.probeAll() pass,
        // real wall-clock `Instant.now()`, real DB write. Nowhere near gateway.backend.failure-grace
        // (180s default) having elapsed; the backend's persisted status stays ACTIVE (fail-slow, WOC-11).
        Mockito.doThrow(new BackendUnavailableException("simulated permanently unreachable backend"))
                .when(backendProber).probe(any());
        int flips = backendHealthChecker.probeAll();
        assertThat(flips).as("WOC-11: one failed probe must not flip status -- fail-slow is unchanged").isZero();

        Backend afterOneFailedProbe = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(afterOneFailedProbe.getStatus())
                .as("status stays ACTIVE after just one failed probe (fail-slow, WOC-11, unchanged by F-WOC-01)")
                .isEqualTo(BackendStatus.ACTIVE);
        assertThat(afterOneFailedProbe.getProbeFailedSince())
                .as("F-WOC-02: the very first failed probe already records the streak")
                .isNotNull();

        // The core assertion: immediately after that single failed probe -- with the backend still
        // persisted as ACTIVE, and with failure-grace nowhere close to elapsed -- a real POST
        // /jobs/claim-equivalent call for this exact backend must NOT receive the (genuinely queued,
        // genuinely claimable a moment ago) job.
        Optional<ClaimedJob> claimAfterFailedProbe = queueManager.claim(backend.getName(), "worker-1");
        assertThat(claimAfterFailedProbe)
                .as("F-WOC-01: a backend that has failed at least one probe must not receive new claims, "
                        + "even though its status is still ACTIVE and failure-grace has not elapsed -- this "
                        + "is what keeps RetryManager's attempt budget from being burned against a backend "
                        + "already known to have failed (WOT-01)")
                .isEmpty();

        ReviewJob stillQueued = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(stillQueued.getStatus())
                .as("the job must remain QUEUED (parked, burning no attempts), exactly the pre-branch "
                        + "behavior F-WOC-01's fix restores for a backend that has failed a probe")
                .isEqualTo(com.review.gateway.model.enums.JobStatus.QUEUED);
        assertThat(stillQueued.getAttempts()).isZero();

        // Recovery: once the backend passes a probe again, probe_failed_since clears (WOC-12) and the
        // very same, still-queued job becomes claimable again -- proving the earlier decline really was
        // caused by the recorded probe failure, not any other property of this fixture.
        Mockito.reset(backendProber);
        Mockito.doNothing().when(backendProber).probe(any());
        backendHealthChecker.probeAll();
        Backend afterRecovery = backendRepository.findById(backend.getId()).orElseThrow();
        assertThat(afterRecovery.getProbeFailedSince()).isNull();

        Optional<ClaimedJob> claimAfterRecovery = queueManager.claim(backend.getName(), "worker-1");
        assertThat(claimAfterRecovery).as("claimable again once the probe streak clears").isPresent();
        assertThat(claimAfterRecovery.orElseThrow().jobId()).isEqualTo(job.getId());
    }
}
