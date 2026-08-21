package com.review.gateway.model.enums;

/**
 * Recorded on {@link com.review.gateway.model.Review} at create time (PMR-09): {@code NONE} means the
 * Review was created with {@code gateway.prompt.enabled=false} (today's legacy behavior — the Worker
 * falls back to its own template {@code system:} block, {@code systemMessages=null}); {@code REPO}
 * means repo-sourced sections were resolved and persisted for this Review, so claim-time assembly must
 * find at least the mandatory {@code CORPORATE_*} rows or fail the job loudly (never silently degrade
 * to an empty/partial system prompt).
 */
public enum PromptBundleMode {
    NONE,
    REPO
}
