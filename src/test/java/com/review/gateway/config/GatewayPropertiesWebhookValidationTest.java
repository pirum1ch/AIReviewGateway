package com.review.gateway.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code gateway.webhook.*} startup validation (threat model WHR-01..30), enforced only when
 * {@code gateway.webhook.enabled=true}. See {@link GatewayPropertiesPromptValidationTest} for the
 * corresponding {@code gateway.prompt.*} suite and {@link GatewayPropertiesValidationTest} for the
 * pre-existing SR-01/SR-15 token/URL rules this webhook config reuses.
 */
class GatewayPropertiesWebhookValidationTest {

    private GatewayProperties validProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken("a".repeat(32));
        properties.getSecurity().setWorkerToken("b".repeat(32));
        properties.getSecurity().setAdminToken("c".repeat(32));
        properties.getGitlab().setToken("d".repeat(32));
        properties.getGitlab().setBaseUrl("https://gitlab.example.com/api/v4");
        properties.getGitlab().setDiffToken("f".repeat(32));

        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setSecretTokens(Set.of("g".repeat(32)));
        properties.getWebhook().setBotUserId(35L);
        // promptVersion/path/botUsername/sweep/rate-limit bounds all keep their valid class defaults.
        return properties;
    }

    @Test
    void validWebhookConfigurationPassesValidation() {
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void disabledWebhookSkipsAllWebhookValidation() {
        GatewayProperties properties = validProperties();
        properties.getWebhook().setEnabled(false);
        properties.getWebhook().setBotUsername(null); // would otherwise fail F-WH-06's check

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    /** F-WH-06: bot-username reaches a GitLab query parameter unpinned before this fix. */
    @Test
    void botUsernameContainingQueryInjectionCharactersRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getWebhook().setBotUsername("ai-review-bot&scope=all&state=all");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-username");
    }

    @Test
    void blankBotUsernameRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getWebhook().setBotUsername("");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot-username");
    }

    @Test
    void aWellFormedBotUsernamePassesValidation() {
        GatewayProperties properties = validProperties();
        properties.getWebhook().setBotUsername("ai-review-bot.v2");

        assertThatCode(() -> properties.validateOnStartup()).doesNotThrowAnyException();
    }
}
