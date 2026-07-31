package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.PromptTooLargeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class DiffSizeValidatorTest {

    private GatewayProperties propertiesWithBudget(int contextWindow, int promptReserve, int answerReserve,
                                                     int maxDiffTokens, int charsPerToken) {
        GatewayProperties properties = new GatewayProperties();
        properties.getDiff().setContextWindow(contextWindow);
        properties.getDiff().setPromptReserve(promptReserve);
        properties.getDiff().setAnswerReserve(answerReserve);
        properties.getDiff().setMaxDiffTokens(maxDiffTokens);
        properties.getDiff().setCharsPerToken(charsPerToken);
        return properties;
    }

    @Test
    void diffWithinBudgetPasses() {
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        String diff = "x".repeat(4 * 100); // 100 tokens, well within budget

        assertThatCode(() -> validator.validate(diff)).doesNotThrowAnyException();
    }

    @Test
    void diffExceedingMaxDiffTokensIsRejected() {
        // derived budget = 16384 - 2000 - 4000 = 10384, but maxDiffTokens=10000 is the tighter cap
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        String diff = "x".repeat(4 * 10001); // 10001 tokens > 10000 cap

        assertThatThrownBy(() -> validator.validate(diff)).isInstanceOf(DiffTooLargeException.class);
    }

    @Test
    void diffExceedingDerivedContextBudgetIsRejectedEvenBelowMaxDiffTokens() {
        // maxDiffTokens is generous (100000) but the context window only leaves room for 1000 tokens.
        GatewayProperties properties = propertiesWithBudget(2000, 500, 500, 100_000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        String diff = "x".repeat(4 * 1001); // 1001 tokens > derived 1000-token budget

        assertThatThrownBy(() -> validator.validate(diff)).isInstanceOf(DiffTooLargeException.class);
    }

    @Test
    void nullOrEmptyDiffEstimatesZeroTokens() {
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        assertThat(validator.estimateTokens(null)).isZero();
        assertThat(validator.estimateTokens("")).isZero();
    }

    @Test
    void exactlyAtBudgetIsAccepted() {
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);
        int budget = validator.budgetTokens();

        String diff = "x".repeat(4 * budget); // exactly at budget, not over

        assertThatCode(() -> validator.validate(diff)).doesNotThrowAnyException();
    }

    // ---- Prompt Manager (PMR-21): budgetTokens(int) / assertPromptFits ----

    @Test
    void budgetTokensWithZeroSystemPromptTokensMatchesTheLegacyNoArgOverload() {
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        assertThat(validator.budgetTokens(0)).isEqualTo(validator.budgetTokens());
    }

    @Test
    void budgetTokensShrinksByTheResolvedSystemPromptSize() {
        // maxDiffTokens set generously high so the context-window-derived term is always the binding
        // constraint, making the shrink-by-exactly-systemPromptTokens arithmetic exact.
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 100_000, 4);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        int noPrompt = validator.budgetTokens(0);
        int withPrompt = validator.budgetTokens(3000);

        assertThat(withPrompt).isEqualTo(noPrompt - 3000);
    }

    @Test
    void assertPromptFitsPassesWhenRemainingBudgetIsAboveTheConfiguredMinimum() {
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        properties.getPrompt().getLimits().setMinDiffBudgetTokens(1000);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        assertThatCode(() -> validator.assertPromptFits(2000)).doesNotThrowAnyException();
    }

    @Test
    void assertPromptFitsThrowsPromptTooLargeExceptionDistinctFromDiffTooLargeException() {
        // 16384 - 2000(promptReserve) - answerReserve(4000) = 10384 headroom; a 10000-token system
        // prompt leaves only 384 tokens for diff, below the configured 1000-token minimum.
        GatewayProperties properties = propertiesWithBudget(16384, 2000, 4000, 10000, 4);
        properties.getPrompt().getLimits().setMinDiffBudgetTokens(1000);
        DiffSizeValidator validator = new DiffSizeValidator(properties);

        assertThatThrownBy(() -> validator.assertPromptFits(10000))
                .isInstanceOf(PromptTooLargeException.class)
                .isNotInstanceOf(DiffTooLargeException.class);
    }

    @Test
    void aPreviouslyAcceptedMaxDiffNowOverflowsWhenASixThousandTokenSystemPromptIsAdded() {
        // PMR-21's own test case: a 6000-token system prompt plus a previously-accepted max diff is now
        // rejected/re-chunked rather than overflowing the context. Tight context window so a 6000-token
        // system prompt pushes the remaining diff budget below the configured minimum.
        GatewayProperties properties = propertiesWithBudget(12000, 2000, 4000, 10000, 4);
        properties.getPrompt().getLimits().setMinDiffBudgetTokens(1000);
        DiffSizeValidator validator = new DiffSizeValidator(properties);
        int maxDiffBudgetWithoutPrompt = validator.budgetTokens(0); // 6000 (context-derived, below maxDiffTokens)

        assertThatThrownBy(() -> validator.assertPromptFits(6000)).isInstanceOf(PromptTooLargeException.class);
        // The diff budget itself also shrinks well below what a "previously accepted max diff" needed.
        assertThat(validator.budgetTokens(6000)).isLessThan(maxDiffBudgetWithoutPrompt);
    }
}
