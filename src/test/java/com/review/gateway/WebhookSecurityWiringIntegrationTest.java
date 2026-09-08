package com.review.gateway;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitLab Webhook Diff Trigger -- the gap QA was explicitly asked to close: every existing test exercises
 * {@code GitLabWebhookSecretFilter}, {@code WebhookController} and {@code SecurityConfig} either in
 * isolation ({@code GitLabWebhookSecretFilterTest}, {@code WebhookControllerTest}) or only the
 * feature-<b>disabled</b> case over the real chain ({@code SecurityMatrixTest#webhookPathDoesNotExistWhenFeatureDisabled},
 * against this suite's shared context where {@code gateway.webhook.enabled=false} is the default). Nobody
 * had sent a real HTTP request through the actual {@code SecurityFilterChain} with
 * {@code gateway.webhook.enabled=true} before this class.
 *
 * <p>Mirrors {@code StructuredOutputEndToEndIntegrationTest}'s pattern exactly: a full
 * {@code @SpringBootTest} + Zonky Postgres + {@code @DynamicPropertySource} context, deliberately
 * separate from the shared default-properties context so the feature can be turned on here without
 * affecting every other test class (test resources {@code application.yml}'s documented convention for
 * kill-switched features).
 *
 * <p>Threats/requirements covered: WHT-23/WHR-01/WHR-02 (fail-closed authorization inside
 * {@code authorizeHttpRequests}, never {@code permitAll} + a side filter), WHR-04 (a correct token from
 * the configured set authenticates), WHT-24/WHR-02 (feature-off means the path does not exist --
 * re-verified here against a context where {@code webhook.enabled=true} for every OTHER property, to
 * prove the kill-switch itself -- not merely "no config" -- is what gates the endpoint), and
 * WHR-08/WOR-09 (oversized body -&gt; 413 before any parsing, including via a percent-encoded path that
 * must not bypass the cap or the security matcher).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class WebhookSecurityWiringIntegrationTest {

    private static final String CORRECT_SECRET = "webhook-e2e-secret-0123456789012345"; // 36 chars, >=32 (WHR-04)
    private static final String WRONG_SECRET = "totally-wrong-webhook-secret-value-000000";
    private static final String WEBHOOK_PATH = "/webhooks/gitlab";

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void enableWebhook(DynamicPropertyRegistry registry) {
        registry.add("gateway.webhook.enabled", () -> "true");
        registry.add("gateway.webhook.secret-tokens", () -> CORRECT_SECRET);
        registry.add("gateway.webhook.bot-user-id", () -> "35"); // the real ai-review-bot id per the plan
        registry.add("gateway.gitlab.diff-token", () -> "test-diff-token-0123456789012345"); // presence-only (PMR-15)
        // WHR-08: small enough that a trivially oversized body below deterministically trips the cap.
        registry.add("gateway.webhook.max-request-body-bytes", () -> "64");
    }

    /** A body that is not a recognizable merge_request event (WHR-07: "not an interesting event" is a
     *  coarse-accept outcome) -- deliberately avoids the test needing a reachable GitLab, since the point
     *  of this class is the security wiring, not the fetch/verify/assemble pipeline (already covered by
     *  {@code WebhookReviewTriggerServiceTest}/{@code WebhookReviewTriggerServiceIntegrationTest}). */
    private static final String NOT_INTERESTING_EVENT_BODY = "{\"object_kind\":\"push\"}";

    private HttpHeaders headersWithToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("X-Gitlab-Token", token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ---------------------------------------------------------------------------------------------
    // WHR-01/WHR-02/WHR-07: correct token reaches WebhookController and is coarsely accepted.
    // ---------------------------------------------------------------------------------------------

    @Test
    void correctTokenReachesTheControllerAndIsCoarselyAccepted() {
        ResponseEntity<Void> response = restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(NOT_INTERESTING_EVENT_BODY, headersWithToken(CORRECT_SECRET)), Void.class);

        assertThat(response.getStatusCode().value())
                .as("a correctly-authenticated, in-limit request must reach WebhookController and get WHR-07's coarse accept")
                .isEqualTo(202);
    }

    // ---------------------------------------------------------------------------------------------
    // WHT-23/WHR-01/WHR-02: wrong/missing token is rejected by the filter chain itself, never the
    // controller -- proven by asserting the 401 body is SecurityConfig's own ErrorResponse shape
    // (WebhookController never returns a body at all -- it returns 202 with none), which is only
    // producible by the authenticationEntryPoint firing before authorizeHttpRequests lets the request
    // reach the controller.
    // ---------------------------------------------------------------------------------------------

    @Test
    void wrongTokenIsRejectedByTheFilterChainNeverReachingTheController() {
        ResponseEntity<Map> response = restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(NOT_INTERESTING_EVENT_BODY, headersWithToken(WRONG_SECRET)), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("error", "UNAUTHORIZED");
        assertThat(String.valueOf(response.getBody())).doesNotContain("Exception").doesNotContainIgnoringCase("stacktrace");
    }

    @Test
    void missingTokenIsRejectedByTheFilterChainNeverReachingTheController() {
        ResponseEntity<Map> response = restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(NOT_INTERESTING_EVENT_BODY, headersWithToken(null)), Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("error", "UNAUTHORIZED");
    }

    // ---------------------------------------------------------------------------------------------
    // WHR-08/WOR-09: an oversized body is rejected 413 by RequestBodySizeLimitFilter before any
    // parsing -- including via a percent-encoded path variant, which must not bypass either the size
    // cap or the security matcher (the WOR-09 lesson this threat model explicitly calls out, §7).
    // ---------------------------------------------------------------------------------------------

    @Test
    void oversizedBodyIsRejectedWith413BeforeAnyParsing() {
        String oversizedBody = "{\"object_kind\":\"push\",\"padding\":\"" + "x".repeat(200) + "\"}";

        ResponseEntity<String> response = restTemplate.exchange(WEBHOOK_PATH, HttpMethod.POST,
                new HttpEntity<>(oversizedBody, headersWithToken(CORRECT_SECRET)), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void percentEncodedPathVariantCannotBypassTheSizeCapOrTheSecurityMatcher() {
        // "%67" decodes to "g" -- "/webhooks/%67itlab" decodes to the exact same path as WEBHOOK_PATH.
        URI percentEncodedUri = URI.create(restTemplate.getRootUri() + "/webhooks/%67itlab");
        String oversizedBody = "{\"object_kind\":\"push\",\"padding\":\"" + "x".repeat(200) + "\"}";

        ResponseEntity<String> response = restTemplate.exchange(percentEncodedUri, HttpMethod.POST,
                new HttpEntity<>(oversizedBody, headersWithToken(CORRECT_SECRET)), String.class);

        assertThat(response.getStatusCode().value())
                .as("a percent-encoded webhook path must be capped exactly like the plain path (WOR-09)")
                .isEqualTo(413);
    }

    @Test
    void percentEncodedPathVariantStillRequiresTheCorrectTokenAndAcceptsWithinLimit() {
        URI percentEncodedUri = URI.create(restTemplate.getRootUri() + "/webhooks/%67itlab");

        ResponseEntity<Void> withCorrectToken = restTemplate.exchange(percentEncodedUri, HttpMethod.POST,
                new HttpEntity<>(NOT_INTERESTING_EVENT_BODY, headersWithToken(CORRECT_SECRET)), Void.class);
        assertThat(withCorrectToken.getStatusCode().value()).isEqualTo(202);

        ResponseEntity<Map> withWrongToken = restTemplate.exchange(percentEncodedUri, HttpMethod.POST,
                new HttpEntity<>(NOT_INTERESTING_EVENT_BODY, headersWithToken(WRONG_SECRET)), Map.class);
        assertThat(withWrongToken.getStatusCode().value()).isEqualTo(401);
    }
}
