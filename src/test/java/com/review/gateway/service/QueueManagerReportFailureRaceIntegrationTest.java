package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.FailureReportOutcome;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.RequeueOutcome;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-added (Worker Observability &amp; Claim Latency): independent, real-database verification of the
 * WOC-27/WOR-19 ownership race the architecture/threat-model docs call out as the trickiest concurrency
 * property of Part 3 (architecture §5.4, threat-model §4.1/WOT-02/WOR-19; test guidance T-3.5) and of
 * WOC-38 (a late heartbeat tick landing after a requeue must not resurrect the requeued job's apparent
 * freshness; test guidance T-3.6).
 *
 * <p>The scenario under test: a Worker-reported failure for a job it used to own arrives <em>after</em>
 * the Gateway's own stale-heartbeat sweep has already reclaimed that job and a different Worker has
 * re-claimed it (a fresh, healthy attempt). The stale report must never touch that new attempt — neither
 * via {@link QueueManager#reportFailure}'s outer, unlocked pre-check, nor (independently, and more
 * importantly per WOC-27's own stated rationale) via {@link RetryManager#requeueOrFail(Long, String,
 * String)}'s locked ownership re-check, which is the control that actually matters if a future refactor
 * ever weakens or removes the outer pre-check.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueManagerReportFailureRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private RetryManager retryManager;
    private QueueManager queueManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private void wireCollaborators(int maxAttempts, Duration requeueDelay) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        GatewayProperties properties = new GatewayProperties();
        properties.getRetry().setMaxAttempts(maxAttempts);
        properties.getRetry().setRequeueDelay(requeueDelay);
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository, properties);
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties,
                entityManager, transactionManager);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        PromptMessageFormatter promptMessageFormatter = new PromptMessageFormatter(properties,
                new PromptAssembler(properties, new DiffSizeValidator(properties)));
        this.retryManager = new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator,
                properties, new TextSanitizer(), entityManager, transactionManager);
        this.queueManager = new QueueManager(reviewRepository, reviewJobRepository, reviewChunkRepository,
                reviewPromptSectionRepository, backendDispatcher, jobStateMachine, chunkCoordinator, eventService,
                Mockito.mock(ResultProcessor.class), chunkContextRenderer, promptMessageFormatter, retryManager,
                new TextSanitizer(), new MetricsCounters(), new ReviewSchemaBuilder(), new DecoderConstraintRenderer(),
                properties, entityManager, transactionManager);
    }

    private Review persistRunningReview(String headSha) {
        Review review = new Review(1L, 2L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        return reviewRepository.saveAndFlush(review);
    }

    private Backend persistActiveBackend(String name, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model", capacity);
        backend.setStatus(BackendStatus.ACTIVE);
        return backendRepository.saveAndFlush(backend);
    }

    private ReviewJob persistRunningJob(Review review, Backend backend, int attempts, String workerId) {
        // QueueManager.claim requires a matching review_chunks row for whatever chunkIndex the claimed
        // job carries (single-chunk jobs are chunkIndex=0, see ReviewJob's 3-arg convenience ctor) --
        // needed here because these tests exercise a real re-claim through QueueManager.claim, unlike
        // QueueManagerReportFailureTest's siblings which never call claim().
        reviewChunkRepository.saveAndFlush(
                new ReviewChunk(review.getId(), 0, 1, "diff-" + review.getHeadSha(), 10, 1, "[]"));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), workerId);
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(attempts);
        job.setHeartbeatAt(Instant.now());
        return reviewJobRepository.saveAndFlush(job);
    }

    private long retryEventCountForJob(Long reviewId, Long jobId) {
        List<ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId);
        return events.stream()
                .filter(e -> e.getEventType() == EventType.RETRY && jobId.equals(e.getJobId()))
                .count();
    }

    /**
     * T-3.5 (outer layer): by the time the stale report physically arrives, the DB already shows the new
     * owner, so {@link QueueManager#reportFailure}'s own unlocked pre-check already rejects it -- proving
     * the common-case shape of the race is closed end-to-end through the real HTTP-facing method, not
     * just at the {@code RetryManager} unit level.
     */
    @Test
    void staleWorkerReportArrivingAfterSweepAndReclaimIsRejectedAtTheOuterPreCheck() {
        wireCollaborators(3, Duration.ofSeconds(90));
        Review review = persistRunningReview("sha-race-outer");
        Backend backend = persistActiveBackend("backend-race-outer", 2);
        ReviewJob job = persistRunningJob(review, backend, 1, "worker-A");

        // The Gateway's own stale-heartbeat sweep reclaims the job (attempts unchanged; workerId is left
        // as-is on requeue -- only overwritten again on the next claim, see QueueManager.claimJobRow).
        RequeueOutcome sweepOutcome = retryManager.requeueOrFail(job.getId(), "heartbeat timeout", null);
        assertThat(sweepOutcome.outcome()).isEqualTo(RequeueOutcome.Outcome.APPLIED_REQUEUED);

        // A different Worker claims the now-QUEUED job -- job.not_before defaults to a future time with
        // a 90s requeue-delay, so bypass that gate here (it is orthogonal to the ownership race under
        // test) by clearing it directly, exactly as if requeue-delay had already elapsed.
        ReviewJob requeued = reviewJobRepository.findById(job.getId()).orElseThrow();
        requeued.setNotBefore(null);
        reviewJobRepository.saveAndFlush(requeued);

        Optional<ClaimedJob> reclaimed = queueManager.claim(backend.getName(), "worker-B");
        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().jobId()).isEqualTo(job.getId());

        ReviewJob reclaimedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reclaimedJob.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reclaimedJob.getWorkerId()).isEqualTo("worker-B");
        assertThat(reclaimedJob.getAttempts()).isEqualTo(2);

        // The stale report from worker-A, sent before the abandonment was reclaimed, finally arrives.
        FailureReportOutcome outcome = queueManager.reportFailure(job.getId(), "worker-A", "LLM_ERROR", null);

        assertThat(outcome).isEqualTo(FailureReportOutcome.OWNERSHIP_MISMATCH);
        ReviewJob afterStaleReport = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterStaleReport.getStatus()).as("worker-B's healthy attempt must be untouched").isEqualTo(JobStatus.RUNNING);
        assertThat(afterStaleReport.getWorkerId()).isEqualTo("worker-B");
        assertThat(afterStaleReport.getAttempts()).isEqualTo(2);
        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.RUNNING);

        // Exactly one job-level RETRY event exists (from the sweep) -- the stale report produced none.
        assertThat(retryEventCountForJob(review.getId(), job.getId())).isEqualTo(1);
    }

    /**
     * T-3.5 (the control that actually matters, per WOC-27's own javadoc): {@link
     * RetryManager#requeueOrFail(Long, String, String)}'s <b>locked</b> ownership re-check independently
     * rejects a stale {@code expectedWorkerId}, even when called directly -- i.e. even if some future
     * change to {@link QueueManager#reportFailure}'s outer pre-check were ever weakened or removed, the
     * job-row-locked recheck inside {@code RetryManager} is still what prevents a stale report from
     * corrupting a fresh, healthy attempt belonging to a different worker.
     */
    @Test
    void ownerAwareLockedRecheckRejectsAStaleWorkerIdEvenWhenCalledDirectlyAfterReclaim() {
        wireCollaborators(3, Duration.ZERO);
        Review review = persistRunningReview("sha-race-locked");
        Backend backend = persistActiveBackend("backend-race-locked", 2);
        ReviewJob job = persistRunningJob(review, backend, 1, "worker-A");

        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");
        Optional<ClaimedJob> reclaimed = queueManager.claim(backend.getName(), "worker-B");
        assertThat(reclaimed).isPresent();

        // Bypass QueueManager's own pre-check entirely and call the locked-recheck component directly
        // with the stale workerId -- this is exactly the call QueueManager would have issued had its own
        // pre-check happened to run a moment earlier, before the reassignment committed (the genuine
        // TOCTOU window WOC-27 exists to close).
        RequeueOutcome outcome = retryManager.requeueOrFail(job.getId(), "worker-reported: reason=LLM_ERROR", "worker-A");

        assertThat(outcome.outcome()).isEqualTo(RequeueOutcome.Outcome.OWNERSHIP_MISMATCH);
        assertThat(outcome.reviewId()).isNull();
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-B");
        assertThat(reloaded.getAttempts()).isEqualTo(2);
        assertThat(reloaded.getLastError()).as("a rejected ownership check must not write last_error").isNull();

        // worker-B's attempt is provably still healthy afterward: its own heartbeat keeps it RUNNING.
        HeartbeatResult heartbeat = queueManager.heartbeat(job.getId(), "worker-B");
        assertThat(heartbeat.outcome().name()).isEqualTo("ACCEPTED");
        assertThat(heartbeat.shouldContinue()).isTrue();
    }

    /**
     * WOC-38: a heartbeat tick from the old attempt landing <em>after</em> the sweep has already
     * requeued the job must not resurrect its {@code heartbeat_at} -- {@code QueueManager.heartbeat}
     * checks {@code job.status == RUNNING} before touching {@code heartbeat_at}, so a late tick against
     * a QUEUED job is a pure no-op on that column. This matters because {@code heartbeat_at} freshness
     * otherwise feeds both the stale-heartbeat sweep and {@code BackendHealthChecker}'s at-capacity
     * deferral (WOC-13) -- a resurrected timestamp on a job that is no longer actually running would
     * corrupt both.
     */
    @Test
    void lateHeartbeatAfterRequeueDoesNotResurrectTheJobsHeartbeatFreshness() {
        wireCollaborators(3, Duration.ZERO);
        Review review = persistRunningReview("sha-late-heartbeat");
        Backend backend = persistActiveBackend("backend-late-heartbeat", 1);
        ReviewJob job = persistRunningJob(review, backend, 1, "worker-C");
        Instant heartbeatBeforeRequeue = reviewJobRepository.findById(job.getId()).orElseThrow().getHeartbeatAt();

        RequeueOutcome sweepOutcome = retryManager.requeueOrFail(job.getId(), "heartbeat timeout", null);
        assertThat(sweepOutcome.outcome()).isEqualTo(RequeueOutcome.Outcome.APPLIED_REQUEUED);
        ReviewJob requeuedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(requeuedJob.getStatus()).isEqualTo(JobStatus.QUEUED);
        // workerId is left untouched by a requeue -- only overwritten on the next claim -- so the late
        // heartbeat below still passes the (necessary but not sufficient) ownership check.
        assertThat(requeuedJob.getWorkerId()).isEqualTo("worker-C");

        HeartbeatResult lateHeartbeat = queueManager.heartbeat(job.getId(), "worker-C");

        assertThat(lateHeartbeat.outcome().name()).isEqualTo("ACCEPTED");
        assertThat(lateHeartbeat.shouldContinue()).as("a QUEUED job must never be told to continue").isFalse();
        ReviewJob afterLateHeartbeat = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(afterLateHeartbeat.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(afterLateHeartbeat.getHeartbeatAt())
                .as("a late heartbeat against a no-longer-RUNNING job must not touch heartbeat_at")
                .isEqualTo(heartbeatBeforeRequeue);
    }
}
