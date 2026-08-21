package com.review.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable input payload for a {@link Review} → {@code review_inputs}. Enables re-running a
 * Review without hitting GitLab again (req. 1.2/4.3). Append-only: every column besides {@code id}
 * is {@code updatable = false} — once written, a row is never mutated.
 */
@Entity
@Table(name = "review_inputs")
public class ReviewInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, unique = true, updatable = false)
    private Long reviewId;

    @Column(name = "diff", nullable = false, updatable = false)
    private String diff;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 32)
    private String promptVersion;

    @Column(name = "head_sha", nullable = false, updatable = false, length = 64)
    private String headSha;

    @Column(name = "base_sha", nullable = false, updatable = false, length = 64)
    private String baseSha;

    @Column(name = "estimated_tokens", updatable = false)
    private Integer estimatedTokens;

    /**
     * Prompt Manager (V3): the estimated token size of the assembled system prompt used for this
     * Review, or {@code null} for a {@link com.review.gateway.model.enums.PromptBundleMode#NONE}
     * Review (kill-switch off — no repo-sourced sections were resolved).
     */
    @Column(name = "system_prompt_tokens", updatable = false)
    private Integer systemPromptTokens;

    /**
     * PMR-09/PMR-24 related: {@code true} when project-section resolution failed and
     * {@code gateway.prompt.error-handling.on-error=SKIP_OPTIONAL} let the Review proceed without its
     * optional {@code PROJECT_*} sections (corporate sections are always mandatory and never produce a
     * degraded Review — a corporate failure always fails {@code POST /reviews} outright).
     */
    @Column(name = "prompt_degraded", nullable = false, updatable = false)
    private boolean promptDegraded;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewInput() {
    }

    public ReviewInput(Long reviewId, String diff, String promptVersion, String headSha,
                       String baseSha, Integer estimatedTokens) {
        this(reviewId, diff, promptVersion, headSha, baseSha, estimatedTokens, null, false);
    }

    /** Prompt Manager (V3) constructor: additionally records the system-prompt token size and degraded flag. */
    public ReviewInput(Long reviewId, String diff, String promptVersion, String headSha, String baseSha,
                        Integer estimatedTokens, Integer systemPromptTokens, boolean promptDegraded) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.diff = Objects.requireNonNull(diff, "diff");
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        this.headSha = Objects.requireNonNull(headSha, "headSha");
        this.baseSha = Objects.requireNonNull(baseSha, "baseSha");
        this.estimatedTokens = estimatedTokens;
        this.systemPromptTokens = systemPromptTokens;
        this.promptDegraded = promptDegraded;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public String getDiff() {
        return diff;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public Integer getEstimatedTokens() {
        return estimatedTokens;
    }

    public Integer getSystemPromptTokens() {
        return systemPromptTokens;
    }

    public boolean isPromptDegraded() {
        return promptDegraded;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewInput other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
