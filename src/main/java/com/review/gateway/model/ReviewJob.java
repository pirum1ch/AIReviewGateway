package com.review.gateway.model;

import com.review.gateway.model.enums.JobStatus;
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
 * Execution record for one chunk of a {@link Review} → {@code review_jobs} (V2, diff chunking:
 * 1:N per review, one row per {@link ReviewChunk}). <b>The queue owner</b> as of V2 — {@code status}/
 * {@code priority}/{@code attempts} live here now, not on {@code reviews}; {@code reviews.status} is
 * instead derived from the set of a review's job statuses by {@code ChunkCoordinator} and applied
 * through the existing {@code StateMachine}. Every transition of {@link #status} happens only through
 * {@code JobStateMachine}.
 */
@Entity
@Table(name = "review_jobs")
public class ReviewJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Column(name = "chunk_id", updatable = false)
    private Long chunkId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private Integer chunkIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private JobStatus status;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "backend_id")
    private Long backendId;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_error")
    private String lastError;

    /**
     * Worker Observability & Claim Latency (V4, WOC-40/WOR-14): earliest instant this job may be
     * claimed again, set on the requeue branch of {@code RetryManager.requeueOrFail} to
     * {@code now + gateway.retry.requeue-delay} (computed against the <b>database</b> clock, never the
     * JVM's). {@code NULL} means claimable immediately (today's behavior; also the state after a
     * fresh claim, which always nulls this out). {@code ReviewJobRepository.findNextQueuedJobIdForUpdate}
     * filters on it.
     */
    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected ReviewJob() {
    }

    /**
     * Convenience constructor for the (still-common, single-chunk) case: {@code chunkId=null},
     * {@code chunkIndex=0}, default {@code priority=10}, {@code attempts=0}, {@code status=QUEUED}.
     * Callers that create multiple chunks per review should use the full constructor instead.
     */
    public ReviewJob(Long reviewId, Long backendId, String workerId) {
        this(reviewId, null, 0, 10, workerId, backendId);
    }

    public ReviewJob(Long reviewId, Long chunkId, Integer chunkIndex, Integer priority, String workerId, Long backendId) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex != null ? chunkIndex : 0;
        this.status = JobStatus.QUEUED;
        this.priority = priority != null ? priority : 10;
        this.attempts = 0;
        this.backendId = backendId;
        this.workerId = workerId;
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

    public Long getReviewId() {
        return reviewId;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
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

    public Long getBackendId() {
        return backendId;
    }

    public void setBackendId(Long backendId) {
        this.backendId = backendId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(Instant heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public void setNotBefore(Instant notBefore) {
        this.notBefore = notBefore;
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
        if (!(o instanceof ReviewJob other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
