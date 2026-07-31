package com.review.gateway.service;

import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The sole place {@link ReviewJob#getStatus()} transitions are validated and applied (V2, diff
 * chunking). Mirrors {@link StateMachine} exactly, one level down: {@code QUEUED -> RUNNING |
 * CANCELLED | OBSOLETE}; {@code RUNNING -> COMPLETED | FAILED | QUEUED (retry) | CANCELLED |
 * OBSOLETE}; everything else terminal. Every successful transition writes exactly one
 * {@code review_events} row (attributed to this job's {@code chunk_index}/{@code job_id} — audit/debug
 * columns only).
 *
 * <p>Not annotated {@code @Transactional} itself, same rationale as {@link StateMachine}: it mutates a
 * JPA-managed entity and delegates the event write to {@link EventService}, so it must always be
 * invoked from within an already-active transaction owned by the caller.
 */
@Service
public class JobStateMachine {

    private static final Logger log = LoggerFactory.getLogger(JobStateMachine.class);

    private static final Map<JobStatus, Set<JobStatus>> LEGAL_TRANSITIONS = buildTransitionTable();

    private final EventService eventService;

    public JobStateMachine(EventService eventService) {
        this.eventService = eventService;
    }

    private static Map<JobStatus, Set<JobStatus>> buildTransitionTable() {
        Map<JobStatus, Set<JobStatus>> table = new EnumMap<>(JobStatus.class);
        table.put(JobStatus.QUEUED, EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED, JobStatus.OBSOLETE));
        table.put(JobStatus.RUNNING, EnumSet.of(
                JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.QUEUED, JobStatus.CANCELLED, JobStatus.OBSOLETE));
        table.put(JobStatus.COMPLETED, EnumSet.noneOf(JobStatus.class));
        table.put(JobStatus.FAILED, EnumSet.noneOf(JobStatus.class));
        table.put(JobStatus.CANCELLED, EnumSet.noneOf(JobStatus.class));
        table.put(JobStatus.OBSOLETE, EnumSet.noneOf(JobStatus.class));
        return table;
    }

    /**
     * Validates and applies {@code job.status -> to}, then writes one {@code review_events} row of
     * type {@code eventType}, attributed to this job's chunk/job id.
     *
     * @throws InvalidStateTransitionException if the transition is not legal
     */
    public void transition(ReviewJob job, JobStatus to, EventType eventType, String workerId, Long backendId, String details) {
        JobStatus from = job.getStatus();
        if (!isLegal(from, to)) {
            throw new InvalidStateTransitionException("Job status " + from + " -> " + to + " is not a legal transition");
        }
        job.setStatus(to);
        eventService.record(job.getReviewId(), eventType, workerId, backendId, job.getChunkIndex(), job.getId(), details);
        log.info("Job {} (reviewId={}, chunkIndex={}) transitioned {} -> {} (event={})",
                job.getId(), job.getReviewId(), job.getChunkIndex(), from, to, eventType);
    }

    public boolean isLegal(JobStatus from, JobStatus to) {
        Set<JobStatus> allowed = LEGAL_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
