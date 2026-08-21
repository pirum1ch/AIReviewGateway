package com.review.gateway.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worker Observability &amp; Claim Latency (WOR-01/WOR-14/WOR-16) fail-fast startup validation:
 * {@code gateway.retry.requeue-delay} coupling to {@code gateway.backend.failure-grace}, the
 * {@code not_before} upper cap, and the SR-06 stale-duplicate-report bound. Same pattern as
 * {@code GatewayPropertiesValidationTest}: {@code validateOnStartup()} is package-private and invoked
 * directly here rather than paying for a full context boot per case.
 */
class GatewayPropertiesRetryAndBackendHealthValidationTest {

    private GatewayProperties validProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken("a".repeat(32));
        properties.getSecurity().setWorkerToken("b".repeat(32));
        properties.getSecurity().setAdminToken("c".repeat(32));
        properties.getGitlab().setToken("d".repeat(32));
        properties.getGitlab().setBaseUrl("https://gitlab.example.com/api/v4");
        properties.getPrompt().setEnabled(false);
        return properties;
    }

    @Test
    void shippedDefaultsPassValidation() {
        // requeue-delay=90s, max-attempts=3, failure-grace=180s: 90*(3-1)=180 >= 180.
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void theOriginalArchitectureDocDefaultOf30sFailsStartup() {
        // WOR-01: the corrected shipped default is 90s, not the architecture doc's original 30s -- this
        // pins that regression so it can never silently come back.
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(30));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay")
                .hasMessageContaining("failure-grace");
    }

    @Test
    void requeueDelayTooSmallForTheAttemptBudgetFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setMaxAttempts(3);
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(89)); // 89*2=178 < 180

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay");
    }

    @Test
    void requeueDelayExactlyMeetingTheBudgetPasses() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setMaxAttempts(3);
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(90)); // 90*2=180 >= 180

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void bothZeroIsTheDocumentedEscapeHatchAndStartsWithoutError() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ZERO);
        properties.getBackend().setFailureGrace(Duration.ZERO);

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void requeueDelayZeroAloneFailsStartup() {
        // WOR-01: neither may be zeroed alone -- this is the paired escape hatch, not two independent knobs.
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ZERO);
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both be 0");
    }

    @Test
    void failureGraceZeroAloneFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(90));
        properties.getBackend().setFailureGrace(Duration.ZERO);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both be 0");
    }

    @Test
    void requeueDelayBelowTheWorkerRequestTimeoutBoundFailsStartup() {
        // WOR-16: bounded by the Worker's network.gateway-timeout-sec (10s default), not poll-interval
        // (3s) as the architecture doc originally (incorrectly) stated.
        GatewayProperties properties = validProperties();
        properties.getRetry().setMaxAttempts(1); // so the WOR-01 budget check can't also fire here
        properties.getBackend().setFailureGrace(Duration.ofSeconds(60));
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(5));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay")
                .hasMessageContaining("network.gateway-timeout-sec");
    }

    @Test
    void requeueDelayAboveTheTenMinuteCapFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ofMinutes(11));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay");
    }

    @Test
    void negativeRequeueDelayFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(-1));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay");
    }

    @Test
    void shippedDefaultsAlsoPassTheDispatchDetectionLatencyCheck() {
        // F-WOC-01: requeue-delay=90s * (max-attempts-1=2) = 180s budget window must also be >=
        // backend-health-interval (60s) + backend.read-timeout (10s) = 70s -- the property that is now
        // actually load-bearing for WOR-01, given BackendDispatcher's fail-fast dispatch decline.
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void requeueDelayTooSmallForTheDispatchDetectionLatencyFailsStartupEvenWhenTheOldFailureGraceCheckWouldPass() {
        // F-WOC-01: construct a case that passes the OLD (failure-grace-only) formula but fails the NEW
        // (detection-latency) one -- proves the new check is independently load-bearing, not redundant
        // with the existing failure-grace check. requeue-delay=8s, max-attempts=2 -> budget window=8s,
        // which is < backend-health-interval(60s)+read-timeout(10s)=70s, but also chosen so
        // failure-grace itself is lowered to 8s (>= the 8s min-nonzero-requeue-delay bound and >= the
        // 60s... no: failure-grace must also be >= backend-health-interval, so this scenario instead
        // lowers backend-health-interval to make the old check pass while the new one still fails).
        GatewayProperties properties = validProperties();
        properties.getRetry().setMaxAttempts(2);
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(15)); // WOR-16 floor
        properties.getBackend().setFailureGrace(Duration.ofSeconds(15)); // old check: 15*(2-1)=15 >= 15, passes
        properties.getScheduler().setBackendHealthInterval(Duration.ofSeconds(15)); // >= failure-grace, passes
        properties.getBackend().setReadTimeout(java.time.Duration.ofSeconds(60)); // new check: 15 < 15+60=75

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requeue-delay")
                .hasMessageContaining("backend-health-interval")
                .hasMessageContaining("read-timeout");
    }

    @Test
    void requeueDelayExactlyMeetingTheDispatchDetectionLatencyPasses() {
        GatewayProperties properties = validProperties();
        properties.getRetry().setMaxAttempts(2);
        properties.getBackend().setFailureGrace(Duration.ofSeconds(15));
        properties.getScheduler().setBackendHealthInterval(Duration.ofSeconds(15));
        properties.getBackend().setReadTimeout(java.time.Duration.ofSeconds(60));
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(75)); // 75*(2-1)=75 >= 15+60=75

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void failureGraceShorterThanTheProbeIntervalFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getScheduler().setBackendHealthInterval(Duration.ofSeconds(60));
        properties.getBackend().setFailureGrace(Duration.ofSeconds(30));
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(90));

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure-grace");
    }

    @Test
    void maxFailBodyBytesMustBePositive() {
        GatewayProperties properties = validProperties();
        properties.getJob().setMaxFailBodyBytes(0);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-fail-body-bytes");
    }
}
