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
