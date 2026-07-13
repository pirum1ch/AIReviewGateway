package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fail-fast diff-size guard (req. 1.1/2.1, SR-11 relates to the byte cap at the container edge —
 * this class enforces the token-budget cap on the content itself). Token count is estimated with a
 * {@code chars-per-token} heuristic rather than a real tokenizer (documented trade-off, architecture
 * §12); swapping in a real tokenizer later only touches this class.
 */
@Service
public class DiffSizeValidator {

    private static final Logger log = LoggerFactory.getLogger(DiffSizeValidator.class);

    private final GatewayProperties properties;

    public DiffSizeValidator(GatewayProperties properties) {
        this.properties = properties;
    }

    /** Heuristic token estimate: {@code ceil(chars / charsPerToken)}. */
    public int estimateTokens(String diff) {
        if (diff == null || diff.isEmpty()) {
            return 0;
        }
        int charsPerToken = Math.max(1, properties.getDiff().getCharsPerToken());
        return (int) Math.ceil(diff.length() / (double) charsPerToken);
    }

    /**
     * The enforced budget: the explicit configured cap, further bounded by whatever the context
     * window leaves after reserving room for the prompt scaffolding and the model's answer. Taking
     * the minimum of the two guards against the two settings drifting out of sync.
     */
    public int budgetTokens() {
        GatewayProperties.Diff cfg = properties.getDiff();
        int derived = cfg.getContextWindow() - cfg.getPromptReserve() - cfg.getAnswerReserve();
        return Math.min(cfg.getMaxDiffTokens(), Math.max(0, derived));
    }

    /**
     * @throws DiffTooLargeException if the diff's estimated token count exceeds {@link #budgetTokens()}
     */
    public void validate(String diff) {
        int estimated = estimateTokens(diff);
        int budget = budgetTokens();
        if (estimated > budget) {
            log.info("Rejecting diff: estimated {} tokens exceeds budget of {}", estimated, budget);
            throw new DiffTooLargeException(
                    "Diff too large: estimated " + estimated + " tokens exceeds budget of " + budget + " tokens");
        }
    }
}
