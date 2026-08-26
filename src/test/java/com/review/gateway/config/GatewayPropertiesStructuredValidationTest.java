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

    // structuredAnswerReserveMustBeAtLeastDiffAnswerReserve (Point 3) removed:
    // chore/answer-reserve-consolidation merged gateway.structured.answer-reserve into
    // gateway.diff.answer-reserve -- there is no longer a second value to compare against.

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
        // chore/answer-reserve-consolidation: the shipped default budget without Prompt Manager is now
        // 16384 - 2000 - 4000(diff.answer-reserve, shared with structured since the merge) - 2670
        // (coverageReserveTokens) = 7714 -- comfortably above min-diff-budget-tokens (1000), where it
        // used to be negative back when structured used its own, larger 8000 default. A
        // max-system-prompt-tokens value has to exceed 7714 - 1000 = 6714 to still exercise this test's
        // actual point: that the Prompt Manager term is included in the formula at all.
        GatewayProperties properties = validProperties();
        properties.getPrompt().setEnabled(true);
        properties.getGitlab().setPromptToken("e".repeat(32));
        properties.getPrompt().getCorporate().setProject("group/project");
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(8000);

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

    // ---- F-SRO-03: coverageReserveTokens is the ONE shared formula both this startup check and
    // DiffChunker.split's runtime chunk sizing must use -- pinning the arithmetic here guards against
    // the two drifting apart again.

    @Test
    void coverageReserveTokensMatchesTheDocumentedFormula() {
        // ceil((maxFilesPerChunk * (maxPathChars + 1) + 400) / charsPerToken)
        assertThatCode(() -> {
            long reserve = GatewayProperties.coverageReserveTokens(40, 256, 4);
            long expected = (long) Math.ceil((40.0 * (256 + 1) + 400) / 4);
            org.assertj.core.api.Assertions.assertThat(reserve).isEqualTo(expected);
        }).doesNotThrowAnyException();
    }

    @Test
    void coverageReserveTokensWithCharsPerTokenOfOneMatchesTheDefaultStructuredConfig() {
        // Shipped defaults: max-files-per-chunk=40, max-path-chars=256 (see application.yml).
        long reserve = GatewayProperties.coverageReserveTokens(40, 256, 1);

        org.assertj.core.api.Assertions.assertThat(reserve).isEqualTo(40L * 257 + 400);
    }

    @Test
    void coverageReserveTokensNeverDividesByZeroCharsPerToken() {
        assertThatCode(() -> GatewayProperties.coverageReserveTokens(40, 256, 0)).doesNotThrowAnyException();
    }
}
