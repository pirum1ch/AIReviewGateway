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
 * Raw model response + metrics for a {@link Review} → {@code review_results}. The raw response is
 * stored mandatorily, before any parsing is attempted (req. 1.9). {@code review_id} is
 * {@code UNIQUE}, which is what makes result submission idempotent at the repository layer
 * ({@code INSERT ... ON CONFLICT (review_id) DO NOTHING}). Append-only: no column besides
 * {@code id} is ever updated after insert.
 */
@Entity
@Table(name = "review_results")
public class ReviewResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, unique = true, updatable = false)
    private Long reviewId;

    @Column(name = "raw_response", nullable = false, updatable = false)
    private String rawResponse;

    @Column(name = "summary", updatable = false)
    private String summary;

    @Column(name = "prompt_tokens", updatable = false)
    private Integer promptTokens;

    @Column(name = "completion_tokens", updatable = false)
    private Integer completionTokens;

    @Column(name = "total_tokens", updatable = false)
    private Integer totalTokens;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    @Column(name = "model", updatable = false, length = 128)
    private String model;

    @Column(name = "backend_id", updatable = false)
    private Long backendId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewResult() {
    }

    public ReviewResult(Long reviewId, String rawResponse, String summary, Integer promptTokens,
                        Integer completionTokens, Integer totalTokens, Long durationMs,
                        String model, Long backendId) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.rawResponse = Objects.requireNonNull(rawResponse, "rawResponse");
        this.summary = summary;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.durationMs = durationMs;
        this.model = model;
        this.backendId = backendId;
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

    public String getRawResponse() {
        return rawResponse;
    }

    public String getSummary() {
        return summary;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getModel() {
        return model;
    }

    public Long getBackendId() {
        return backendId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewResult other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
