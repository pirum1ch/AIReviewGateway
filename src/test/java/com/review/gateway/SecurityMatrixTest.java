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
import java.util.List;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive endpoint x role matrix (SR-16) over the REAL {@code SecurityFilterChain} and full
 * application context (Zonky Postgres) -- deliberately NOT a per-controller {@code @WebMvcTest} slice,
 * so this is the one place the whole {@code SecurityConfig.authorizeHttpRequests} rule ordering is
 * exercised end-to-end exactly as a real deployment would route requests (any accidental overlap
 * between the DELETE-specific matcher and the general {@code /reviews/**} matcher, for instance, would
 * only show up here). Expected results per the amended, strict single-role table (threat-model SR-16):
 * {@code /reviews/**} (except DELETE) = CI only; {@code DELETE /reviews/{id}} = ADMIN only;
 * {@code /jobs/**} = WORKER only; {@code /backends}, {@code /metrics} = ADMIN only; {@code /health} =
 * anyone (including no token). A body/status distinguishing "authenticated but wrong role" (403) from
 * "not authenticated at all" (401) is asserted for every cell; 401/403 response bodies are checked to
 * leak nothing beyond the fixed {@code ErrorResponse} shape.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class SecurityMatrixTest {

    private static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    private static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";
    private static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";
    private static final String GARBAGE_TOKEN = "totally-unrecognized-token-value-xyz-000";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    @AfterEach
    void cleanUp() {
        // QA note: postReviewsRequiresCiExactly() creates a REAL QUEUED review via the CI-token happy
        // path. Deleting only backends here previously left that review QUEUED in the shared queue,
        // where it could be claimed by an unrelated test class's /jobs/claim call (queue-claim has no
        // per-test scoping -- it just takes the oldest QUEUED row) and produce a confusing, seemingly
        // unrelated test-order-dependent failure elsewhere. Reviews must go first (cascades review_jobs)
        // so backends -- still possibly referenced by a review_jobs row -- can be deleted afterward.
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private HttpEntity<?> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Backend activeBackend(String name) {
        Backend backend = new Backend(name, "http://192.168.1.77:8080", "model-x", 5);
        backend.setStatus(BackendStatus.ACTIVE);
        return backend;
    }

    private Map<String, Object> validCreateReviewBody(String headSha) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", 500);
        body.put("mergeRequestId", 600);
        body.put("headSha", headSha);
        body.put("baseSha", "base-" + headSha);
        body.put("diff", "diff content");
        body.put("promptVersion", "v1");
        body.put("priority", 10);
        return body;
    }

    private void assertBodyLeaksNothing(ResponseEntity<Map> response) {
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.keySet()).isSubsetOf(java.util.Set.of("error", "message"));
        String asString = String.valueOf(body);
        assertThat(asString).doesNotContain("Exception").doesNotContainIgnoringCase("stacktrace");
    }

    // ---------------------------------------------------------------- /health ----

    @Test
    void healthIsReachableByEveryRoleAndNoToken() {
        for (String token : new String[]{null, CI_TOKEN, WORKER_TOKEN, ADMIN_TOKEN, GARBAGE_TOKEN}) {
            ResponseEntity<String> response = restTemplate.exchange("/health", HttpMethod.GET, entity(token, null), String.class);
            assertThat(response.getStatusCode().value()).as("token=%s", token).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------- /reviews -----

    @Test
    void postReviewsRequiresCiExactly() {
        ResponseEntity<Map> ci = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(CI_TOKEN, validCreateReviewBody("sha-matrix-post-ci")), Map.class);
        assertThat(ci.getStatusCode().value()).isIn(200, 201);

        ResponseEntity<Map> worker = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(WORKER_TOKEN, validCreateReviewBody("sha-matrix-post-worker")), Map.class);
        assertThat(worker.getStatusCode().value()).isEqualTo(403);
        assertBodyLeaksNothing(worker);

        ResponseEntity<Map> admin = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(ADMIN_TOKEN, validCreateReviewBody("sha-matrix-post-admin")), Map.class);
        assertThat(admin.getStatusCode().value()).as("ADMIN must NOT be granted CI's create-review privilege (SR-16 strict single-role)").isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(null, validCreateReviewBody("sha-matrix-post-none")), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
        assertBodyLeaksNothing(none);

        ResponseEntity<Map> garbage = restTemplate.exchange("/reviews", HttpMethod.POST,
                entity(GARBAGE_TOKEN, validCreateReviewBody("sha-matrix-post-garbage")), Map.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(401);
        assertBodyLeaksNothing(garbage);
    }

    @Test
    void getReviewsByIdRequiresCiExactly() {
        ResponseEntity<Map> ci = restTemplate.exchange("/reviews/999999999", HttpMethod.GET, entity(CI_TOKEN, null), Map.class);
        assertThat(ci.getStatusCode().value()).as("CI is authenticated for the right role; 404 (not 403) proves it reached the handler").isEqualTo(404);

        ResponseEntity<Map> worker = restTemplate.exchange("/reviews/999999999", HttpMethod.GET, entity(WORKER_TOKEN, null), Map.class);
        assertThat(worker.getStatusCode().value()).isEqualTo(403);
        assertBodyLeaksNothing(worker);

        ResponseEntity<Map> admin = restTemplate.exchange("/reviews/999999999", HttpMethod.GET, entity(ADMIN_TOKEN, null), Map.class);
        assertThat(admin.getStatusCode().value()).as("ADMIN must NOT implicitly get CI's read access (SR-16 strict single-role)").isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/reviews/999999999", HttpMethod.GET, entity(null, null), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> garbage = restTemplate.exchange("/reviews/999999999", HttpMethod.GET, entity(GARBAGE_TOKEN, null), Map.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void deleteReviewsByIdRequiresAdminExactly() {
        ResponseEntity<Map> admin = restTemplate.exchange("/reviews/999999999", HttpMethod.DELETE, entity(ADMIN_TOKEN, null), Map.class);
        assertThat(admin.getStatusCode().value()).as("ADMIN is authenticated for the right role; 404 proves it reached the handler").isEqualTo(404);

        ResponseEntity<Map> ci = restTemplate.exchange("/reviews/999999999", HttpMethod.DELETE, entity(CI_TOKEN, null), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);
        assertBodyLeaksNothing(ci);

        ResponseEntity<Map> worker = restTemplate.exchange("/reviews/999999999", HttpMethod.DELETE, entity(WORKER_TOKEN, null), Map.class);
        assertThat(worker.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/reviews/999999999", HttpMethod.DELETE, entity(null, null), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
    }

    // --------------------------------------------------------------- /jobs ------

    @Test
    void jobsClaimRequiresWorkerExactly() {
        // Deliberately seed a real, claimable (ACTIVE, empty-queue) backend for the WORKER-role cell:
        // an unknown/inactive/at-capacity backendId hits a separate, real defect (see
        // BackendDispatcherClaimDeclineTransactionBugTest) that turns the documented 204 into a 500 --
        // this test's purpose is exclusively the role matrix, so it must not incidentally depend on
        // that unrelated bug's behavior.
        backendRepository.save(activeBackend("mac-mini-matrix"));
        Map<String, Object> claimBody = Map.of("backendId", "mac-mini-matrix", "workerId", "worker-matrix");

        ResponseEntity<Map> worker = restTemplate.exchange("/jobs/claim", HttpMethod.POST, entity(WORKER_TOKEN, claimBody), Map.class);
        assertThat(worker.getStatusCode().value()).as("empty queue -> 204, but proves WORKER reached the handler").isEqualTo(204);

        ResponseEntity<Map> ci = restTemplate.exchange("/jobs/claim", HttpMethod.POST, entity(CI_TOKEN, claimBody), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);
        assertBodyLeaksNothing(ci);

        ResponseEntity<Map> admin = restTemplate.exchange("/jobs/claim", HttpMethod.POST, entity(ADMIN_TOKEN, claimBody), Map.class);
        assertThat(admin.getStatusCode().value()).as("ADMIN must NOT be granted WORKER's claim privilege").isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/jobs/claim", HttpMethod.POST, entity(null, claimBody), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> garbage = restTemplate.exchange("/jobs/claim", HttpMethod.POST, entity(GARBAGE_TOKEN, claimBody), Map.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void jobsHeartbeatRequiresWorkerExactly() {
        Map<String, Object> heartbeatBody = Map.of("workerId", "worker-matrix");

        ResponseEntity<Map> worker = restTemplate.exchange("/jobs/999999999/heartbeat", HttpMethod.POST, entity(WORKER_TOKEN, heartbeatBody), Map.class);
        assertThat(worker.getStatusCode().value()).as("unknown jobId -> 404, but proves WORKER reached the handler").isEqualTo(404);

        ResponseEntity<Map> ci = restTemplate.exchange("/jobs/999999999/heartbeat", HttpMethod.POST, entity(CI_TOKEN, heartbeatBody), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> admin = restTemplate.exchange("/jobs/999999999/heartbeat", HttpMethod.POST, entity(ADMIN_TOKEN, heartbeatBody), Map.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/jobs/999999999/heartbeat", HttpMethod.POST, entity(null, heartbeatBody), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void jobsResultRequiresWorkerExactly() {
        Map<String, Object> resultBody = Map.of("workerId", "worker-matrix", "rawResponse", "raw text");

        ResponseEntity<Map> worker = restTemplate.exchange("/jobs/999999999/result", HttpMethod.POST, entity(WORKER_TOKEN, resultBody), Map.class);
        assertThat(worker.getStatusCode().value()).as("unknown jobId -> 404, but proves WORKER reached the handler").isEqualTo(404);

        ResponseEntity<Map> ci = restTemplate.exchange("/jobs/999999999/result", HttpMethod.POST, entity(CI_TOKEN, resultBody), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> admin = restTemplate.exchange("/jobs/999999999/result", HttpMethod.POST, entity(ADMIN_TOKEN, resultBody), Map.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/jobs/999999999/result", HttpMethod.POST, entity(null, resultBody), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
    }

    // ------------------------------------------------------- /backends, /metrics -

    @Test
    void backendsRequiresAdminExactly() {
        // GET /backends returns a JSON ARRAY (List<BackendView>), unlike every other endpoint tested
        // here which returns an object -- must be deserialized as List, not Map, for the success case.
        ResponseEntity<List> admin = restTemplate.exchange("/backends", HttpMethod.GET, entity(ADMIN_TOKEN, null), List.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> ci = restTemplate.exchange("/backends", HttpMethod.GET, entity(CI_TOKEN, null), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);
        assertBodyLeaksNothing(ci);

        ResponseEntity<Map> worker = restTemplate.exchange("/backends", HttpMethod.GET, entity(WORKER_TOKEN, null), Map.class);
        assertThat(worker.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/backends", HttpMethod.GET, entity(null, null), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<Map> garbage = restTemplate.exchange("/backends", HttpMethod.GET, entity(GARBAGE_TOKEN, null), Map.class);
        assertThat(garbage.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void metricsRequiresAdminExactly() {
        ResponseEntity<Map> admin = restTemplate.exchange("/metrics", HttpMethod.GET, entity(ADMIN_TOKEN, null), Map.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> ci = restTemplate.exchange("/metrics", HttpMethod.GET, entity(CI_TOKEN, null), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> worker = restTemplate.exchange("/metrics", HttpMethod.GET, entity(WORKER_TOKEN, null), Map.class);
        assertThat(worker.getStatusCode().value()).as("WORKER must not read business metrics").isEqualTo(403);
        assertBodyLeaksNothing(worker);

        ResponseEntity<Map> none = restTemplate.exchange("/metrics", HttpMethod.GET, entity(null, null), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
    }
}
