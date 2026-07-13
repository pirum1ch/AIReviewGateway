package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
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
}
