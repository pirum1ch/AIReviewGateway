package com.review.worker.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation matrix for {@link WorkerProperties#validateOnStartup()} plus the JSR-380 annotations that
 * back it. {@code validateOnStartup()} is called directly (it is package-private, and this test lives in
 * the same package) rather than through a Spring context, so each rule can be exercised in isolation
 * without booting the application.
 */
class WorkerPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    private WorkerProperties validProperties() {
        return validProperties("127.0.0.1");
    }

    private WorkerProperties validProperties(String managementServerAddress) {
        WorkerProperties properties = new WorkerProperties(managementServerAddress);
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("qwen2.5-coder");
        return properties;
    }

    @Test
    void validConfigurationPassesStartupValidation() {
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void blankGatewayUrlFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getGateway().setUrl(" ");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.url");
    }

    @Test
    void plainHttpGatewayUrlFailsFastWithoutInsecureFlag() {
        WorkerProperties properties = validProperties();
        properties.getGateway().setUrl("http://gateway.internal");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void plainHttpGatewayUrlFailsFastOnNonLoopbackEvenWithInsecureFlag() {
        WorkerProperties properties = validProperties();
        properties.getGateway().setUrl("http://gateway.internal");
        properties.getWorker().setAllowInsecureGateway(true);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void plainHttpGatewayUrlAllowedOnLoopbackWithInsecureFlag() {
        WorkerProperties properties = validProperties();
        properties.getGateway().setUrl("http://127.0.0.1:9000");
        properties.getWorker().setAllowInsecureGateway(true);
        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void nonHttpGatewaySchemeFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getGateway().setUrl("ftp://gateway.internal");
        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankLlamaUrlFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getLlama().setUrl("");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("llama.url");
    }

    @Test
    void nonLoopbackLlamaUrlDoesNotFailStartup() {
        // WSR-06 is a SHOULD, not a MUST: this only warns, it must never block startup.
        WorkerProperties properties = validProperties();
        properties.getLlama().setUrl("http://llama.internal:8000");
        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void nonPositivePollIntervalFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getNetwork().setPollIntervalMs(0);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pollIntervalMs");
    }

    @Test
    void nonPositiveRequestTimeoutFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getNetwork().setRequestTimeoutSec(-5);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requestTimeoutSec");
    }

    @Test
    void nonPositiveGatewayTimeoutFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getNetwork().setGatewayTimeoutSec(0);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gatewayTimeoutSec");
    }

    @Test
    void zeroHeartbeatIntervalFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getHeartbeat().setIntervalSec(0);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat.intervalSec");
    }

    @Test
    void heartbeatIntervalAtOrAboveStalenessCeilingFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getHeartbeat().setIntervalSec(180);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat.intervalSec");
    }

    @Test
    void heartbeatIntervalJustBelowCeilingPassesWithoutException() {
        // Above the 90s warn threshold but below the 180s hard ceiling -- must only warn, not fail.
        WorkerProperties properties = validProperties();
        properties.getHeartbeat().setIntervalSec(120);
        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveMaxDiffBytesFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getWorker().getLimits().setMaxDiffBytes(0);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxDiffBytes");
    }

    @Test
    void nonPositiveMaxResponseBytesFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getWorker().getLimits().setMaxResponseBytes(-1);
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxResponseBytes");
    }

    @Test
    void promptLocationNotClasspathFailsFast() {
        WorkerProperties properties = validProperties();
        properties.getPrompt().setLocation("file:/etc/worker/prompts/");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath:");
    }

    @Test
    void blankManagementServerAddressFailsFast() {
        WorkerProperties properties = validProperties("");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.address");
    }

    @Test
    void nonLoopbackManagementServerAddressFailsFast() {
        WorkerProperties properties = validProperties("0.0.0.0");
        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.address");
    }

    @Test
    void loopbackManagementServerAddressVariantsPass() {
        assertThatCode(() -> validProperties("localhost").validateOnStartup()).doesNotThrowAnyException();
        assertThatCode(() -> validProperties("::1").validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void jsr380AnnotationsRejectBlankRequiredFields() {
        WorkerProperties properties = validProperties();
        properties.getWorker().setId("");
        Set<ConstraintViolation<WorkerProperties>> violations = validator.validate(properties);
        assertThat(violations).isNotEmpty();
    }

    @Test
    void jsr380AnnotationsAcceptAFullyPopulatedInstance() {
        WorkerProperties properties = validProperties();
        Set<ConstraintViolation<WorkerProperties>> violations = validator.validate(properties);
        assertThat(violations).isEmpty();
    }
}
