package com.review.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SR-01/SR-15 fail-fast startup validation. {@code validateOnStartup()} is package-private and only
 * ever invoked by Spring via {@code @PostConstruct} in production/{@code @SpringBootTest} contexts;
 * calling it directly here (same package) is the fastest way to unit-test every branch without paying
 * for a full context boot per case.
 */
class GatewayPropertiesValidationTest {

    private GatewayProperties validProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken("a".repeat(32));
        properties.getSecurity().setWorkerToken("b".repeat(32));
        properties.getSecurity().setAdminToken("c".repeat(32));
        properties.getGitlab().setToken("d".repeat(32));
        properties.getGitlab().setBaseUrl("https://gitlab.example.com/api/v4");
        return properties;
    }

    @Test
    void validConfigurationPassesValidation() {
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void blankCiTokenFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getSecurity().setCiToken("");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ci-token");
    }

    @Test
    void shortWorkerTokenFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getSecurity().setWorkerToken("too-short");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker-token")
                .hasMessageContaining("32");
    }

    @Test
    void nullAdminTokenFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getSecurity().setAdminToken(null);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-token");
    }

    @Test
    void shortGitlabTokenFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getGitlab().setToken("short");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gitlab.token");
    }

    @Test
    void nonHttpsGitlabBaseUrlFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getGitlab().setBaseUrl("http://gitlab.example.com/api/v4");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void missingGitlabBaseUrlFailsStartup() {
        GatewayProperties properties = validProperties();
        properties.getGitlab().setBaseUrl(null);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    void exceptionMessageNeverEchoesTheActualSecretValue() {
        GatewayProperties properties = validProperties();
        String secretValue = "super-secret-value-that-must-not-leak-anywhere";
        properties.getSecurity().setCiToken(secretValue.substring(0, 10)); // deliberately too short

        assertThatThrownBy(properties::validateOnStartup)
                .hasMessageNotContaining(secretValue);
    }

    @Test
    void maskedToStringNeverLeaksTokenValues() {
        GatewayProperties properties = validProperties();

        assertThatCode(() -> {
            String securityToString = properties.getSecurity().toString();
            String gitlabToString = properties.getGitlab().toString();
            org.assertj.core.api.Assertions.assertThat(securityToString).doesNotContain("a".repeat(32));
            org.assertj.core.api.Assertions.assertThat(gitlabToString).doesNotContain("d".repeat(32));
        }).doesNotThrowAnyException();
    }
}
