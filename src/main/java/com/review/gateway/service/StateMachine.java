package com.review.gateway.service;

import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The sole place {@link Review#getStatus()} transitions are validated and applied, per architecture
 * §4. Every legal transition is enumerated in {@link #LEGAL_TRANSITIONS}; anything else throws
 * {@link InvalidStateTransitionException}. Every successful transition writes exactly one
 * {@code review_events} row via {@link EventService}.
 *
 * <p>Not annotated {@code @Transactional} itself: it mutates a JPA-managed entity and delegates the
 * event write to {@link EventService} (itself {@code @Transactional}), so it must always be invoked
 * from within an already-active transaction owned by the caller (e.g. {@code ReviewService},
 * {@code QueueManager}). The {@code Review} passed in must be a managed entity in that transaction's
 * persistence context (or explicitly saved by the caller afterward) — this class does not call
 * {@code save()} itself, matching the "aggregate is the source of truth, dirty-checking commits it"
 * pattern used elsewhere in this codebase.
 *
 * <p>Note on row 8 of the transition table ({@code COMPLETED -> COMPLETED}, transient GitLab
 * failure during publish): that is not a state <em>change</em> and is deliberately NOT modeled here.
 * {@code GitLabPublisher} handles that case directly (it never calls {@link #transition}) because the
 * frozen {@code EventType} enum (feature/01-data-model, V1 schema) has no event type for "publish
 * attempt failed, still COMPLETED" — logging that case is left to the application logger.
 */
@Service
public class StateMachine {

    private static final Logger log = LoggerFactory.getLogger(StateMachine.class);

    private static final Map<ReviewStatus, Set<ReviewStatus>> LEGAL_TRANSITIONS = buildTransitionTable();

    private final EventService eventService;

    public StateMachine(EventService eventService) {
        this.eventService = eventService;
    }

    private static Map<ReviewStatus, Set<ReviewStatus>> buildTransitionTable() {
        Map<ReviewStatus, Set<ReviewStatus>> table = new EnumMap<>(ReviewStatus.class);
        table.put(ReviewStatus.NEW, EnumSet.of(ReviewStatus.QUEUED, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.QUEUED, EnumSet.of(ReviewStatus.RUNNING, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.RUNNING, EnumSet.of(
                ReviewStatus.COMPLETED, ReviewStatus.QUEUED, ReviewStatus.FAILED,
                ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        table.put(ReviewStatus.COMPLETED, EnumSet.of(ReviewStatus.PUBLISHED, ReviewStatus.OBSOLETE, ReviewStatus.CANCELLED));
        // Terminal states: PUBLISHED, FAILED, CANCELLED, OBSOLETE — no outgoing transitions.
        table.put(ReviewStatus.PUBLISHED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.FAILED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.CANCELLED, EnumSet.noneOf(ReviewStatus.class));
        table.put(ReviewStatus.OBSOLETE, EnumSet.noneOf(ReviewStatus.class));
        return table;
    }

    /**
     * Validates and applies {@code review.status -> to}, then writes one {@code review_events} row
     * of type {@code eventType}.
     *
     * @throws InvalidStateTransitionException if the transition is not in the architecture §4 table
     */
    public void transition(Review review, ReviewStatus to, EventType eventType, String workerId, Long backendId, String details) {
        ReviewStatus from = review.getStatus();
        if (!isLegal(from, to)) {
            throw new InvalidStateTransitionException(from, to);
        }
        review.setStatus(to);
        eventService.record(review.getId(), eventType, workerId, backendId, details);
        log.info("Review {} transitioned {} -> {} (event={})", review.getId(), from, to, eventType);
    }

    /** Convenience overload for transitions with no worker/backend attribution. */
    public void transition(Review review, ReviewStatus to, EventType eventType, String details) {
        transition(review, to, eventType, null, null, details);
    }

    /** Returns whether {@code from -> to} is one of the transitions enumerated in architecture §4. */
    public boolean isLegal(ReviewStatus from, ReviewStatus to) {
        Set<ReviewStatus> allowed = LEGAL_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
