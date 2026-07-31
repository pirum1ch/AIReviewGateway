package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 (diff chunking): {@code review_jobs} is now the queue owner — every query here is driven by
 * {@code ReviewJob.status}, not the parent {@code Review.status}.
 */
class ReviewJobRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewJobRepository reviewJobRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Review persistReview(long projectId, long mrId, String headSha) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", 10);
        return entityManager.persistFlushFind(review);
    }

    private Backend persistBackend(String name) {
        return entityManager.persistFlushFind(new Backend(name, "https://" + name + ".local", "model-x", 2));
    }

    private ReviewJob jobWithStatus(Review review, Backend backend, String workerId, JobStatus status) {
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), workerId);
        job.setStatus(status);
        return job;
    }

    @Test
    void findByReviewIdAndChunkIndexReturnsTheAttachedJob() {
        Review review = persistReview(1L, 1L, "sha-1");
        Backend backend = persistBackend("backend-lookup");
        ReviewJob job = entityManager.persistFlushFind(jobWithStatus(review, backend, "worker-1", JobStatus.RUNNING));

        Optional<ReviewJob> found = reviewJobRepository.findByReviewIdAndChunkIndex(review.getId(), 0);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(job.getId());
    }

    @Test
    void findByReviewIdAndChunkIndexReturnsEmptyWhenNoJobExists() {
        Review review = persistReview(1L, 2L, "sha-2");

        assertThat(reviewJobRepository.findByReviewIdAndChunkIndex(review.getId(), 0)).isEmpty();
    }

    @Test
    void countRunningJobsForBackendCountsOnlyRunningJobsOnThatBackend() {
        Backend backendA = persistBackend("backend-a");
        Backend backendB = persistBackend("backend-b");

        Review running1 = persistReview(2L, 10L, "sha-run-1");
        entityManager.persistAndFlush(jobWithStatus(running1, backendA, "worker-1", JobStatus.RUNNING));

        Review running2 = persistReview(2L, 11L, "sha-run-2");
        entityManager.persistAndFlush(jobWithStatus(running2, backendA, "worker-2", JobStatus.RUNNING));

        // Same backend, but the job itself is COMPLETED -> must not count toward capacity.
        Review completed = persistReview(2L, 12L, "sha-completed");
        entityManager.persistAndFlush(jobWithStatus(completed, backendA, "worker-3", JobStatus.COMPLETED));

        // Running, but on a different backend -> must not count for backendA.
        Review runningOtherBackend = persistReview(2L, 13L, "sha-run-other");
        entityManager.persistAndFlush(jobWithStatus(runningOtherBackend, backendB, "worker-4", JobStatus.RUNNING));

        assertThat(reviewJobRepository.countRunningJobsForBackend(backendA.getId())).isEqualTo(2);
        assertThat(reviewJobRepository.countRunningJobsForBackend(backendB.getId())).isEqualTo(1);
    }

    @Test
    void findJobIdsWithStaleHeartbeatFindsMissedAndNullHeartbeats() {
        Backend backend = persistBackend("backend-heartbeat");
        Instant cutoff = Instant.now().minus(3, ChronoUnit.MINUTES);

        Review stale = persistReview(3L, 20L, "sha-stale");
        ReviewJob staleJob = jobWithStatus(stale, backend, "worker-1", JobStatus.RUNNING);
        staleJob.setHeartbeatAt(cutoff.minus(1, ChronoUnit.MINUTES));
        ReviewJob staleJobSaved = entityManager.persistFlushFind(staleJob);

        Review neverPinged = persistReview(3L, 21L, "sha-never");
        ReviewJob neverPingedJob = jobWithStatus(neverPinged, backend, "worker-2", JobStatus.RUNNING);
        // heartbeatAt left null -> must still be treated as stale
        ReviewJob neverPingedJobSaved = entityManager.persistFlushFind(neverPingedJob);

        Review fresh = persistReview(3L, 22L, "sha-fresh");
        ReviewJob freshJob = jobWithStatus(fresh, backend, "worker-3", JobStatus.RUNNING);
        freshJob.setHeartbeatAt(Instant.now());
        entityManager.persistAndFlush(freshJob);

        Review staleButNotRunning = persistReview(3L, 23L, "sha-obsolete");
        ReviewJob staleButNotRunningJob = jobWithStatus(staleButNotRunning, backend, "worker-4", JobStatus.OBSOLETE);
        staleButNotRunningJob.setHeartbeatAt(cutoff.minus(1, ChronoUnit.HOURS));
        entityManager.persistAndFlush(staleButNotRunningJob);

        List<Long> staleJobIds = reviewJobRepository.findJobIdsWithStaleHeartbeat(cutoff);

        assertThat(staleJobIds).containsExactlyInAnyOrder(staleJobSaved.getId(), neverPingedJobSaved.getId());
    }

    @Test
    void findJobIdsExceedingMaxDurationOnlyMatchesRunningJobsPastTheCap() {
        Backend backend = persistBackend("backend-duration");
        Instant cutoff = Instant.now().minus(45, ChronoUnit.MINUTES);

        Review longRunning = persistReview(4L, 30L, "sha-long");
        ReviewJob longRunningJob = jobWithStatus(longRunning, backend, "worker-1", JobStatus.RUNNING);
        longRunningJob.setStartedAt(cutoff.minus(5, ChronoUnit.MINUTES));
        ReviewJob longRunningJobSaved = entityManager.persistFlushFind(longRunningJob);

        Review recentlyStarted = persistReview(4L, 31L, "sha-recent");
        ReviewJob recentJob = jobWithStatus(recentlyStarted, backend, "worker-2", JobStatus.RUNNING);
        recentJob.setStartedAt(Instant.now());
        entityManager.persistAndFlush(recentJob);

        Review notStartedYet = persistReview(4L, 32L, "sha-not-started");
        ReviewJob notStartedJob = jobWithStatus(notStartedYet, backend, "worker-3", JobStatus.RUNNING);
        entityManager.persistAndFlush(notStartedJob); // startedAt null -> never matches

        List<Long> exceeded = reviewJobRepository.findJobIdsExceedingMaxDuration(cutoff);

        assertThat(exceeded).containsExactly(longRunningJobSaved.getId());
    }

    @Test
    void findJobIdsWithStaleHeartbeatBoundaryExactlyAtCutoffIsNotYetStale() {
        // "Stale" means the interval is EXCEEDED, i.e. strictly more than the timeout. A heartbeat
        // recorded at exactly the cutoff instant has not yet exceeded it and must not be swept.
        Backend backend = persistBackend("backend-heartbeat-boundary");
        Instant cutoff = Instant.now().minus(3, ChronoUnit.MINUTES);

        Review exactlyAtCutoff = persistReview(3L, 24L, "sha-exact-cutoff");
        ReviewJob exactJob = jobWithStatus(exactlyAtCutoff, backend, "worker-5", JobStatus.RUNNING);
        exactJob.setHeartbeatAt(cutoff);
        entityManager.persistAndFlush(exactJob);

        Review oneMillisStale = persistReview(3L, 25L, "sha-one-millis-stale");
        ReviewJob oneMillisJob = jobWithStatus(oneMillisStale, backend, "worker-6", JobStatus.RUNNING);
        oneMillisJob.setHeartbeatAt(cutoff.minusMillis(1));
        ReviewJob oneMillisJobSaved = entityManager.persistFlushFind(oneMillisJob);

        List<Long> staleJobIds = reviewJobRepository.findJobIdsWithStaleHeartbeat(cutoff);

        assertThat(staleJobIds).containsExactly(oneMillisJobSaved.getId());
    }

    @Test
    void findJobIdsExceedingMaxDurationBoundaryExactlyAtCutoffIsNotYetExceeding() {
        Backend backend = persistBackend("backend-duration-boundary");
        Instant cutoff = Instant.now().minus(45, ChronoUnit.MINUTES);

        Review exactlyAtCutoff = persistReview(4L, 33L, "sha-exact-duration-cutoff");
        ReviewJob exactJob = jobWithStatus(exactlyAtCutoff, backend, "worker-7", JobStatus.RUNNING);
        exactJob.setStartedAt(cutoff);
        entityManager.persistAndFlush(exactJob);

        Review oneMillisOver = persistReview(4L, 34L, "sha-one-millis-over");
        ReviewJob oneMillisJob = jobWithStatus(oneMillisOver, backend, "worker-8", JobStatus.RUNNING);
        oneMillisJob.setStartedAt(cutoff.minusMillis(1));
        ReviewJob oneMillisJobSaved = entityManager.persistFlushFind(oneMillisJob);

        List<Long> exceeded = reviewJobRepository.findJobIdsExceedingMaxDuration(cutoff);

        assertThat(exceeded).containsExactly(oneMillisJobSaved.getId());
    }

    @Test
    void countRunningJobsForBackendExcludesFailedAndQueuedJobs() {
        Backend backend = persistBackend("backend-non-running-statuses");

        Review running = persistReview(2L, 14L, "sha-run-counted");
        entityManager.persistAndFlush(jobWithStatus(running, backend, "worker-1", JobStatus.RUNNING));

        Review failed = persistReview(2L, 15L, "sha-failed");
        entityManager.persistAndFlush(jobWithStatus(failed, backend, "worker-2", JobStatus.FAILED));

        Review queued = persistReview(2L, 16L, "sha-queued");
        entityManager.persistAndFlush(jobWithStatus(queued, backend, "worker-3", JobStatus.QUEUED));

        assertThat(reviewJobRepository.countRunningJobsForBackend(backend.getId())).isEqualTo(1);
    }
}
