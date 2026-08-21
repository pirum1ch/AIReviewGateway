package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces appsec's F-DC-12 finding on real (Zonky) PostgreSQL: {@code ReviewService.sweepObsolete}'s
 * post-lock re-check reads a <em>stale, pre-lock</em> in-session {@code Review} instance rather than the
 * fresh row its {@code FOR NO KEY UPDATE} lock just protected, because the unlocked candidate query and
 * the per-row native lock query run in the same persistence-context session. A Review that is published
 * concurrently, in the window between the candidate read and this row's lock acquisition, is silently
 * overwritten {@code PUBLISHED -> OBSOLETE} — a transition {@link StateMachine} itself would reject as
 * illegal if it were ever shown the true current status.
 *
 * <p>Synchronization is done via {@code pg_locks} polling (no sleeps/guessed timing, matching appsec's
 * own methodology): the "publisher" thread holds the parent lock open while the "sweep" thread's
 * candidate read (unlocked, so unaffected) completes and its own lock attempt genuinely blocks behind
 * the publisher's lock — confirmed via {@code pg_locks WHERE NOT granted} before the publisher is
 * allowed to flip the status and commit (releasing the lock the sweep thread is waiting on).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SweepObsoleteStaleReadTest extends AbstractPostgresIntegrationTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long MR_ID = 777L;

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
    }

    private ReviewService newReviewService() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setEnabled(false);
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        DeduplicationService deduplicationService = new DeduplicationService(reviewRepository);
        DiffSizeValidator diffSizeValidator = new DiffSizeValidator(properties);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        DiffChunker diffChunker = new DiffChunker(properties, diffSizeValidator, chunkContextRenderer);
        PromptManager promptManager = new PromptManager(properties, Mockito.mock(GitLabClient.class),
                new PromptSourceResolver(properties), new PromptAssembler(properties, diffSizeValidator),
                new TextSanitizer());
        return new ReviewService(reviewRepository, reviewInputRepository, reviewChunkRepository,
                reviewJobRepository, reviewCommentRepository, reviewPromptSectionRepository, deduplicationService,
                diffSizeValidator, diffChunker, chunkContextRenderer, promptManager, eventService, stateMachine,
                jobStateMachine, entityManager, transactionManager);
    }

    @Test
    void reviewPublishedConcurrentlyWithSweepObsoleteIsNeverOverwrittenToObsolete() throws Exception {
        Review review = new Review(PROJECT_ID, MR_ID, "sha-old", "base", "v1", 10);
        review.setStatus(ReviewStatus.COMPLETED);
        Review saved = reviewRepository.saveAndFlush(review);
        Long reviewId = saved.getId();

        ReviewService reviewService = newReviewService();

        CountDownLatch publisherHasLock = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // "Publisher": locks the review row (simulating GitLabPublisher's own parent-lock path),
            // holds it open, then (once told to) flips status to PUBLISHED and commits -- releasing the
            // lock sweepObsolete's own lock attempt below will be blocked on.
            Future<?> publisher = executor.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.executeWithoutResult(status -> {
                    Review locked = reviewRepository.findByIdForNoKeyUpdate(reviewId).orElseThrow();
                    publisherHasLock.countDown();
                    awaitQuietly(releaseLock);
                    locked.setStatus(ReviewStatus.PUBLISHED);
                    reviewRepository.save(locked);
                });
            });

            assertThat(publisherHasLock.await(10, TimeUnit.SECONDS))
                    .as("publisher must have acquired the row lock before sweepObsolete starts")
                    .isTrue();

            // Trigger sweepObsolete (via createReview for a new head_sha of the same project/MR): its
            // unlocked candidate read succeeds immediately (review is still COMPLETED, publisher hasn't
            // committed yet) and loads the entity into its session; its subsequent per-row
            // findByIdForNoKeyUpdate then blocks behind the publisher's lock.
            Future<CreateReviewCommand> sweepTrigger = executor.submit(() -> {
                reviewService.createReview(new CreateReviewCommand(
                        PROJECT_ID, MR_ID, "sha-new", "base", "diff --git a/X b/X\n--- a/X\n+++ b/X\n@@ -1,1 +1,1 @@\n+x\n",
                        "v1", 10));
                return null;
            });

            // Deterministic sync (no sleeps): poll pg_locks until the sweep thread's lock request is
            // genuinely waiting, i.e. it has already run its (unlocked, so unaffected) candidate query
            // with the stale COMPLETED status and is now blocked in findByIdForNoKeyUpdate.
            assertThat(awaitAWaitingLockOnReviews(5_000))
                    .as("sweepObsolete's per-row lock attempt must actually be blocked behind the publisher's lock")
                    .isTrue();

            // Now let the publisher finish: flips to PUBLISHED and commits, releasing the row lock.
            releaseLock.countDown();
            publisher.get(20, TimeUnit.SECONDS);
            sweepTrigger.get(20, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("F-DC-12: a Review published concurrently with sweepObsolete's stale-instance re-check "
                        + "must stay PUBLISHED, never be silently overwritten to OBSOLETE by a transition "
                        + "the StateMachine would reject if it saw the true current status")
                .isEqualTo(ReviewStatus.PUBLISHED);
    }

    /**
     * Polls {@code pg_locks} (via its own short-lived transaction/connection) for a genuinely ungranted
     * lock request, confirming the sweep thread is truly blocked rather than guessing via a sleep.
     *
     * <p>A blocked {@code FOR NO KEY UPDATE} request on an already-locked row does not itself show up as
     * an ungranted {@code tuple} lock in {@code pg_locks} — PostgreSQL's row-locking implementation has
     * the waiter instead request a {@code ShareLock} on the holder's {@code transactionid} (i.e. "wait for
     * that transaction to end"), which is what actually appears here with {@code granted = false} (looked
     * this up empirically by dumping the full {@code pg_locks} table during a first, failed attempt at
     * this test that over-filtered on {@code relation = 'reviews'} and never found the transactionid-typed
     * wait). Since this test's only concurrent activity is the two threads it starts itself, any ungranted
     * lock is unambiguously the sweep thread waiting on the publisher.
     */
    private boolean awaitAWaitingLockOnReviews(long timeoutMillis) throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long waitingLocks = tx.execute(status -> ((Number) entityManager
                    .createNativeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted").getSingleResult()).longValue());
            if (waitingLocks > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
