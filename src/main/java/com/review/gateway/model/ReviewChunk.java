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
 * One file-based slice of a {@link Review}'s diff → {@code review_chunks} (V2, diff chunking).
 * Immutable once written, exactly like {@link ReviewInput} (which stays the whole, unmodified diff —
 * {@code review_chunks} never replaces it, only adds the per-chunk slices used for dispatch).
 *
 * <p>{@code filePaths} is stored as a JSON array of already-SANITIZED paths (never the raw,
 * attacker-controlled value) — see {@code ChunkContextRenderer} (CSR-09/CSR-10). In the rare
 * no-{@code diff --git} fallback mode, {@code filePaths} is empty: path provenance is untrusted
 * there, so nothing is extracted at all (CSR-11).
 */
@Entity
@Table(name = "review_chunks")
public class ReviewChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_count", nullable = false, updatable = false)
    private Integer chunkCount;

    @Column(name = "diff", nullable = false, updatable = false)
    private String diff;

    @Column(name = "estimated_tokens", updatable = false)
    private Integer estimatedTokens;

    @Column(name = "file_count", updatable = false)
    private Integer fileCount;

    @Column(name = "file_paths", updatable = false)
    private String filePaths;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewChunk() {
    }

    public ReviewChunk(Long reviewId, Integer chunkIndex, Integer chunkCount, String diff,
                        Integer estimatedTokens, Integer fileCount, String filePaths) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.chunkIndex = Objects.requireNonNull(chunkIndex, "chunkIndex");
        this.chunkCount = Objects.requireNonNull(chunkCount, "chunkCount");
        this.diff = Objects.requireNonNull(diff, "diff");
        this.estimatedTokens = estimatedTokens;
        this.fileCount = fileCount;
        this.filePaths = filePaths;
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

    public Integer getChunkCount() {
        return chunkCount;
    }

    public String getDiff() {
        return diff;
    }

    public Integer getEstimatedTokens() {
        return estimatedTokens;
    }

    public Integer getFileCount() {
        return fileCount;
    }

    public String getFilePaths() {
        return filePaths;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewChunk other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
