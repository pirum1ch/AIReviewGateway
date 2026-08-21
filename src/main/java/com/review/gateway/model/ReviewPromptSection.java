package com.review.gateway.model;

import com.review.gateway.model.enums.PromptSectionKind;
import com.review.gateway.model.enums.PromptSectionStatus;
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
 * One resolved section of a Review's assembled system prompt → {@code review_prompt_sections}
 * (architecture §6). Immutable/append-only exactly like {@link ReviewInput}/{@link ReviewChunk}: every
 * column besides {@code id} is {@code updatable = false}, and PMR-07 requires the app DB user to hold
 * {@code INSERT}/{@code SELECT} only on this table (no {@code UPDATE}/{@code DELETE} grant — see
 * {@code DEPLOYMENT.md}).
 *
 * <p>{@code content} is empty for an {@link PromptSectionStatus#ABSENT} row (PMR-11: a positive record
 * that a configured/optional source was looked up and not found, never silence). {@code
 * content_sha256} is computed over the exact stored (post-sanitization) bytes (PMR-07), enabling the
 * assembled prompt to be reconstructed and verified from the DB alone.
 *
 * <p>ponytail: PMR-29 (SHOULD) — no retention/cleanup job nulls {@code content} for terminal Reviews
 * yet (would fold into the existing SR-22 retention policy, which this codebase has not itself
 * implemented as a scheduled job either — no precedent to extend). At ~4 rows × 30 Reviews/day this is
 * ~44k rows/year of provenance metadata plus duplicated repo content (architecture §11) — add the
 * purge job once at-rest volume or the SR-18 exposure window actually becomes a concern in practice,
 * not preemptively.
 */
@Entity
@Table(name = "review_prompt_sections")
public class ReviewPromptSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private Integer ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 32)
    private PromptSectionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 16)
    private PromptSectionStatus status;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "source_project", nullable = false, updatable = false, length = 256)
    private String sourceProject;

    @Column(name = "source_path", nullable = false, updatable = false, length = 512)
    private String sourcePath;

    @Column(name = "source_ref", nullable = false, updatable = false, length = 256)
    private String sourceRef;

    @Column(name = "source_commit", nullable = false, updatable = false, length = 64)
    private String sourceCommit;

    @Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
    private String contentSha256;

    @Column(name = "estimated_tokens", nullable = false, updatable = false)
    private Integer estimatedTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewPromptSection() {
    }

    public ReviewPromptSection(Long reviewId, Integer ordinal, PromptSectionKind kind, PromptSectionStatus status,
                                String content, String sourceProject, String sourcePath, String sourceRef,
                                String sourceCommit, String contentSha256, Integer estimatedTokens) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.ordinal = Objects.requireNonNull(ordinal, "ordinal");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.status = Objects.requireNonNull(status, "status");
        this.content = Objects.requireNonNull(content, "content");
        this.sourceProject = Objects.requireNonNull(sourceProject, "sourceProject");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.sourceCommit = Objects.requireNonNull(sourceCommit, "sourceCommit");
        this.contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        this.estimatedTokens = estimatedTokens != null ? estimatedTokens : 0;
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

    public Integer getOrdinal() {
        return ordinal;
    }

    public PromptSectionKind getKind() {
        return kind;
    }

    public PromptSectionStatus getStatus() {
        return status;
    }

    public String getContent() {
        return content;
    }

    public String getSourceProject() {
        return sourceProject;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getSourceCommit() {
        return sourceCommit;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public Integer getEstimatedTokens() {
        return estimatedTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewPromptSection other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * PMR-25: the default record/object {@code toString()} would otherwise be one accidental
     * {@code log.debug("{}", section)} away from dumping full (possibly proprietary, and in the
     * {@code PROJECT_*} case untrusted/attacker-supplied) section content. Only counts/hashes/metadata
     * are ever safe to render.
     */
    @Override
    public String toString() {
        int contentChars = content == null ? 0 : content.length();
        return "ReviewPromptSection[id=" + id + ", reviewId=" + reviewId + ", ordinal=" + ordinal + ", kind=" + kind
                + ", status=" + status + ", content=<masked, " + contentChars + " chars>, sourceProject="
                + sourceProject + ", sourcePath=" + sourcePath + ", sourceRef=" + sourceRef + ", sourceCommit="
                + sourceCommit + ", contentSha256=" + contentSha256 + ", estimatedTokens=" + estimatedTokens + "]";
    }
}
