package com.review.gateway.model;

import com.review.gateway.model.enums.BackendStatus;
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
 * Registry entry for a llama-server instance → {@code backends}. Load is derived from the count
 * of currently-{@code RUNNING} {@link ReviewJob}s referencing this backend, never a separate
 * counter (req. 1.6).
 */
@Entity
@Table(name = "backends")
public class Backend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, unique = true, updatable = false, length = 64)
    private String name;

    @Column(name = "url", nullable = false, length = 256)
    private String url;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BackendStatus status;

    @Column(name = "last_seen")
    private Instant lastSeen;

    /**
     * Worker Observability & Claim Latency (V4, WOC-10): restart-safe start-of-streak timestamp for a
     * continuous run of failed health probes; {@code NULL} means the backend is not currently failing.
     * Set on the first failed probe of a streak, cleared on any successful probe -- PostgreSQL stays the
     * single source of truth (no in-memory failure counter). {@code ACTIVE -> SUSPECT} requires this
     * streak to be at least {@code gateway.backend.failure-grace} old (WOC-11); {@code BackendDispatcher}
     * also declines a backend whose streak is past grace regardless of persisted status (WOR-10).
     */
    @Column(name = "probe_failed_since")
    private Instant probeFailedSince;

    /**
     * Prompt Manager (V3, PMR-22): per-backend override of {@code gateway.prompt.message-format}
     * ({@code MULTI}/{@code SINGLE}), or {@code null} to use the configured global default. Deliberately
     * a plain {@code String}, not {@code @Enumerated} bound directly to
     * {@link com.review.gateway.model.enums.PromptMessageFormat} — {@code PromptMessageFormatter} parses
     * it via {@code PromptMessageFormat.fromNullable}, never {@code Enum.valueOf}, so a value the DB
     * {@code CHECK} constraint didn't catch (a stale row from before the constraint existed, manual DB
     * edit, etc.) degrades to the global default with a {@code WARN} instead of throwing and taking the
     * claim path down.
     */
    @Column(name = "prompt_message_format", length = 16)
    private String promptMessageFormat;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Backend() {
    }

    public Backend(String name, String url, String model, Integer capacity) {
        this.name = Objects.requireNonNull(name, "name");
        this.url = Objects.requireNonNull(url, "url");
        this.model = Objects.requireNonNull(model, "model");
        this.capacity = capacity != null ? capacity : 1;
        this.status = BackendStatus.ACTIVE;
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

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = Objects.requireNonNull(url, "url");
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    public BackendStatus getStatus() {
        return status;
    }

    public void setStatus(BackendStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Instant getProbeFailedSince() {
        return probeFailedSince;
    }

    public void setProbeFailedSince(Instant probeFailedSince) {
        this.probeFailedSince = probeFailedSince;
    }

    public String getPromptMessageFormat() {
        return promptMessageFormat;
    }

    public void setPromptMessageFormat(String promptMessageFormat) {
        this.promptMessageFormat = promptMessageFormat;
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
        if (!(o instanceof Backend other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
