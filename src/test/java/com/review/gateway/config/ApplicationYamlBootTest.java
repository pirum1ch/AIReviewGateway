package com.review.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-PM-02 regression: boots the <b>real</b> {@code src/main/resources/application.yml} through
 * Spring's own {@code ConfigData}/property-binding machinery (not {@code src/test/resources/
 * application.yml}, which shadows it on the ordinary test classpath and would hide exactly this class
 * of bug), with only the environment variables {@code DEPLOYMENT.md} §2/§10.1 document as required,
 * and asserts {@link GatewayProperties} binds and {@code @PostConstruct}-validates cleanly.
 *
 * <p>This is precisely the test the appsec SAST report (F-PM-02, "Post-fix verification" section)
 * asked for: "a test that boots the real {@code application.yml} property set (which would have
 * caught F-PM-02 mechanically)". Before the fix, this would have failed with {@code IllegalStateException:
 * gateway.gitlab.prompt-token must be set (SR-01) -- refusing to start} on the first test method, because
 * {@code gateway.prompt.enabled} defaulted to {@code true} in Java with no {@code gateway.prompt.*} block
 * in the YAML at all.
 */
class ApplicationYamlBootTest {

    private static final String APPLICATION_YAML_LOCATION =
            "file:" + System.getProperty("user.dir") + "/src/main/resources/application.yml";

    /**
     * The exact §2/§10.1 documented minimum for a Gateway that has not touched Prompt Manager at all:
     * four bearer/GitLab secrets, {@code DB_USER}/{@code DB_PASSWORD} (referenced by {@code
     * spring.datasource.*}, unrelated to this bean but part of the same YAML), and deliberately
     * <b>no</b> {@code PROMPT_MANAGER_ENABLED}/{@code GITLAB_PROMPT_TOKEN}/{@code
     * PROMPT_CORPORATE_PROJECT} -- the "operator hasn't configured Prompt Manager yet" case F-PM-02
     * broke.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(GatewayPropertiesTestConfig.class)
            .withPropertyValues(
                    "spring.config.location=" + APPLICATION_YAML_LOCATION,
                    "CI_TOKEN=" + "a".repeat(32),
                    "WORKER_TOKEN=" + "b".repeat(32),
                    "ADMIN_TOKEN=" + "c".repeat(32),
                    "GITLAB_TOKEN=glpat-xxxxxxxxxxxxxxxxxxxx",
                    "DB_USER=review_gateway",
                    "DB_PASSWORD=unused-in-this-test");

    @Test
    void stockDeploymentWithOnlyTheDocumentedNonPromptEnvVarsBootsCleanly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GatewayProperties properties = context.getBean(GatewayProperties.class);
            // F-PM-02's actual assertion: the shipped default is the safe (off) one, so the
            // gateway.prompt.* validation branch that requires gateway.gitlab.prompt-token never runs.
            assertThat(properties.getPrompt().isEnabled()).isFalse();
        });
    }

    @Test
    void promptManagerOptedInWithItsOwnDocumentedEnvVarsAlsoBootsCleanly() {
        runner.withPropertyValues(
                        "PROMPT_MANAGER_ENABLED=true",
                        "GITLAB_PROMPT_TOKEN=glpat-yyyyyyyyyyyyyyyyyyyy",
                        "PROMPT_CORPORATE_PROJECT=group/ai-review-prompts")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayProperties properties = context.getBean(GatewayProperties.class);
                    assertThat(properties.getPrompt().isEnabled()).isTrue();
                    assertThat(properties.getGitlab().getPromptToken()).isEqualTo("glpat-yyyyyyyyyyyyyyyyyyyy");
                    assertThat(properties.getPrompt().getCorporate().getProject()).isEqualTo("group/ai-review-prompts");
                });
    }

    @Test
    void promptManagerEnabledWithoutItsOwnEnvVarsStillFailsFastAsDesigned() {
        // Not a regression -- confirms the kill-switch's "on" branch still requires its own secret
        // once an operator flips it, rather than silently no-op'ing (that would be a different bug).
        runner.withPropertyValues("PROMPT_MANAGER_ENABLED=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("gateway.gitlab.prompt-token must be set (SR-01) — refusing to start");
                });
    }

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class GatewayPropertiesTestConfig {
    }
}
