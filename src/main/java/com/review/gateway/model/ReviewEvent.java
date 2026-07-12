package com.review.gateway.model;

import com.review.gateway.model.enums.EventType;
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
 * A single audit-trail row for a {@link Review} → {@code review_events}. Fully append-only: no
 * column is ever updated after insert. {@code details} must never contain secrets or full diffs —
 * writers go through {@code EventService}'s scrubber (feature/02-core-services, SR-12).
 */
@Entity
@Table(name = "review_events")
public class ReviewEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private Long reviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 32)
    private EventType eventType;

    @Column(name = "worker_id", updatable = false, length = 64)
    private String workerId;

    @Column(name = "backend_id", updatable = false)
    private Long backendId;

    @Column(name = "details", updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ReviewEvent() {
    }

    public ReviewEvent(Long reviewId, EventType eventType, String workerId, Long backendId,
                       String details) {
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.workerId = workerId;
        this.backendId = backendId;
        this.details = details;
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

    public EventType getEventType() {
        return eventType;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Long getBackendId() {
        return backendId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReviewEvent other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
