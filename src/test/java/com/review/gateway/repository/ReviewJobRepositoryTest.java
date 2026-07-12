package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewJobRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewJobRepository reviewJobRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Review persistReview(long projectId, long mrId, String headSha, ReviewStatus status) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", 10);
        review.setStatus(status);
        return entityManager.persistFlushFind(review);
    }

    private Backend persistBackend(String name) {
        return entityManager.persistFlushFind(new Backend(name, "https://" + name + ".local", "model-x", 2));
    }

    @Test
    void findByReviewIdReturnsTheAttachedJob() {
        Review review = persistReview(1L, 1L, "sha-1", ReviewStatus.RUNNING);
        Backend backend = persistBackend("backend-lookup");
        ReviewJob job = entityManager.persistFlushFind(new ReviewJob(review.getId(), backend.getId(), "worker-1"));

        Optional<ReviewJob> found = reviewJobRepository.findByReviewId(review.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(job.getId());
    }

    @Test
    void findByReviewIdReturnsEmptyWhenNoJobExists() {
        Review review = persistReview(1L, 2L, "sha-2", ReviewStatus.NEW);

        assertThat(reviewJobRepository.findByReviewId(review.getId())).isEmpty();
    }

    @Test
    void countRunningJobsForBackendCountsOnlyRunningReviewsOnThatBackend() {
        Backend backendA = persistBackend("backend-a");
        Backend backendB = persistBackend("backend-b");

        Review running1 = persistReview(2L, 10L, "sha-run-1", ReviewStatus.RUNNING);
        entityManager.persistAndFlush(new ReviewJob(running1.getId(), backendA.getId(), "worker-1"));

        Review running2 = persistReview(2L, 11L, "sha-run-2", ReviewStatus.RUNNING);
        entityManager.persistAndFlush(new ReviewJob(running2.getId(), backendA.getId(), "worker-2"));

        // Same backend, but the review is COMPLETED -> must not count toward capacity.
        Review completed = persistReview(2L, 12L, "sha-completed", ReviewStatus.COMPLETED);
        entityManager.persistAndFlush(new ReviewJob(completed.getId(), backendA.getId(), "worker-3"));

        // Running, but on a different backend -> must not count for backendA.
        Review runningOtherBackend = persistReview(2L, 13L, "sha-run-other", ReviewStatus.RUNNING);
        entityManager.persistAndFlush(new ReviewJob(runningOtherBackend.getId(), backendB.getId(), "worker-4"));

        assertThat(reviewJobRepository.countRunningJobsForBackend(backendA.getId())).isEqualTo(2);
        assertThat(reviewJobRepository.countRunningJobsForBackend(backendB.getId())).isEqualTo(1);
    }

    @Test
    void findReviewIdsWithStaleHeartbeatFindsMissedAndNullHeartbeats() {
        Backend backend = persistBackend("backend-heartbeat");
        Instant cutoff = Instant.now().minus(3, ChronoUnit.MINUTES);

        Review stale = persistReview(3L, 20L, "sha-stale", ReviewStatus.RUNNING);
        ReviewJob staleJob = new ReviewJob(stale.getId(), backend.getId(), "worker-1");
        staleJob.setHeartbeatAt(cutoff.minus(1, ChronoUnit.MINUTES));
        entityManager.persistAndFlush(staleJob);

        Review neverPinged = persistReview(3L, 21L, "sha-never", ReviewStatus.RUNNING);
        ReviewJob neverPingedJob = new ReviewJob(neverPinged.getId(), backend.getId(), "worker-2");
        // heartbeatAt left null -> must still be treated as stale
        entityManager.persistAndFlush(neverPingedJob);

        Review fresh = persistReview(3L, 22L, "sha-fresh", ReviewStatus.RUNNING);
        ReviewJob freshJob = new ReviewJob(fresh.getId(), backend.getId(), "worker-3");
        freshJob.setHeartbeatAt(Instant.now());
        entityManager.persistAndFlush(freshJob);

        Review staleButNotRunning = persistReview(3L, 23L, "sha-obsolete", ReviewStatus.OBSOLETE);
        ReviewJob staleButNotRunningJob = new ReviewJob(staleButNotRunning.getId(), backend.getId(), "worker-4");
        staleButNotRunningJob.setHeartbeatAt(cutoff.minus(1, ChronoUnit.HOURS));
        entityManager.persistAndFlush(staleButNotRunningJob);

        List<Long> staleReviewIds = reviewJobRepository.findReviewIdsWithStaleHeartbeat(cutoff);

        assertThat(staleReviewIds).containsExactlyInAnyOrder(stale.getId(), neverPinged.getId());
    }

    @Test
    void findReviewIdsExceedingMaxDurationOnlyMatchesRunningJobsPastTheCap() {
        Backend backend = persistBackend("backend-duration");
        Instant cutoff = Instant.now().minus(45, ChronoUnit.MINUTES);

        Review longRunning = persistReview(4L, 30L, "sha-long", ReviewStatus.RUNNING);
        ReviewJob longRunningJob = new ReviewJob(longRunning.getId(), backend.getId(), "worker-1");
        longRunningJob.setStartedAt(cutoff.minus(5, ChronoUnit.MINUTES));
        entityManager.persistAndFlush(longRunningJob);

        Review recentlyStarted = persistReview(4L, 31L, "sha-recent", ReviewStatus.RUNNING);
        ReviewJob recentJob = new ReviewJob(recentlyStarted.getId(), backend.getId(), "worker-2");
        recentJob.setStartedAt(Instant.now());
        entityManager.persistAndFlush(recentJob);

        Review notStartedYet = persistReview(4L, 32L, "sha-not-started", ReviewStatus.RUNNING);
        ReviewJob notStartedJob = new ReviewJob(notStartedYet.getId(), backend.getId(), "worker-3");
        entityManager.persistAndFlush(notStartedJob); // startedAt null -> never matches

        List<Long> exceeded = reviewJobRepository.findReviewIdsExceedingMaxDuration(cutoff);

        assertThat(exceeded).containsExactly(longRunning.getId());
    }
}
