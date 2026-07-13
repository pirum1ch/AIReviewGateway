package com.review.gateway.controller;

import com.review.gateway.dto.ClaimJobRequest;
import com.review.gateway.dto.ClaimJobResponse;
import com.review.gateway.dto.HeartbeatRequest;
import com.review.gateway.dto.HeartbeatResponse;
import com.review.gateway.dto.JobPayload;
import com.review.gateway.dto.SubmitResultRequest;
import com.review.gateway.dto.SubmitResultResponse;
import com.review.gateway.service.QueueManager;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.HeartbeatOutcome;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.ResultOutcome;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Worker-facing queue endpoints (architecture §11, SR-04). Every operation here is keyed off a
 * self-declared {@code workerId} in the request body; ownership mismatches are rejected by
 * {@link QueueManager} before any mutation and surfaced here as an opaque 403 (never leaking the
 * target review's state — F02-05).
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private final QueueManager queueManager;

    public JobController(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    /**
     * Claims the next queued Review for the caller's backend (architecture §5). {@code 200} with the
     * claimed job, or {@code 204} when there is nothing to claim right now (empty queue, backend not
     * ACTIVE, or backend at capacity — all three are indistinguishable to the Worker by design).
     */
    @PostMapping("/claim")
    public ResponseEntity<ClaimJobResponse> claim(@Valid @RequestBody ClaimJobRequest request) {
        Optional<ClaimedJob> claimed = queueManager.claim(request.backendId(), request.workerId());
        return claimed
                .map(job -> ResponseEntity.ok(toResponse(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Worker liveness ping (req. 1.7). {@code 404} if the job is unknown, {@code 403} on an ownership
     * mismatch (SR-04); otherwise {@code 200} with {@code shouldContinue} telling the Worker whether to
     * keep generating.
     */
    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(@PathVariable("id") Long id, @Valid @RequestBody HeartbeatRequest request) {
        HeartbeatResult result = queueManager.heartbeat(id, request.workerId());
        if (result.outcome() == HeartbeatOutcome.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (result.outcome() == HeartbeatOutcome.OWNERSHIP_MISMATCH) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(new HeartbeatResponse(result.shouldContinue()));
    }

    /**
     * Result submission (req. 1.9, SR-04, SR-21). {@code 404}/{@code 403} same as heartbeat; otherwise
     * {@code 200} — idempotent whether this is the first delivery or a retried one (req. 1.9).
     */
    @PostMapping("/{id}/result")
    public ResponseEntity<SubmitResultResponse> submitResult(@PathVariable("id") Long id, @Valid @RequestBody SubmitResultRequest request) {
        SubmitResultCommand command = new SubmitResultCommand(
                request.rawResponse(), request.promptTokens(), request.completionTokens(), request.durationMs(), request.model());
        SubmitResultOutcome outcome = queueManager.submitResult(id, request.workerId(), command);
        if (outcome.outcome() == ResultOutcome.NOT_FOUND) {
            return ResponseEntity.notFound().build();
        }
        if (outcome.outcome() == ResultOutcome.OWNERSHIP_MISMATCH) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(new SubmitResultResponse(outcome.reviewId(), outcome.currentStatus().name()));
    }

    private ClaimJobResponse toResponse(ClaimedJob job) {
        return new ClaimJobResponse(job.jobId(), job.reviewId(), new JobPayload(job.diff(), job.promptVersion()));
    }
}
