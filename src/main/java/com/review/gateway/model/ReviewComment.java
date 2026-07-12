package com.review.gateway.model;

import com.review.gateway.model.enums.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * A single parsed comment from an LLM review response → {@code review_comments}. The parsed
 * content ({@code filePath}, {@code lineNumber}, {@code severity}, {@code comment}) is immutable
 * once written; only {@code discussionId}/{@code publishedAt} are mutated, exactly once, by
 * {@code GitLabPublisher} when the comment is successfully posted to GitLab (idempotent publish,
 * req. 1.10 — publisher only ever touches rows where {@code publishedAt IS NULL}).
 */
@Entity
@Table(name = "review_comments")
public class ReviewComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "file_path", updatable = false, length = 1024)
    private String filePath;

    @Column(name = "line_number", updatable = false)
    private Integer lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", updatable = false, length = 16)
    private Severity severity;

    @Column(name = "comment", nullable = false, updatable = false)
    private String comment;

    @Column(name = "discussion_id", length = 128)
    private String discussionId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewComment() {
    }

    public ReviewComment(Long reviewId, String filePath, Integer lineNumber, Severity severity,
                         String comment) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.severity = severity;
        this.comment = Objects.requireNonNull(comment, "comment");
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

    public String getFilePath() {
        return filePath;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getComment() {
        return comment;
    }

    public String getDiscussionId() {
        return discussionId;
    }

    public void setDiscussionId(String discussionId) {
        this.discussionId = discussionId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewComment other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
