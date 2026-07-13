package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduces {@code TimeoutManager}/{@code RetryManager} wired as <em>real Spring-proxied beans</em>
 * (via {@code @Import}), unlike every other test in this package which constructs services manually
 * with {@code new X(...)} and therefore never exercises Spring's {@code @Transactional} AOP interceptor
 * at all. In production these ARE real {@code @Service} beans discovered by component-scan, so this is
 * the only way to catch a transaction-propagation defect between them.
 *
 * <p>{@code TimeoutManager.sweepStaleHeartbeats()}/{@code enforceMaxDuration()} are annotated
 * {@code @Transactional(readOnly = true)} and, within that same method body, call
 * {@code RetryManager.requeueOrFail(...)} — a <em>different</em> {@code @Service} bean, so the call
 * genuinely goes through the Spring AOP proxy (this is not the harmless self-invocation case). With
 * default propagation ({@code REQUIRED}) and Spring's default
 * {@code validateExistingTransaction=false}, {@code RetryManager}'s writing transaction silently joins
 * the caller's already-read-only physical transaction instead of erroring or opening a new one — so
 * every write {@code RetryManager} performs (the {@code QUEUED}/{@code FAILED} transition, the
 * {@code review_events} insert) executes against a connection PostgreSQL itself treats as read-only,
 * which the driver enforces at the server side.
 *
 * <p><b>DEFECT (Critical, confirmed empirically against real Postgres):</b> calling
 * {@code TimeoutManager.sweepStaleHeartbeats()}/{@code enforceMaxDuration()} as genuine Spring beans
 * throws {@code JpaSystemException: ERROR: cannot execute INSERT in a read-only transaction} from
 * inside {@code EventService.record(...)} the very first time it finds a stale/over-duration job, and
 * the {@code reviews} row is never actually moved to {@code QUEUED}/{@code FAILED} (the whole physical
 * transaction rolls back). See {@code docs/implementation-architecture.md} §8: this sweep is the
 * *only* mechanism that reclaims a stuck {@code RUNNING} review (requirement 2.7); with this defect
 * present, every stuck review stays wedged in {@code RUNNING} forever once feature/03 wires the
 * {@code @Scheduled} driver, and the scheduled task itself throws on every tick it finds work to do.
 * Root cause: {@code TimeoutManager.sweepStaleHeartbeats}/{@code enforceMaxDuration} are annotated
 * {@code @Transactional(readOnly = true)} (TimeoutManager.java) while calling {@code RetryManager}
 * (a writing {@code @Transactional} bean) in the same call graph; Spring joins the writing call into
 * the caller's already-read-only physical transaction by default
 * ({@code validateExistingTransaction=false}) instead of rejecting or isolating it. Suggested fix:
 * drop {@code readOnly = true} from both {@code TimeoutManager} methods (they are read/query-and-
 * delegate driver methods; the actual writes belong to {@code RetryManager}'s own transaction and
 * should not be tainted by the caller's).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TimeoutManager.class, RetryManager.class, StateMachine.class, EventService.class, GatewayProperties.class})
class TimeoutManagerSpringProxyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private TimeoutManager timeoutManager;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    @Test
    void sweepStaleHeartbeatsThrowsInsteadOfRequeuingAStuckRunningReviewWhenRunAsRealSpringBeans() {
        Backend backend = backendRepository.saveAndFlush(new Backend("tm-proxy-backend", "https://tm.local", "model", 1));

        Review review = new Review(1L, 1L, "sha-stale", "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(1);
        review = reviewRepository.saveAndFlush(review);

        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStartedAt(Instant.now().minus(Duration.ofMinutes(10)));
        job.setHeartbeatAt(Instant.now().minus(Duration.ofMinutes(10))); // way past the 180s default timeout
        reviewJobRepository.saveAndFlush(job);

        // This is the exact call HeartbeatChecker's @Scheduled driver makes in production, against real
        // Spring-managed, AOP-proxied TimeoutManager/RetryManager beans. Expected (correct) behavior
        // would be: swept == 1 and the review moves to QUEUED with a RETRY event recorded. Actual
        // (buggy) behavior: the read-only transaction TimeoutManager opened is silently reused for
        // RetryManager's write, and PostgreSQL rejects the write outright.
        Long reviewId = review.getId();
        assertThatThrownBy(() -> timeoutManager.sweepStaleHeartbeats())
                .as("DEFECT: TimeoutManager.sweepStaleHeartbeats() is @Transactional(readOnly = true) "
                        + "(TimeoutManager.java) but calls RetryManager.requeueOrFail(...), a writing bean, "
                        + "in the same call graph -> the write joins the read-only transaction and PostgreSQL "
                        + "rejects it, instead of the stuck review being requeued/failed")
                .isInstanceOf(JpaSystemException.class)
                .hasMessageContaining("read-only transaction");

        // The review is left permanently stuck in RUNNING -- exactly the outcome the heartbeat sweep
        // exists to prevent (architecture §8, requirement 2.7).
        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.RUNNING);
        assertThat(reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId)).isEmpty();
    }
}
