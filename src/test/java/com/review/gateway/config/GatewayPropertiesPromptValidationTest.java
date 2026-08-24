package com.review.gateway.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code gateway.prompt.*} startup validation (PMR-13/14/15/21/30), only enforced when the kill-switch
 * is on — see {@link GatewayPropertiesValidationTest} for the pre-existing SR-01/SR-15 tokens/URL rules.
 */
class GatewayPropertiesPromptValidationTest {

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUpLogCapture() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(GatewayProperties.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        ((Logger) LoggerFactory.getLogger(GatewayProperties.class)).detachAppender(logAppender);
    }

    private GatewayProperties validProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken("a".repeat(32));
        properties.getSecurity().setWorkerToken("b".repeat(32));
        properties.getSecurity().setAdminToken("c".repeat(32));
        properties.getGitlab().setToken("d".repeat(32));
        properties.getGitlab().setBaseUrl("https://gitlab.example.com/api/v4");
        properties.getGitlab().setPromptToken("e".repeat(32));
        properties.getPrompt().setEnabled(true);
        properties.getPrompt().getCorporate().setProject("platform/ai-review-prompts");
        properties.getPrompt().getCorporate().setRef("main");
        properties.getPrompt().getCorporate().setBasePromptPath("prompts/base-system-prompt.md");
        properties.getPrompt().getCorporate().setReviewRulesPath("prompts/review-rules.md");
        // Structured Review Output (threat model SOR-13): the shipped structured.answer-reserve default
        // (8000) does not fit alongside a 6000-token system prompt at the shipped context-window -- that
        // is the exact, intended SOR-13 startup-refusal scenario (see
        // GatewayPropertiesStructuredValidationTest#promptManagerTermIsIncludedWhenPromptManagerIsEnabled).
        // This file tests Prompt Manager validation specifically, so its fixture pins
        // structured.answer-reserve at its minimum legal value (equal to diff.answer-reserve) to stay
        // budget-consistent without that being the thing under test here.
        properties.getStructured().setAnswerReserve(properties.getDiff().getAnswerReserve());
        return properties;
    }

    @Test
    void validPromptConfigurationPassesValidation() {
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void disabledKillSwitchSkipsAllPromptValidation() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setEnabled(false);
        properties.getPrompt().getCorporate().setProject(null); // would otherwise fail

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    /** PMR-10: the kill-switch being off must be logged at WARN on startup, not silent. */
    @Test
    void disabledKillSwitchLogsAWarningOnStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setEnabled(false);

        properties.validateOnStartup();

        boolean warnedAboutKillSwitch = logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("gateway.prompt.enabled=false"));
        assertThat(warnedAboutKillSwitch).isTrue();
    }

    @Test
    void enabledKillSwitchNeverLogsTheDisabledWarning() {
        GatewayProperties properties = validProperties();

        properties.validateOnStartup();

        boolean warnedAboutKillSwitch = logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("gateway.prompt.enabled=false"));
        assertThat(warnedAboutKillSwitch).isFalse();
    }

    // ---- PMR-14: project references only, never a URL/host ----

    @Test
    void urlShapedCorporateProjectRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("https://evil.example/x");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corporate.project");
    }

    @Test
    void projectRefWithSchemeIsRejected() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("http://gitlab.internal/group/project");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    /**
     * F-PM-04 regression (appsec SAST round): PMR-14 requires the project-reference validator to reject
     * {@code ..}, but a bare {@code ..} segment matches {@code PROJECT_REF_PATTERN}'s
     * {@code [A-Za-z0-9._-]+} class, so {@code requireProjectRef} accepted it (unlike {@code requireRef}/
     * {@code requireSourcePath}, which both carry an explicit {@code ..} check). Not exploitable as a URI
     * traversal today — every project reference is expanded as a strictly-encoded URI template variable,
     * so {@code /} becomes {@code %2F} — but it is a defense-in-depth rule the requirement names
     * explicitly, and it is the one validator with no {@code ..} guard.
     */
    @Test
    void corporateProjectRefWithDotDotSegmentRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("group/../other-group/prompts");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corporate.project");
    }

    /** Same rule on the override side, which feeds the same outbound fetch. */
    @Test
    void overrideProjectRefWithDotDotSegmentRefusesStartup() {
        GatewayProperties properties = validProperties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("..");
        properties.getPrompt().getProject().getOverrides().put("1042", override);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overrides[1042].project");
    }

    /** Scope guard: a legitimate dot inside a segment (e.g. a `.github`-style group) still starts. */
    @Test
    void projectRefWithSingleDotsIsStillAccepted() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("org.platform/ai-review.prompts");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void numericProjectIdIsAccepted() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("1042");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void groupPathProjectRefIsAccepted() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("org/team-a/ai-review-prompts");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void blankCorporateProjectRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setProject("");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    // ---- PMR-13: ref/path shape ----

    @Test
    void refWithDotDotIsRejected() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setRef("../../etc/passwd");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pathTraversalInConfiguredPathIsRejected() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setBasePromptPath("../../../etc/passwd");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pathWithLeadingSlashIsRejected() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setBasePromptPath("/etc/passwd");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overLongPathIsRejected() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setBasePromptPath("a".repeat(201) + ".md");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankReviewRulesPathRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getCorporate().setReviewRulesPath(null);

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    // ---- PMR-15: read-only token presence ----

    @Test
    void missingPromptTokenRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getGitlab().setPromptToken(null);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt-token");
    }

    @Test
    void blankPromptTokenRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getGitlab().setPromptToken("");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void promptTokenIsMaskedInToString() {
        GatewayProperties properties = validProperties();

        assertThatCode(() -> {
            String rendered = properties.getGitlab().toString();
            org.assertj.core.api.Assertions.assertThat(rendered).doesNotContain("e".repeat(32));
            org.assertj.core.api.Assertions.assertThat(rendered).contains("MASKED");
        }).doesNotThrowAnyException();
    }

    // ---- overrides ----

    @Test
    void overrideWithUrlShapedProjectRefusesStartup() {
        GatewayProperties properties = validProperties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("https://evil.example/x");
        properties.getPrompt().getProject().getOverrides().put("1042", override);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overrides[1042]");
    }

    @Test
    void overrideWithValidProjectAndNullRefPassesValidation() {
        GatewayProperties properties = validProperties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("org/team-a/ai-review-prompts");
        properties.getPrompt().getProject().getOverrides().put("1042", override);

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void tooManyOverridesRefusesStartup() {
        GatewayProperties properties = validProperties();
        for (int i = 0; i < 501; i++) {
            GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
            override.setProject("org/team-a/repo" + i);
            properties.getPrompt().getProject().getOverrides().put(String.valueOf(i), override);
        }

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    // ---- PMR-21: budget consistency ----

    @Test
    void inconsistentBudgetRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(20000); // larger than the context window itself

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void consistentBudgetPassesValidation() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(6000);

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    // ---- error-handling / message-format enum-shaped strings ----

    @Test
    void invalidOnErrorValueRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().getErrorHandling().setOnError("IGNORE");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidMessageFormatValueRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setMessageFormat("BOGUS");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    // ---- timeouts ----

    @Test
    void totalTimeoutBelowTwiceReadTimeoutRefusesStartup() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setReadTimeout(Duration.ofSeconds(8));
        properties.getPrompt().setTotalTimeout(Duration.ofSeconds(10));

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void totalTimeoutAtLeastTwiceReadTimeoutPassesValidation() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setReadTimeout(Duration.ofSeconds(8));
        properties.getPrompt().setTotalTimeout(Duration.ofSeconds(20));

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }
}
