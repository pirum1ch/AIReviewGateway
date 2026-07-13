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

import java.util.LinkedHashMap;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end lifecycle test over the real HTTP layer + a real (Zonky) PostgreSQL: exercises the full
 * happy path (create -&gt; claim -&gt; heartbeat -&gt; result -&gt; status) plus dedup and admin-cancel,
 * exactly as a real CI job / Worker / admin would drive it. GitLab publish is intentionally not
 * exercised here (no network access; {@code GitLabPublisher} is only reached from the
 * {@code @Scheduled} retry path, not synchronously from any of these endpoints).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class ReviewLifecycleIntegrationTest {

    private static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    private static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";
    private static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    @AfterEach
    void cleanUp() {
        // reviews -> review_jobs/review_inputs/... cascade ON DELETE; must go first so backends (still
        // referenced by review_jobs.backend_id until then) can be removed afterward without violating
        // the FK.
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private Backend seedActiveBackend(String name) {
        Backend backend = new Backend(name, "http://192.168.1.50:8080", "model-x", 5);
        backend.setStatus(BackendStatus.ACTIVE);
        return backendRepository.saveAndFlush(backend);
    }

    private HttpHeaders headersFor(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> createReviewBody(long projectId, long mrId, String headSha) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("mergeRequestId", mrId);
        body.put("headSha", headSha);
        body.put("baseSha", "base-" + headSha);
        body.put("diff", "diff --git a/Foo.java b/Foo.java\n+ some change");
        body.put("promptVersion", "v1");
        body.put("priority", 10);
        return body;
    }

    @SuppressWarnings("unchecked")
    @Test
    void fullLifecycleCreateClaimHeartbeatResultReachesCompleted() {
        seedActiveBackend("mac-mini-e2e-1");

        // 1. CI creates a Review.
        ResponseEntity<Map> createResponse = restTemplate.exchange("/reviews", HttpMethod.POST,
                new HttpEntity<>(createReviewBody(100, 200, "sha-e2e-1"), headersFor(CI_TOKEN)), Map.class);
        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);
        Number reviewId = (Number) createResponse.getBody().get("reviewId");
        assertThat(createResponse.getBody().get("status")).isEqualTo("QUEUED");

        // Dedup: an identical create (same project/mr/head_sha) returns the SAME reviewId with 200.
        ResponseEntity<Map> dedupResponse = restTemplate.exchange("/reviews", HttpMethod.POST,
                new HttpEntity<>(createReviewBody(100, 200, "sha-e2e-1"), headersFor(CI_TOKEN)), Map.class);
        assertThat(dedupResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(((Number) dedupResponse.getBody().get("reviewId")).longValue()).isEqualTo(reviewId.longValue());

        // GET status: QUEUED.
        ResponseEntity<Map> statusAfterCreate = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);
        assertThat(statusAfterCreate.getBody().get("status")).isEqualTo("QUEUED");

        // 2. Worker claims the job.
        Map<String, Object> claimBody = Map.of("backendId", "mac-mini-e2e-1", "workerId", "worker-e2e-1");
        ResponseEntity<Map> claimResponse = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                new HttpEntity<>(claimBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(claimResponse.getStatusCode().value()).isEqualTo(200);
        Number jobId = (Number) claimResponse.getBody().get("jobId");
        assertThat(((Number) claimResponse.getBody().get("reviewId")).longValue()).isEqualTo(reviewId.longValue());
        Map<String, Object> payload = (Map<String, Object>) claimResponse.getBody().get("payload");
        assertThat(payload.get("diff")).isEqualTo("diff --git a/Foo.java b/Foo.java\n+ some change");

        // GET status: RUNNING now.
        ResponseEntity<Map> statusAfterClaim = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);
        assertThat(statusAfterClaim.getBody().get("status")).isEqualTo("RUNNING");
        assertThat(statusAfterClaim.getBody().get("attempts")).isEqualTo(1);

        // Second claim attempt (queue now empty for this backend) -> 204.
        ResponseEntity<Map> secondClaim = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                new HttpEntity<>(claimBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(secondClaim.getStatusCode().value()).isEqualTo(204);

        // 3. Worker heartbeats.
        Map<String, Object> heartbeatBody = Map.of("workerId", "worker-e2e-1");
        ResponseEntity<Map> heartbeatResponse = restTemplate.exchange("/jobs/" + jobId + "/heartbeat", HttpMethod.POST,
                new HttpEntity<>(heartbeatBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(heartbeatResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(heartbeatResponse.getBody().get("shouldContinue")).isEqualTo(true);

        // 4. Worker submits the result.
        Map<String, Object> resultBody = new LinkedHashMap<>();
        resultBody.put("workerId", "worker-e2e-1");
        resultBody.put("rawResponse", "Looks good overall, no issues found.");
        resultBody.put("promptTokens", 100);
        resultBody.put("completionTokens", 20);
        resultBody.put("durationMs", 1500);
        resultBody.put("model", "model-x");
        ResponseEntity<Map> resultResponse = restTemplate.exchange("/jobs/" + jobId + "/result", HttpMethod.POST,
                new HttpEntity<>(resultBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(resultResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(resultResponse.getBody().get("status")).isEqualTo("COMPLETED");

        // GET status: COMPLETED, with a comment recorded.
        ResponseEntity<Map> finalStatus = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);
        assertThat(finalStatus.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) finalStatus.getBody().get("commentCount")).intValue()).isEqualTo(1);

        // Idempotent re-delivery of the same result: still 200, still COMPLETED, no new comment/event.
        ResponseEntity<Map> repeatedResult = restTemplate.exchange("/jobs/" + jobId + "/result", HttpMethod.POST,
                new HttpEntity<>(resultBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(repeatedResult.getStatusCode().value()).isEqualTo(200);
        assertThat(repeatedResult.getBody().get("status")).isEqualTo("COMPLETED");

        ResponseEntity<Map> statusAfterRepeat = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);
        assertThat(((Number) statusAfterRepeat.getBody().get("commentCount")).intValue()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void adminCancelStopsAClaimedJobViaHeartbeatContinueFalse() {
        seedActiveBackend("mac-mini-e2e-2");

        ResponseEntity<Map> createResponse = restTemplate.exchange("/reviews", HttpMethod.POST,
                new HttpEntity<>(createReviewBody(101, 201, "sha-e2e-cancel"), headersFor(CI_TOKEN)), Map.class);
        Number reviewId = (Number) createResponse.getBody().get("reviewId");

        Map<String, Object> claimBody = Map.of("backendId", "mac-mini-e2e-2", "workerId", "worker-e2e-2");
        ResponseEntity<Map> claimResponse = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                new HttpEntity<>(claimBody, headersFor(WORKER_TOKEN)), Map.class);
        Number jobId = (Number) claimResponse.getBody().get("jobId");

        // Admin cancels the RUNNING review.
        ResponseEntity<Map> cancelResponse = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.DELETE,
                new HttpEntity<>(headersFor(ADMIN_TOKEN)), Map.class);
        assertThat(cancelResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelResponse.getBody().get("status")).isEqualTo("CANCELLED");

        // The worker's next heartbeat for the same job is told to stop.
        Map<String, Object> heartbeatBody = Map.of("workerId", "worker-e2e-2");
        ResponseEntity<Map> heartbeatResponse = restTemplate.exchange("/jobs/" + jobId + "/heartbeat", HttpMethod.POST,
                new HttpEntity<>(heartbeatBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(heartbeatResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(heartbeatResponse.getBody().get("shouldContinue")).isEqualTo(false);

        // A second cancel attempt on the already-terminal review is rejected (409), not silently accepted.
        ResponseEntity<Map> secondCancel = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.DELETE,
                new HttpEntity<>(headersFor(ADMIN_TOKEN)), Map.class);
        assertThat(secondCancel.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void oversizedDiffIsRejectedWith422DiffTooLargeShapeOverRealHttp() {
        // gateway.diff.max-diff-tokens is 10000 with chars-per-token 4 in test config (inherited from
        // main application.yml defaults) -> ~40000+ chars comfortably exceeds the budget, well under
        // the SR-11 edge byte-cap (100000) so this exercises DiffSizeValidator, not the body-size filter.
        String hugeDiff = "x".repeat(45_000);
        Map<String, Object> body = createReviewBody(102, 202, "sha-e2e-huge");
        body.put("diff", hugeDiff);

        ResponseEntity<Map> response = restTemplate.exchange("/reviews", HttpMethod.POST,
                new HttpEntity<>(body, headersFor(CI_TOKEN)), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("error")).isEqualTo("DIFF_TOO_LARGE");
        assertThat(response.getBody()).containsOnlyKeys("error", "message");
    }

    @Test
    void unknownReviewIdReturns404OverRealHttp() {
        ResponseEntity<Map> response = restTemplate.exchange("/reviews/999999999", HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().get("error")).isEqualTo("NOT_FOUND");
    }

    @Test
    void claimOnEmptyQueueReturns204OverRealHttp() {
        seedActiveBackend("mac-mini-e2e-empty");
        Map<String, Object> claimBody = Map.of("backendId", "mac-mini-e2e-empty", "workerId", "worker-e2e-empty");

        ResponseEntity<String> response = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                new HttpEntity<>(claimBody, headersFor(WORKER_TOKEN)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }
}
