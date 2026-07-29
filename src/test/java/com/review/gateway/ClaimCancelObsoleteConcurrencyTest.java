package com.review.gateway;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSR-17: hammers {@code POST /jobs/claim} against {@code DELETE /reviews/{id}} and against
 * {@code POST /reviews} with a new {@code head_sha} for the same MR, all concurrently, and asserts zero
 * deadlock/unexpected-500 outcomes. This is the concurrency test the lock-ordering fix calls for: claim
 * only ever locks a job row (never the parent); cancel/sweepObsolete lock the parent first, then cascade
 * to children — independently, never nested — so no thread should ever observe a Postgres deadlock
 * (which would otherwise surface as a {@code 500}) or an unhandled exception. A bounded {@code
 * lock_timeout} additionally guarantees any genuine lock contention resolves as a clean {@code 409},
 * never hangs a request indefinitely.
 *
 * <p><b>F-DC-08:</b> appsec pointed out this class structurally could not fail on a genuine Postgres
 * deadlock — {@link #recordIfUnexpected} only flagged {@code status >= 500}, and a real deadlock (SQLSTATE
 * 40P01) is bounded to exactly {@code 409} same as a benign {@code lock_timeout} expiry, so it was
 * silently whitelisted alongside every other legitimate 409. Now that {@code GlobalExceptionHandler}
 * gives a real deadlock its own {@code DEADLOCK_DETECTED} error code (distinct from {@code
 * LOCK_TIMEOUT}), every response body is inspected and a {@code DEADLOCK_DETECTED} is tracked and
 * asserted to never occur — turning this into an actual regression test for F-DC-03's lock-ordering fix
 * rather than a test that would pass even if that fix were reverted. Also fixed: the only diff ever used
 * here was a fixed 45-char single-hunk body with {@code promptVersion: v1}, so {@code chunkCount} was
 * always {@code 1} — none of the multi-chunk-specific races (sibling cascade-cancel, concurrent chunk
 * completion, result-submit racing cancel) were ever exercised. {@link
 * #multiChunkConcurrentResultSubmitAndCancelNeverDeadlockOrLeakAnUnhandled500} adds that coverage
 * directly, reproducing the exact scenario appsec identified as the likely real-world trigger:
 * {@code ChunkCoordinator.cascadeCancelSiblings} racing a sibling chunk's result submission.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class ClaimCancelObsoleteConcurrencyTest {

    private static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    private static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";
    private static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";
    private static final long PROJECT_ID = 900;
    private static final long MR_ID = 9000;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private HttpEntity<?> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> createReviewBody(String headSha) {
        return Map.of(
                "projectId", PROJECT_ID,
                "mergeRequestId", MR_ID,
                "headSha", headSha,
                "baseSha", "base-" + headSha,
                "diff", "diff --git a/Foo.java b/Foo.java\n+ small change",
                "promptVersion", "v1",
                "priority", 10);
    }

    @SuppressWarnings("unchecked")
    private Long createReview(String headSha) {
        ResponseEntity<Map> response = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(CI_TOKEN, createReviewBody(headSha)), Map.class);
        if (response.getStatusCode().value() >= 500) {
            throw new AssertionError("Unexpected 5xx creating review: " + response.getStatusCode());
        }
        Object reviewId = response.getBody() == null ? null : response.getBody().get("reviewId");
        return reviewId == null ? null : ((Number) reviewId).longValue();
    }

    @Test
    void concurrentClaimCancelAndNewHeadShaNeverProduceAnUnhandled500() throws Exception {
        Backend backend = new Backend("concurrency-backend", "http://192.168.1.60:8080", "model-x", 10);
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);

        int iterations = 20;
        List<Long> seedReviewIds = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long id = createReview("sha-seed-" + i);
            if (id != null) {
                seedReviewIds.add(id);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(3);
        List<Integer> unexpectedStatuses = new CopyOnWriteArrayList<>();
        List<String> deadlockResponses = new CopyOnWriteArrayList<>();
        AtomicInteger headShaCounter = new AtomicInteger();

        Callable<Void> claimHammer = () -> {
            Map<String, Object> claimBody = Map.of("backendId", "concurrency-backend", "workerId", "worker-hammer");
            for (int i = 0; i < iterations; i++) {
                ResponseEntity<Map> response = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                        entity(WORKER_TOKEN, claimBody), Map.class);
                recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
            }
            return null;
        };

        Callable<Void> cancelHammer = () -> {
            for (int i = 0; i < iterations; i++) {
                if (!seedReviewIds.isEmpty()) {
                    Long target = seedReviewIds.get(i % seedReviewIds.size());
                    ResponseEntity<Map> response = restTemplate.exchange("/reviews/" + target, HttpMethod.DELETE,
                            entity(ADMIN_TOKEN, null), Map.class);
                    recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
                }
            }
            return null;
        };

        Callable<Void> newHeadShaHammer = () -> {
            for (int i = 0; i < iterations; i++) {
                ResponseEntity<Map> response = restTemplate.exchange("/reviews", HttpMethod.POST,
                        entity(CI_TOKEN, createReviewBody("sha-race-" + headShaCounter.incrementAndGet())), Map.class);
                recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
            }
            return null;
        };

        try {
            List<Future<Void>> futures = executor.invokeAll(
                    List.of(claimHammer, cancelHammer, newHeadShaHammer, claimHammer), 60, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                future.get(); // surfaces any exception thrown inside a Callable
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpectedStatuses)
                .as("no request should ever surface an unhandled 5xx (deadlock, lock-timeout mishandling, etc.)")
                .isEmpty();
        assertThat(deadlockResponses)
                .as("F-DC-03's lock-ordering fix (FOR NO KEY UPDATE on the parent) must mean a genuine Postgres "
                        + "deadlock never occurs here — a bounded, retryable LOCK_TIMEOUT 409 is fine, but a "
                        + "DEADLOCK_DETECTED 409 would mean the lock-ordering fix regressed")
                .isEmpty();
    }

    /**
     * F-DC-08: multi-chunk variant of the above. Uses a diff large enough that {@code DiffChunker}
     * splits it into 3 {@code review_chunks}/{@code review_jobs} rows (default {@code
     * gateway.diff.max-chunks=5}, per-chunk budget ~38,976 chars with the stock config — see
     * {@code DiffChunker}/{@code DiffSizeValidator} javadoc), then races claiming all 3 chunk jobs,
     * submitting results for two of them, and admin-cancelling the parent Review — reproducing the exact
     * scenario appsec identified as the likely real-world F-DC-03 deadlock trigger: {@code
     * ChunkCoordinator.cascadeCancelSiblings} (triggered by cancel) racing a sibling chunk's result
     * submission (which itself calls {@code ChunkCoordinator.completeChunkAndRecompute}). Both paths lock
     * the parent Review row — before the fix, via conflicting {@code FOR UPDATE}; after, via
     * non-conflicting {@code FOR NO KEY UPDATE} — while a job row is independently locked elsewhere.
     */
    @Test
    void multiChunkConcurrentResultSubmitAndCancelNeverDeadlockOrLeakAnUnhandled500() throws Exception {
        Backend backend = new Backend("chunk-concurrency-backend", "http://192.168.1.61:8080", "model-x", 10);
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);

        List<Integer> unexpectedStatuses = new CopyOnWriteArrayList<>();
        List<String> deadlockResponses = new CopyOnWriteArrayList<>();
        int iterations = 8;
        ExecutorService executor = Executors.newFixedThreadPool(3);

        try {
            for (int iteration = 0; iteration < iterations; iteration++) {
                String workerId = "worker-chunk-" + iteration;
                Long reviewId = createChunkedReview("sha-chunk-race-" + iteration);
                assertThat(reviewId).as("multi-chunk review must have been created (not deduplicated/rejected)").isNotNull();

                List<Long> jobIds = claimAllChunkJobs(reviewId, workerId, 3);
                assertThat(jobIds)
                        .as("the diff must actually have been split into 3 chunks (chunkCount driving 3 jobs)")
                        .hasSize(3);

                Callable<Void> submitFirst = () -> {
                    ResponseEntity<Map> response = restTemplate.exchange("/jobs/" + jobIds.get(0) + "/result",
                            HttpMethod.POST, entity(WORKER_TOKEN, submitResultBody(workerId)), Map.class);
                    recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
                    return null;
                };
                Callable<Void> submitSecond = () -> {
                    ResponseEntity<Map> response = restTemplate.exchange("/jobs/" + jobIds.get(1) + "/result",
                            HttpMethod.POST, entity(WORKER_TOKEN, submitResultBody(workerId)), Map.class);
                    recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
                    return null;
                };
                Callable<Void> cancelParent = () -> {
                    ResponseEntity<Map> response = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.DELETE,
                            entity(ADMIN_TOKEN, null), Map.class);
                    recordIfUnexpected(response, unexpectedStatuses, deadlockResponses);
                    return null;
                };

                List<Future<Void>> futures = executor.invokeAll(
                        List.of(submitFirst, submitSecond, cancelParent), 30, TimeUnit.SECONDS);
                for (Future<Void> future : futures) {
                    future.get();
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpectedStatuses)
                .as("no request should ever surface an unhandled 5xx across concurrent sibling-chunk "
                        + "completion and parent cancellation")
                .isEmpty();
        assertThat(deadlockResponses)
                .as("cascadeCancelSiblings racing a sibling's result submission must never produce a genuine "
                        + "Postgres deadlock (F-DC-03) -- a bounded 409 LOCK_TIMEOUT is acceptable, "
                        + "DEADLOCK_DETECTED is not")
                .isEmpty();
    }

    private Map<String, Object> chunkedReviewBody(String headSha) {
        // Three file sections, each with a body well under the ~38,976-char default per-chunk budget
        // alone, but two together well over it -- forcing DiffChunker's next-fit bin-packing to place
        // each in its own chunk (3 chunks total) with the stock application.yml defaults.
        String diff = gitSection("A.java", "a".repeat(30_000))
                + gitSection("B.java", "b".repeat(30_000))
                + gitSection("C.java", "c".repeat(30_000));
        return Map.of(
                "projectId", PROJECT_ID,
                "mergeRequestId", MR_ID + 1,
                "headSha", headSha,
                "baseSha", "base-" + headSha,
                "diff", diff,
                "promptVersion", "v2",
                "priority", 10);
    }

    private String gitSection(String path, String body) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -1,1 +1,1 @@\n+" + body + "\n";
    }

    @SuppressWarnings("unchecked")
    private Long createChunkedReview(String headSha) {
        ResponseEntity<Map> response = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(CI_TOKEN, chunkedReviewBody(headSha)), Map.class);
        if (response.getStatusCode().value() >= 500) {
            throw new AssertionError("Unexpected 5xx creating chunked review: " + response.getStatusCode());
        }
        Object reviewId = response.getBody() == null ? null : response.getBody().get("reviewId");
        return reviewId == null ? null : ((Number) reviewId).longValue();
    }

    /** Claims exactly {@code count} jobs from the dedicated chunk-concurrency backend, one at a time. */
    @SuppressWarnings("unchecked")
    private List<Long> claimAllChunkJobs(Long reviewId, String workerId, int count) {
        List<Long> jobIds = new java.util.ArrayList<>();
        Map<String, Object> claimBody = Map.of("backendId", "chunk-concurrency-backend", "workerId", workerId);
        for (int i = 0; i < count; i++) {
            ResponseEntity<Map> response = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                    entity(WORKER_TOKEN, claimBody), Map.class);
            if (response.getStatusCode().value() != 200) {
                throw new AssertionError("Expected to claim chunk job " + i + " of review " + reviewId
                        + " but got " + response.getStatusCode());
            }
            Object jobId = response.getBody().get("jobId");
            jobIds.add(((Number) jobId).longValue());
        }
        return jobIds;
    }

    private Map<String, Object> submitResultBody(String workerId) {
        return Map.of(
                "workerId", workerId,
                "rawResponse", "{\"summary\":\"ok\",\"comments\":[]}",
                "promptTokens", 10,
                "completionTokens", 5,
                "durationMs", 100,
                "model", "model-x");
    }

    private void recordIfUnexpected(ResponseEntity<Map> response, List<Integer> unexpectedStatuses,
                                     List<String> deadlockResponses) {
        int status = response.getStatusCode().value();
        // 500 is the only truly unacceptable outcome here (an unhandled exception); every other status
        // (200/201/204/403/404/409/422) is a legitimate, already-tested outcome of one of these
        // concurrently-racing operations -- EXCEPT a 409 whose body identifies it as a genuine deadlock
        // (F-DC-08): that is tracked separately below and must never occur.
        if (status >= 500) {
            unexpectedStatuses.add(status);
        }
        Object body = response.getBody();
        if (status == 409 && body instanceof Map<?, ?> map && "DEADLOCK_DETECTED".equals(map.get("error"))) {
            deadlockResponses.add(String.valueOf(map.get("message")));
        }
    }
}
