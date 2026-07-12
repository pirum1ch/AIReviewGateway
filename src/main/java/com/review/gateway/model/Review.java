package com.review.gateway.model;

import com.review.gateway.model.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root and single source of truth for Review lifecycle state → {@code reviews}.
 *
 * <p>Holds the dedup key ({@code projectId}, {@code mergeRequestId}, {@code headSha}), the queue
 * ownership columns ({@code status}, {@code priority}), and {@code attempts}. All transitions of
 * {@link #status} happen only through {@code StateMachine} (feature/02-core-services); this class
 * intentionally exposes {@link #setStatus(ReviewStatus)} and friends as low-level mutators, not as
 * a public API for arbitrary callers.
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "merge_request_id", nullable = false, updatable = false)
    private Long mergeRequestId;

    @Column(name = "head_sha", nullable = false, updatable = false, length = 64)
    private String headSha;

    @Column(name = "base_sha", nullable = false, updatable = false, length = 64)
    private String baseSha;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 32)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Review() {
    }

    public Review(Long projectId, Long mergeRequestId, String headSha, String baseSha,
                  String promptVersion, Integer priority) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.mergeRequestId = Objects.requireNonNull(mergeRequestId, "mergeRequestId");
        this.headSha = Objects.requireNonNull(headSha, "headSha");
        this.baseSha = Objects.requireNonNull(baseSha, "baseSha");
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        this.priority = priority != null ? priority : 10;
        this.status = ReviewStatus.NEW;
        this.attempts = 0;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getMergeRequestId() {
        return mergeRequestId;
    }

    public String getHeadSha() {
        return headSha;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = Objects.requireNonNull(priority, "priority");
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    public void incrementAttempts() {
        this.attempts = this.attempts + 1;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Review other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
