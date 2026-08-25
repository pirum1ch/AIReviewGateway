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
 * Raw model response + metrics for one chunk of a {@link Review} → {@code review_results} (V2, diff
 * chunking: 1:N per review, one row per completed chunk). The raw response is stored mandatorily,
 * before any parsing is attempted (req. 1.9). {@code (review_id, chunk_index)} is {@code UNIQUE},
 * which is what makes result submission idempotent per chunk at the repository layer. {@code job_id}
 * (CSR-20) is always derived server-side from the locked {@code review_jobs} row being processed —
 * never taken from the Worker-supplied request body. Append-only: no column besides {@code id} is
 * ever updated after insert.
 */
@Entity
@Table(name = "review_results")
public class ReviewResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private Integer chunkIndex;

    @Column(name = "job_id", updatable = false)
    private Long jobId;

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

    /**
     * Structured Review Output (V5, SRO-42/43/44): llama-server's {@code finish_reason} for this
     * chunk's completion, whitelist-parsed and normalized on the Gateway ({@link
     * com.review.gateway.model.enums.FinishReason#fromWireValue}) before storage — never the raw wire
     * text verbatim. {@code NULL} means "not reported" (an old Worker, or a backend/llama-server build
     * that omits the field), preserved as {@code NULL} rather than coerced to {@code "unknown"}.
     */
    @Column(name = "finish_reason", updatable = false, length = 32)
    private String finishReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewResult() {
    }

    public ReviewResult(Long reviewId, Integer chunkIndex, Long jobId, String rawResponse, String summary,
                        Integer promptTokens, Integer completionTokens, Integer totalTokens, Long durationMs,
                        String model, Long backendId, String finishReason) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.chunkIndex = chunkIndex != null ? chunkIndex : 0;
        this.jobId = jobId;
        this.rawResponse = Objects.requireNonNull(rawResponse, "rawResponse");
        this.summary = summary;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.durationMs = durationMs;
        this.model = model;
        this.backendId = backendId;
        this.finishReason = finishReason;
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

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public Long getJobId() {
        return jobId;
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

    public String getFinishReason() {
        return finishReason;
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
