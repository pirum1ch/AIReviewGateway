package com.review.gateway.service;

import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA regression (previously DEFECT, Critical, now FIXED): every dev-written test of the "claim
 * declines gracefully" paths ({@code QueueManagerIntegrationTest
 * .claimReturnsEmptyWhenBackendIsNotActive}/{@code .claimReturnsEmptyWhenBackendIsAtCapacity})
 * constructs {@code new QueueManager(...)} directly — a plain object, with NO Spring AOP/transaction
 * proxy applied — so those tests never exercised the real {@code @Transactional} interaction where
 * this bug lived. This test uses the REAL Spring-managed ({@code @Autowired}) bean, which is what
 * {@code JobController} actually calls in production.
 *
 * <p><b>Fixed:</b> {@code BackendDispatcher.resolveClaimableBackend} no longer throws {@code
 * JobNotClaimableException} (a {@code RuntimeException} that, when the method was {@code
 * @Transactional(readOnly = true)}, tripped Spring's transactional AOP advice into marking the whole
 * shared physical transaction — {@code QueueManager.claim}'s own {@code REQUIRES_NEW} transaction,
 * which {@code resolveClaimableBackend} was joining via propagation REQUIRED — rollback-only before
 * {@code claim} ever got a chance to catch it, so the later commit failed with {@code
 * UnexpectedRollbackException} instead of the intended, routine {@code 204}). It now returns {@code
 * Optional.empty()} for "unknown backend" / "not ACTIVE" / "at capacity" and carries no
 * {@code @Transactional} of its own (it only ever runs inside {@code claim}'s already-open
 * transaction), so no exception ever crosses a transactional-AOP proxy boundary in this path.
 *
 * <p><b>Production impact (fixed):</b> every {@code POST /jobs/claim} where the named backend is
 * unknown, {@code SUSPECT}/{@code MAINTENANCE}/{@code OFFLINE}, or already at capacity — ALL THREE
 * explicitly documented as normal, expected, everyday "nothing to claim right now" outcomes (architecture
 * §5, {@code JobController} javadoc) — now correctly yield {@code Optional.empty()} (204), including
 * under the real Spring-managed transactional proxy exercised here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class BackendDispatcherClaimDeclineTransactionBugTest {

    @Autowired
    private QueueManager queueManager; // the REAL Spring-managed (transactional-proxy-wrapped) bean
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    @Test
    void claimForAnUnknownBackendReturnsEmptyNot500() {
        // No backend row at all -> BackendDispatcher.resolveClaimableBackend returns Optional.empty().
        assertThatCode(() -> {
            var result = queueManager.claim("this-backend-does-not-exist", "worker-1");
            assertThat(result)
                    .as("FIXED: documented contract is Optional.empty() -> HTTP 204, no exception")
                    .isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void claimForABackendNotActiveReturnsEmptyNot500() {
        Backend backend = new Backend("mac-mini-suspect-qa", "http://192.168.1.90:8080", "model-x", 2);
        backend.setStatus(BackendStatus.SUSPECT);
        backendRepository.saveAndFlush(backend);

        Review review = new Review(1L, 1L, "sha-qa-suspect", "base", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        Review saved = reviewRepository.saveAndFlush(review);
        reviewInputRepository.saveAndFlush(new ReviewInput(saved.getId(), "diff", "v1", "sha-qa-suspect", "base", 10));

        assertThatCode(() -> {
            var result = queueManager.claim("mac-mini-suspect-qa", "worker-1");
            assertThat(result)
                    .as("FIXED: a SUSPECT backend is a routine, expected state (auto health-check "
                            + "demotion) -- claim declines with Optional.empty()/204, not 500")
                    .isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void claimForABackendAtCapacityReturnsEmptyNot500() {
        Backend backend = new Backend("mac-mini-full-qa", "http://192.168.1.91:8080", "model-x", 1);
        backend.setStatus(BackendStatus.ACTIVE);
        Backend savedBackend = backendRepository.saveAndFlush(backend);

        Review runningElsewhere = new Review(1L, 2L, "sha-qa-running", "base", "v1", 10);
        runningElsewhere.setStatus(ReviewStatus.RUNNING);
        Review savedRunning = reviewRepository.saveAndFlush(runningElsewhere);
        reviewJobRepository.saveAndFlush(new ReviewJob(savedRunning.getId(), savedBackend.getId(), "worker-existing"));

        Review queued = new Review(1L, 3L, "sha-qa-queued", "base", "v1", 10);
        queued.setStatus(ReviewStatus.QUEUED);
        Review savedQueued = reviewRepository.saveAndFlush(queued);
        reviewInputRepository.saveAndFlush(new ReviewInput(savedQueued.getId(), "diff", "v1", "sha-qa-queued", "base", 10));

        assertThatCode(() -> {
            var result = queueManager.claim("mac-mini-full-qa", "worker-1");
            assertThat(result)
                    .as("FIXED: a backend at capacity is the single most routine claim-decline reason "
                            + "under normal load -- Optional.empty()/204, not 500")
                    .isEmpty();
        }).doesNotThrowAnyException();
    }
}
