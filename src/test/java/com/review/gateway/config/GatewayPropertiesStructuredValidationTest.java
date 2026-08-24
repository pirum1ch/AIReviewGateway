package com.review.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structured Review Output (architecture §8, five-point startup validation; threat model SOR-13,
 * CRITICAL): {@code validateOnStartup()} is package-private and invoked directly here, same pattern as
 * {@code GatewayPropertiesRetryAndBackendHealthValidationTest}.
 */
class GatewayPropertiesStructuredValidationTest {

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
        assertThatCode(() -> validProperties().validateOnStartup()).doesNotThrowAnyException();
    }

    @Test
    void maxFilesPerChunkMustBeAtLeastOne() {
        GatewayProperties properties = validProperties();
        properties.getStructured().setMaxFilesPerChunk(0);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-files-per-chunk");
    }

    @Test
    void maxPathCharsMustBeAtMost300() {
        GatewayProperties properties = validProperties();
        properties.getStructured().setMaxPathChars(301);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-path-chars");
    }

    @Test
    void maxPathCharsMustBeAtLeastOne() {
        GatewayProperties properties = validProperties();
        properties.getStructured().setMaxPathChars(0);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-path-chars");
    }

    @Test
    void maxPathsPerSectionMustBeAtLeastMaxFilesPerChunk() {
        GatewayProperties properties = validProperties();
        properties.getStructured().setMaxFilesPerChunk(100);
        properties.getDiff().setMaxPathsPerSection(64); // default, now smaller than max-files-per-chunk

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-paths-per-section")
                .hasMessageContaining("max-files-per-chunk");
    }

    @Test
    void structuredAnswerReserveMustBeAtLeastDiffAnswerReserve() {
        GatewayProperties properties = validProperties();
        properties.getStructured().setAnswerReserve(1000); // below the default diff.answer-reserve (4000)

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("structured.answer-reserve")
                .hasMessageContaining("diff.answer-reserve");
    }

    @Test
    void coverageBlockMustFitTheRemainingBudget() {
        GatewayProperties properties = validProperties();
        // A much larger coverage block than the default context window can accommodate.
        properties.getStructured().setMaxFilesPerChunk(4000);
        properties.getDiff().setMaxPathsPerSection(4000);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-diff-budget-tokens");
    }

    /**
     * Threat model SOR-13 (CRITICAL): the check as originally drafted omitted the Prompt Manager term,
     * which would have let every v3 Review in a REPO-mode deployment fail at RUNTIME with
     * {@code 422 PROMPT_TOO_LARGE} instead of the Gateway refusing to start with a named-property message.
     */
    @Test
    void promptManagerTermIsIncludedWhenPromptManagerIsEnabled() {
        GatewayProperties properties = validProperties();
        properties.getPrompt().setEnabled(true);
        properties.getGitlab().setPromptToken("e".repeat(32));
        properties.getPrompt().getCorporate().setProject("group/project");
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(6000);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-system-prompt-tokens");
    }

    @Test
    void promptManagerTermIsExcludedWhenPromptManagerIsDisabled() {
        // Same numeric shape as the failing case above, but with Prompt Manager off -- must pass.
        GatewayProperties properties = validProperties();
        properties.getPrompt().setEnabled(false);

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }
}
