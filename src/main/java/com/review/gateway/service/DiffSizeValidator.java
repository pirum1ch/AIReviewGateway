package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.PromptTooLargeException;
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
     *
     * <p>Retained (systemPromptTokens=0) for callers that predate Prompt Manager, and for the
     * deliberately permissive CSR-01 pre-filter ({@link #rejectIfAbsurdlyLarge}), which must stay
     * IO-free and run before any GitLab call could ever produce a real system-prompt size.
     */
    public int budgetTokens() {
        return budgetTokens(0);
    }

    /**
     * Prompt Manager (architecture §9, PMR-21): the diff budget shrinks by the actual resolved
     * system-prompt size for <em>this</em> Review — {@code gateway.diff.prompt-reserve} now means only
     * the {@code user}-template's fixed wrapper text, not "the whole system prompt" (that was the
     * pre-Prompt-Manager meaning). Delegates to {@link #budgetTokens(int, int)} with {@code
     * gateway.diff.answer-reserve} — v1/v2 arithmetic is byte-identical to before F-SRO-03 (T-6.1).
     */
    public int budgetTokens(int systemPromptTokens) {
        return budgetTokens(systemPromptTokens, properties.getDiff().getAnswerReserve());
    }

    /**
     * F-SRO-03 (appsec SAST fix round): explicit-reserve overload. {@code answerReserveTokens} is
     * threaded explicitly by the caller rather than always reading {@code gateway.diff.answer-reserve}
     * internally, so this same arithmetic can size the startup budget check (5-point {@code
     * GatewayProperties.validateStructuredOnStartup}) exactly — before this overload existed, that
     * startup assertion checked a budget the runtime code never actually used. Every caller currently
     * passes {@code gateway.diff.answer-reserve} regardless of prompt version
     * (chore/answer-reserve-consolidation merged the formerly-separate {@code
     * gateway.structured.answer-reserve} into it); the parameter stays explicit rather than reading the
     * property internally so this method has no config dependency of its own.
     */
    public int budgetTokens(int systemPromptTokens, int answerReserveTokens) {
        GatewayProperties.Diff cfg = properties.getDiff();
        int derived = cfg.getContextWindow() - cfg.getPromptReserve() - Math.max(0, systemPromptTokens)
                - answerReserveTokens;
        return Math.min(cfg.getMaxDiffTokens(), Math.max(0, derived));
    }

    /**
     * PMR-21: fails with {@code PROMPT_TOO_LARGE} (422) — distinct from {@code DIFF_TOO_LARGE} — when
     * the diff budget remaining after subtracting {@code systemPromptTokens} falls below
     * {@code gateway.prompt.limits.min-diff-budget-tokens}. Called after {@code PromptManager.resolve}
     * and before {@code DiffChunker.split}, so an oversized resolved system prompt is reported with its
     * own diagnostic rather than silently shrinking every chunk or overflowing the model context.
     */
    public void assertPromptFits(int systemPromptTokens) {
        assertPromptFits(systemPromptTokens, properties.getDiff().getAnswerReserve());
    }

    /**
     * F-SRO-03: explicit-reserve overload — see {@link #budgetTokens(int, int)}. Called by
     * {@code ReviewService} with {@code gateway.diff.answer-reserve}, so the same reserve that sizes
     * {@code DiffChunker.split}'s runtime chunking also gates this pre-chunking budget check.
     */
    public void assertPromptFits(int systemPromptTokens, int answerReserveTokens) {
        int minDiffBudgetTokens = properties.getPrompt().getLimits().getMinDiffBudgetTokens();
        int budget = budgetTokens(systemPromptTokens, answerReserveTokens);
        if (budget < minDiffBudgetTokens) {
            log.info("Rejecting review: system prompt of {} tokens leaves only {} tokens of diff budget, "
                    + "below the configured minimum of {}", systemPromptTokens, budget, minDiffBudgetTokens);
            throw new PromptTooLargeException("Resolved system prompt (" + systemPromptTokens
                    + " tokens) leaves less than the configured minimum diff budget of " + minDiffBudgetTokens
                    + " tokens");
        }
    }

    /**
     * @throws DiffTooLargeException if the diff's estimated token count exceeds {@link #budgetTokens()}
     *         (the single-chunk budget). Retained for direct unit coverage of the underlying budget
     *         math; {@code ReviewService}'s request path no longer calls this directly (a diff over
     *         this budget is now chunked, not rejected outright) — see {@link #rejectIfAbsurdlyLarge}.
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

    /**
     * CSR-01: cheap, pre-chunking fail-fast guard. Diffs within this (much larger) absolute ceiling are
     * candidates for {@code DiffChunker} to split; anything beyond it is rejected with the existing
     * {@code 422 DIFF_TOO_LARGE} before {@code DiffChunker.split} ever runs, so an absurdly large
     * request can't be fully line-scanned first. The ceiling is
     * {@code max-chunks * (budgetTokens() - chunk-header-reserve-tokens)} — the maximum number of
     * tokens {@code DiffChunker} could ever successfully pack into {@code max-chunks} chunks.
     *
     * @throws DiffTooLargeException if the diff's estimated token count exceeds that ceiling
     */
    public void rejectIfAbsurdlyLarge(String diff) {
        int estimated = estimateTokens(diff);
        long ceiling = absurdlyLargeCeilingTokens();
        if (estimated > ceiling) {
            log.info("Rejecting diff before chunking: estimated {} tokens exceeds the absolute max of {} tokens "
                    + "(max-chunks={})", estimated, ceiling, properties.getDiff().getMaxChunks());
            throw new DiffTooLargeException("Diff too large: estimated " + estimated
                    + " tokens exceeds the absolute max of " + ceiling + " tokens (max-chunks="
                    + properties.getDiff().getMaxChunks() + ")");
        }
    }

    /** @return {@code max-chunks * (budgetTokens() - chunk-header-reserve-tokens)}, floored at {@code max-chunks}. */
    public long absurdlyLargeCeilingTokens() {
        GatewayProperties.Diff cfg = properties.getDiff();
        int perChunkBudget = Math.max(1, budgetTokens() - cfg.getChunkHeaderReserveTokens());
        return (long) Math.max(1, cfg.getMaxChunks()) * perChunkBudget;
    }
}
