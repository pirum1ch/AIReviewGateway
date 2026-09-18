package com.review.gateway;

import com.review.gateway.model.Backend;
import com.review.gateway.repository.BackendRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backend Self-Registration role matrix (BSQ-12), the {@code gateway.backend.self-registration.enabled
 * = true} half — a separate Spring context (different property, own context-cache entry) from {@link
 * SecurityMatrixTest}, which covers the (default) disabled half on the shared context. Also covers
 * BSQ-12's explicit trailing-slash / percent-encoded-path callout for {@code /backends/announce}.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
@TestPropertySource(properties = {
        "gateway.backend.self-registration.enabled=true",
        "gateway.backend.allowed-host-pattern=^192\\.168\\..*"
})
class SecurityMatrixSelfRegistrationEnabledTest {

    private static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    private static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";
    private static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BackendRepository backendRepository;
    @LocalServerPort
    private int port;

    @AfterEach
    void cleanUp() {
        backendRepository.deleteAll();
    }

    private HttpEntity<?> entity(String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> announceBody(String backendId) {
        return Map.of("backendId", backendId, "workerId", "worker-matrix-enabled",
                "url", "http://192.168.1.77:8080", "model", "model-x");
    }

    @Test
    void announceRequiresWorkerExactlyWhenFlagIsEnabled() {
        ResponseEntity<Map> worker = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(WORKER_TOKEN, announceBody("mac-mini-enabled-1")), Map.class);
        assertThat(worker.getStatusCode().value()).as("WORKER reaches the handler and the announce succeeds").isEqualTo(200);

        ResponseEntity<Map> ci = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(CI_TOKEN, announceBody("mac-mini-enabled-2")), Map.class);
        assertThat(ci.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> admin = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(ADMIN_TOKEN, announceBody("mac-mini-enabled-3")), Map.class);
        assertThat(admin.getStatusCode().value()).as("ADMIN must NOT be granted WORKER's announce privilege").isEqualTo(403);

        ResponseEntity<Map> none = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(null, announceBody("mac-mini-enabled-4")), Map.class);
        assertThat(none.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void workerCannotReachTheAdminWriteEndpointsEvenWithTheFlagEnabled() {
        ResponseEntity<Map> workerPost = restTemplate.exchange("/backends", HttpMethod.POST,
                entity(WORKER_TOKEN, Map.of("name", "x", "url", "http://192.168.1.1:8080", "model", "m")), Map.class);
        assertThat(workerPost.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<Map> workerDelete = restTemplate.exchange("/backends/x", HttpMethod.DELETE,
                entity(WORKER_TOKEN, null), Map.class);
        assertThat(workerDelete.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void adminCannotReachTheAnnounceEndpointEvenWithTheFlagEnabled() {
        ResponseEntity<Map> admin = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(ADMIN_TOKEN, announceBody("mac-mini-enabled-5")), Map.class);
        assertThat(admin.getStatusCode().value()).isEqualTo(403);
    }

    /**
     * BSQ-11: through the REAL running application (WebConfig's filter registration + the filter's own
     * pattern), not a direct unit test of {@code RequestBodySizeLimitFilter} -- see the twin test in
     * {@code SecurityMatrixTest} for {@code POST /backends} for the full rationale (BST-09).
     */
    @Test
    void announceRejectsAnOversizedBodyThroughTheRunningApplication() {
        String oversizedJson = "{\"backendId\":\"matrix-oversized\",\"workerId\":\"worker-matrix\","
                + "\"url\":\"http://192.168.1.80:8080\",\"model\":\"" + "a".repeat(9 * 1024) + "\"}";
        byte[] bodyBytes = oversizedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + WORKER_TOKEN);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Set explicitly -- see the twin SecurityMatrixTest#postBackendsRejects... test's comment for why
        // (TestRestTemplate's default client can switch to chunked transfer for large bodies, which would
        // bypass this Content-Length-only filter regardless of server-side behavior).
        headers.setContentLength(bodyBytes.length);

        ResponseEntity<Map> response = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                new HttpEntity<>(oversizedJson, headers), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    // -------------------------------------------------------- QA: full register/steal/override lifecycle ---

    /**
     * QA round (task item 3): the register -&gt; steal attempt -&gt; owner re-announce -&gt; admin override
     * sequence, exercised through the real HTTP surface end to end (the developer's own tests exercise the
     * same decision table at the service layer, {@code BackendRegistryServiceTest} -- this is the same
     * invariants proven through {@code SecurityConfig}/the controllers/JSON (de)serialization as well).
     */
    @Test
    void registerStealAttemptReannounceThenAdminOverrideLifecycle() {
        String name = "mac-mini-lifecycle";

        // 1. A fresh announce registers the backend, owned by worker-original.
        ResponseEntity<Map> registered = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(WORKER_TOKEN, Map.of("backendId", name, "workerId", "worker-original",
                        "url", "http://192.168.1.90:8080", "model", "model-x")), Map.class);
        assertThat(registered.getStatusCode().value()).isEqualTo(200);
        assertThat(registered.getBody()).containsEntry("created", true);
        assertThat(registered.getBody()).doesNotContainKey("url"); // BSQ-17

        // 2. A different workerId tries to steal the name -- 409, zero mutation.
        ResponseEntity<Map> stealAttempt = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(WORKER_TOKEN, Map.of("backendId", name, "workerId", "worker-attacker",
                        "url", "http://192.168.1.91:9090", "model", "model-evil")), Map.class);
        assertThat(stealAttempt.getStatusCode().value()).isEqualTo(409);
        Backend afterSteal = backendRepository.findByName(name).orElseThrow();
        assertThat(afterSteal.getUrl()).isEqualTo("http://192.168.1.90:8080");
        assertThat(afterSteal.getModel()).isEqualTo("model-x");
        assertThat(afterSteal.getAnnouncedBy()).isEqualTo("worker-original");

        // 3. The original owner re-announces with a new address -- accepted, url updates.
        ResponseEntity<Map> reannounce = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(WORKER_TOKEN, Map.of("backendId", name, "workerId", "worker-original",
                        "url", "http://192.168.1.92:8080", "model", "model-x")), Map.class);
        assertThat(reannounce.getStatusCode().value()).isEqualTo(200);
        assertThat(reannounce.getBody()).containsEntry("created", false);
        Backend afterReannounce = backendRepository.findByName(name).orElseThrow();
        assertThat(afterReannounce.getUrl()).isEqualTo("http://192.168.1.92:8080");
        assertThat(afterReannounce.getAnnouncedBy()).isEqualTo("worker-original");

        // 4. An admin call overrides the row (host replacement) and releases self-registration ownership.
        ResponseEntity<Map> adminOverride = restTemplate.exchange("/backends", HttpMethod.POST,
                entity(ADMIN_TOKEN, Map.of("name", name, "url", "http://192.168.1.93:8080", "model", "model-x")),
                Map.class);
        assertThat(adminOverride.getStatusCode().value()).isEqualTo(200); // update, not insert
        assertThat(adminOverride.getBody()).doesNotContainKey("url"); // BSQ-17
        assertThat(adminOverride.getBody()).containsKey("announcedBy");
        assertThat(adminOverride.getBody().get("announcedBy")).isNull();
        Backend afterAdmin = backendRepository.findByName(name).orElseThrow();
        assertThat(afterAdmin.getUrl()).isEqualTo("http://192.168.1.93:8080");
        assertThat(afterAdmin.getAnnouncedBy()).isNull();

        // 5. The name is unowned again -- even the former "attacker" workerId may now claim it (first
        // claim, same url as the admin just set -- BSQ-01 permits this).
        ResponseEntity<Map> claimAfterRelease = restTemplate.exchange("/backends/announce", HttpMethod.POST,
                entity(WORKER_TOKEN, Map.of("backendId", name, "workerId", "worker-attacker",
                        "url", "http://192.168.1.93:8080", "model", "model-x")), Map.class);
        assertThat(claimAfterRelease.getStatusCode().value()).isEqualTo(200);
        Backend afterClaim = backendRepository.findByName(name).orElseThrow();
        assertThat(afterClaim.getAnnouncedBy()).isEqualTo("worker-attacker");
    }

    // ---------------------------------------------------------- BSQ-12: trailing-slash / percent-encoded ---

    @Test
    void trailingSlashOnAnnounceDoesNotReachTheHandlerUnderADifferentRule() {
        ResponseEntity<Map> worker = restTemplate.exchange("/backends/announce/", HttpMethod.POST,
                entity(WORKER_TOKEN, announceBody("mac-mini-enabled-6")), Map.class);
        // Must not silently succeed as if it were the canonical "/backends/announce" path under a laxer
        // rule; Spring's own trailing-slash handling means this is either 404 (no matching mapping) or a
        // security rejection -- either way, never a 200 for a request whose canonical form wasn't hit.
        assertThat(worker.getStatusCode().value()).isIn(404, 401, 403);
    }

    @Test
    void percentEncodedAnnouncePathDoesNotBypassTheWorkerOnlyRule() {
        // /backends/%61nnounce decodes to /backends/announce -- must be governed by the SAME rule as the
        // canonical path, not fall through to a laxer one. Built as a raw java.net.URI (not the
        // String-based TestRestTemplate#exchange overload, which treats its argument as a URI TEMPLATE and
        // re-encodes the literal '%' -- that client-side quirk, not server-side routing, is what a naive
        // version of this test would actually be exercising).
        URI ciUri = URI.create("http://localhost:" + port + "/backends/%61nnounce");
        ResponseEntity<Map> ci = restTemplate.getRestTemplate()
                .exchange(ciUri, HttpMethod.POST, entity(CI_TOKEN, announceBody("mac-mini-enabled-7")), Map.class);
        assertThat(ci.getStatusCode().value()).isIn(403, 404);

        URI workerUri = URI.create("http://localhost:" + port + "/backends/%61nnounce");
        ResponseEntity<Map> worker = restTemplate.getRestTemplate()
                .exchange(workerUri, HttpMethod.POST, entity(WORKER_TOKEN, announceBody("mac-mini-enabled-8")), Map.class);
        assertThat(worker.getStatusCode().value()).isIn(200, 404);
    }
}
