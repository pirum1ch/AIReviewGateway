package com.review.gateway;

import com.review.gateway.model.Backend;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.TimeoutManager;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round (structured-review-output), SRO-36/CSR-17-shaped concurrency coverage requested for this
 * feature: races a structured (v3) job's Gateway-side validation-failure retry path (which -- per
 * SRO-36 -- calls {@code RetryManager.requeueOrFail} from {@code ResultProcessor.process()} <b>after</b>
 * phase 1's own transaction has already committed and released the job-row lock, exactly the
 * "validation failure routes through RetryManager from outside the phase-1 transaction" shape this
 * project has a history of subtle lock-ordering bugs in (see {@code ClaimCancelObsoleteConcurrencyTest}'s
 * F-DC-08 javadoc for the prior instance of this bug class) -- concurrently against a stale-heartbeat
 * sweep ({@code TimeoutManager.sweepStaleHeartbeats}, the same {@code RetryManager.requeueOrFail} entry
 * point, called independently) targeting the exact same job row.
 *
 * <p>Both call sites are supposed to be safe to race by construction: {@code RetryManager.requeueOrFail}
 * takes the job row {@code FOR UPDATE} and is documented idempotent -- "if it already left RUNNING ...
 * this is a silent no-op". This test asserts that contract holds under a genuine concurrent race rather
 * than trusting the docstring: no deadlock, no unhandled 5xx, and -- architecture §12 T-4.8's own wording
 * -- exactly one {@code RETRY} event and exactly one attempt increment for the job, never two.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class StructuredValidationRetrySweepConcurrencyTest {

    private static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    private static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private TimeoutManager timeoutManager;

    @DynamicPropertySource
    static void allowV3(DynamicPropertyRegistry registry) {
        registry.add("gateway.review.allowed-prompt-versions", () -> "v1,v2,v3");
    }

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private HttpHeaders headersFor(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private static final String SINGLE_FILE_DIFF =
            "diff --git a/A.java b/A.java\nindex 111..222 100644\n--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+x\n";

    @Test
    void aStructuredValidationFailureRacingAStaleHeartbeatSweepOnTheSameJobNeverDeadlocksAndProducesExactlyOneRetry()
            throws Exception {
        Backend backend = new Backend("backend-race-sweep", "http://192.168.1.80:8080", "model-x", 5);
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);

        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("projectId", 700L);
        createBody.put("mergeRequestId", 7000L);
        createBody.put("headSha", "sha-race-sweep");
        createBody.put("baseSha", "base-sha-race-sweep");
        createBody.put("diff", SINGLE_FILE_DIFF);
        createBody.put("promptVersion", "v3");
        createBody.put("priority", 10);
        ResponseEntity<Map> created = restTemplate.exchange("/reviews", HttpMethod.POST,
                new HttpEntity<>(createBody, headersFor(CI_TOKEN)), Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Number reviewId = (Number) created.getBody().get("reviewId");

        Map<String, Object> claimBody = Map.of("backendId", "backend-race-sweep", "workerId", "worker-race-sweep");
        ResponseEntity<Map> claimed = restTemplate.exchange("/jobs/claim", HttpMethod.POST,
                new HttpEntity<>(claimBody, headersFor(WORKER_TOKEN)), Map.class);
        assertThat(claimed.getStatusCode().value()).isEqualTo(200);
        Long jobId = ((Number) claimed.getBody().get("jobId")).longValue();

        // Backdate the heartbeat so TimeoutManager's sweep sees this job as a genuine stale-heartbeat
        // candidate, racing it against the validation-failure retry path below on the SAME job row.
        ReviewJob job = reviewJobRepository.findById(jobId).orElseThrow();
        job.setHeartbeatAt(Instant.now().minus(Duration.ofMinutes(10)));
        reviewJobRepository.saveAndFlush(job);

        List<Integer> unexpectedStatuses = new CopyOnWriteArrayList<>();

        Callable<Void> submitInvalidResult = () -> {
            Map<String, Object> resultBody = new LinkedHashMap<>();
            resultBody.put("workerId", "worker-race-sweep");
            resultBody.put("rawResponse", "not valid json at all");
            resultBody.put("promptTokens", 10);
            resultBody.put("completionTokens", 5);
            resultBody.put("durationMs", 100);
            resultBody.put("model", "model-x");
            ResponseEntity<Map> response = restTemplate.exchange("/jobs/" + jobId + "/result", HttpMethod.POST,
                    new HttpEntity<>(resultBody, headersFor(WORKER_TOKEN)), Map.class);
            if (response.getStatusCode().value() >= 500) {
                unexpectedStatuses.add(response.getStatusCode().value());
            }
            return null;
        };
        Callable<Void> sweepStaleHeartbeats = () -> {
            // Direct call, exactly as ScheduledJobs' @Scheduled tick would invoke it -- no HTTP layer
            // involved for this side of the race, matching how the real sweep actually runs.
            timeoutManager.sweepStaleHeartbeats();
            return null;
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = executor.invokeAll(
                    List.of(submitInvalidResult, sweepStaleHeartbeats), 30, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                future.get(); // surfaces any exception thrown inside a Callable (e.g. a deadlock exception)
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpectedStatuses)
                .as("no request should ever surface an unhandled 5xx from this race")
                .isEmpty();

        ReviewJob reloaded = reviewJobRepository.findById(jobId).orElseThrow();
        assertThat(reloaded.getAttempts())
                .as("exactly one of the two racing requeueOrFail callers must actually apply the "
                        + "transition -- the other must see the job already left RUNNING and no-op, per "
                        + "RetryManager.requeueOrFail's documented idempotency contract")
                .isEqualTo(1);

        // T-4.8's own wording is "exactly one RETRY", but this codebase records a RETRY transition at
        // BOTH the job-state-machine layer (reason text "heartbeat timeout (attempt N/M)" or
        // "structured-output: <CLASS>...") AND the review-state-machine layer (a DIFFERENT, chunk-summary
        // detail string, e.g. "chunks=1 completed=0 running=0 failed=0") for a single logical
        // requeueOrFail application -- confirmed empirically (two rows per single successful retry, one
        // per layer, with genuinely different detail text). So the discriminating signal for "exactly one
        // *application*" is not the raw row count or a single shared detail string, but that the two
        // mutually exclusive job-level reason prefixes never BOTH appear: a genuine double-application
        // (both racers winning) would leave both a "heartbeat timeout" row AND a "structured-output: "
        // row, never just one -- and would also have shown up above as attempts == 2, which already
        // failed first if it had happened.
        List<String> retryDetails = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId.longValue()).stream()
                .filter(event -> event.getEventType() == EventType.RETRY)
                .map(event -> event.getDetails() == null ? "" : event.getDetails())
                .toList();
        boolean sweepReasonPresent = retryDetails.stream().anyMatch(d -> d.startsWith("heartbeat timeout"));
        boolean validationReasonPresent = retryDetails.stream().anyMatch(d -> d.startsWith("structured-output:"));
        assertThat(sweepReasonPresent ^ validationReasonPresent)
                .as("T-4.8: a concurrent stale-heartbeat sweep on the same job produces exactly one logical "
                        + "RETRY -- exactly one of the two racers' reasons ('heartbeat timeout' xor "
                        + "'structured-output: ...') is ever recorded, never both. Recorded details: " + retryDetails)
                .isTrue();

        ResponseEntity<Map> finalStatus = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.GET,
                new HttpEntity<>(headersFor(CI_TOKEN)), Map.class);
        assertThat(finalStatus.getBody().get("status")).isEqualTo("QUEUED");
        assertThat(((Number) finalStatus.getBody().get("attempts")).intValue()).isEqualTo(1);
    }
}
