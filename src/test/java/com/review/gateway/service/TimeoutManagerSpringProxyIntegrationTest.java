package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exercises {@code TimeoutManager}/{@code RetryManager} wired as <em>real Spring-proxied beans</em>
 * (via {@code @Import}), unlike every other test in this package which constructs services manually
 * with {@code new X(...)} and therefore never exercises Spring's {@code @Transactional} AOP interceptor
 * at all. In production these ARE real {@code @Service} beans discovered by component-scan, so this is
 * the only way to catch a transaction-propagation defect between them.
 *
 * <p><b>Fixed (previously DEFECT, KD-1/Critical):</b> {@code TimeoutManager.sweepStaleHeartbeats()}/
 * {@code enforceMaxDuration()} call {@code RetryManager.requeueOrFail(...)} — a <em>different</em>
 * {@code @Service} bean, so the call genuinely goes through the Spring AOP proxy (this is not the
 * harmless self-invocation case). Before this fix, both methods were {@code @Transactional(readOnly =
 * true)}; with default propagation ({@code REQUIRED}) and Spring's default
 * {@code validateExistingTransaction=false}, {@code RetryManager}'s writing transaction silently joined
 * the caller's already-read-only physical transaction instead of erroring or opening a new one — so
 * every write {@code RetryManager} performs (the {@code QUEUED}/{@code FAILED} transition, the
 * {@code review_events} insert) executed against a connection PostgreSQL itself enforces as read-only,
 * throwing {@code JpaSystemException: ERROR: cannot execute INSERT in a read-only transaction} and
 * leaving the stuck {@code RUNNING} review wedged forever (architecture §8, requirement 2.7). The fix
 * drops {@code readOnly = true} from both {@code TimeoutManager} methods — they orchestrate writes (via
 * {@code RetryManager}) and must not taint that write with a read-only hint of their own.
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
    void sweepStaleHeartbeatsRequeuesAStuckRunningReviewWhenRunAsRealSpringBeans() {
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
        // Spring-managed, AOP-proxied TimeoutManager/RetryManager beans.
        Long reviewId = review.getId();
        int[] swept = new int[1];
        assertThatCode(() -> swept[0] = timeoutManager.sweepStaleHeartbeats())
                .as("FIXED: TimeoutManager.sweepStaleHeartbeats() no longer carries readOnly=true, so "
                        + "RetryManager.requeueOrFail(...)'s write transaction is no longer tainted by a "
                        + "read-only hint from the caller")
                .doesNotThrowAnyException();

        assertThat(swept[0]).isEqualTo(1);

        // The review must actually have moved back to QUEUED (attempts=1 < default max-attempts=3), with
        // a RETRY event recorded -- exactly what the heartbeat sweep exists to guarantee (req. 1.7/2.7).
        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.QUEUED);

        List<com.review.gateway.model.ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId);
        assertThat(events).extracting(com.review.gateway.model.ReviewEvent::getEventType).contains(EventType.RETRY);
    }

    @Test
    void enforceMaxDurationFailsAStuckRunningReviewOnceAttemptsAreExhaustedWhenRunAsRealSpringBeans() {
        Backend backend = backendRepository.saveAndFlush(new Backend("tm-proxy-backend-2", "https://tm2.local", "model", 1));

        Review review = new Review(1L, 2L, "sha-max-duration", "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(3); // == default max-attempts -> this sweep must FAIL it, not requeue it
        review = reviewRepository.saveAndFlush(review);

        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStartedAt(Instant.now().minus(Duration.ofMinutes(60))); // way past the 45m default cap
        job.setHeartbeatAt(Instant.now()); // fresh heartbeat -- only the max-duration backstop should catch this
        reviewJobRepository.saveAndFlush(job);

        Long reviewId = review.getId();
        int[] swept = new int[1];
        assertThatCode(() -> swept[0] = timeoutManager.enforceMaxDuration())
                .as("FIXED: TimeoutManager.enforceMaxDuration() no longer carries readOnly=true")
                .doesNotThrowAnyException();

        assertThat(swept[0]).isEqualTo(1);

        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.FAILED);

        List<com.review.gateway.model.ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId);
        assertThat(events).extracting(com.review.gateway.model.ReviewEvent::getEventType).contains(EventType.FAILED);
    }
}
