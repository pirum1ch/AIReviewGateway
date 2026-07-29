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
        AtomicInteger headShaCounter = new AtomicInteger();

        Callable<Void> claimHammer = () -> {
            Map<String, Object> claimBody = Map.of("backendId", "concurrency-backend", "workerId", "worker-hammer");
            for (int i = 0; i < iterations; i++) {
                ResponseEntity<Map> response = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                        entity(WORKER_TOKEN, claimBody), Map.class);
                recordIfUnexpected(response.getStatusCode().value(), unexpectedStatuses);
            }
            return null;
        };

        Callable<Void> cancelHammer = () -> {
            for (int i = 0; i < iterations; i++) {
                if (!seedReviewIds.isEmpty()) {
                    Long target = seedReviewIds.get(i % seedReviewIds.size());
                    ResponseEntity<Map> response = restTemplate.exchange("/reviews/" + target, HttpMethod.DELETE,
                            entity(ADMIN_TOKEN, null), Map.class);
                    recordIfUnexpected(response.getStatusCode().value(), unexpectedStatuses);
                }
            }
            return null;
        };

        Callable<Void> newHeadShaHammer = () -> {
            for (int i = 0; i < iterations; i++) {
                ResponseEntity<Map> response = restTemplate.exchange("/reviews", HttpMethod.POST,
                        entity(CI_TOKEN, createReviewBody("sha-race-" + headShaCounter.incrementAndGet())), Map.class);
                recordIfUnexpected(response.getStatusCode().value(), unexpectedStatuses);
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
    }

    private void recordIfUnexpected(int status, List<Integer> unexpectedStatuses) {
        // 500 is the only truly unacceptable outcome here (an unhandled exception/deadlock); every other
        // status (200/201/204/403/404/409/422) is a legitimate, already-tested outcome of one of these
        // three concurrently-racing operations.
        if (status >= 500) {
            unexpectedStatuses.add(status);
        }
    }
}
