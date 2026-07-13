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

/**
 * DEFECT REPRODUCTION (QA finding, see feature/03-api-security QA report — severity Critical). Every
 * dev-written test of the "claim declines gracefully" paths ({@code QueueManagerIntegrationTest
 * .claimReturnsEmptyWhenBackendIsNotActive}/{@code .claimReturnsEmptyWhenBackendIsAtCapacity})
 * constructs {@code new QueueManager(...)} directly — a plain object, with NO Spring AOP/transaction
 * proxy applied — so those tests never exercise the real {@code @Transactional} interaction and pass
 * despite the underlying bug. This test uses the REAL Spring-managed ({@code @Autowired}) bean, which
 * is what {@code JobController} actually calls in production, and reproduces:
 *
 * <p>{@code QueueManager.claim} (REQUIRES_NEW) calls {@code BackendDispatcher.resolveClaimableBackend}
 * (its own, separate {@code @Transactional(readOnly = true)} method, propagation REQUIRED so it joins
 * the same physical transaction). When the backend is unknown / not ACTIVE / at capacity, that method
 * throws {@code JobNotClaimableException} (a {@code RuntimeException}). Spring's transactional AOP
 * advice on {@code resolveClaimableBackend} sees the exception cross ITS OWN proxy boundary and, because
 * it is not the transaction's physical owner (it only joined an already-open one), marks the shared
 * physical transaction rollback-only before rethrowing. {@code QueueManager.claim} then catches the
 * exception (as its javadoc documents) and returns {@code Optional.empty()} normally -- but when
 * {@code claim}'s own transactional advice subsequently tries to COMMIT that same physical transaction,
 * it discovers it was already marked rollback-only and throws {@code UnexpectedRollbackException}
 * instead, which is NOT caught anywhere and reaches {@code GlobalExceptionHandler}'s generic handler as
 * an unmapped {@code 500}.
 *
 * <p><b>Production impact:</b> every {@code POST /jobs/claim} where the named backend is unknown,
 * {@code SUSPECT}/{@code MAINTENANCE}/{@code OFFLINE}, or already at capacity -- ALL THREE explicitly
 * documented as normal, expected, everyday "nothing to claim right now" outcomes that must yield
 * {@code 204} (architecture §5, {@code JobController} javadoc) -- instead return an opaque {@code 500}.
 * Since "backend at capacity" is an ordinary, frequent condition under normal load (a Worker polling
 * while its one concurrent slot is occupied), this breaks the core claim-polling contract, not just an
 * edge case.
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
    void claimForAnUnknownBackendMustReturnEmptyNot500() {
        // No backend row at all -> BackendDispatcher.resolveClaimableBackend throws JobNotClaimableException.
        assertThat(queueManager.claim("this-backend-does-not-exist", "worker-1"))
                .as("BUG (Critical): documented contract is Optional.empty() -> HTTP 204; the real "
                        + "Spring-managed bean instead throws UnexpectedRollbackException here, which "
                        + "JobController does not catch, surfacing as an unmapped HTTP 500")
                .isEmpty();
    }

    @Test
    void claimForABackendNotActiveMustReturnEmptyNot500() {
        Backend backend = new Backend("mac-mini-suspect-qa", "http://192.168.1.90:8080", "model-x", 2);
        backend.setStatus(BackendStatus.SUSPECT);
        backendRepository.saveAndFlush(backend);

        Review review = new Review(1L, 1L, "sha-qa-suspect", "base", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        Review saved = reviewRepository.saveAndFlush(review);
        reviewInputRepository.saveAndFlush(new ReviewInput(saved.getId(), "diff", "v1", "sha-qa-suspect", "base", 10));

        assertThat(queueManager.claim("mac-mini-suspect-qa", "worker-1"))
                .as("BUG (Critical): a SUSPECT backend is a routine, expected state (auto health-check "
                        + "demotion) -- claim must decline with Optional.empty()/204, not 500")
                .isEmpty();
    }

    @Test
    void claimForABackendAtCapacityMustReturnEmptyNot500() {
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

        assertThat(queueManager.claim("mac-mini-full-qa", "worker-1"))
                .as("BUG (Critical): a backend at capacity is the single most routine claim-decline "
                        + "reason under normal load -- must be Optional.empty()/204, not 500")
                .isEmpty();
    }
}
