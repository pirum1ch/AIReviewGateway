package com.review.gateway.service;

import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.repository.ReviewEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for appending {@code review_events} rows (SR-12/T-09). Callers must never pass
 * secrets, bearer tokens, full diffs, or raw LLM responses as {@code details} — that invariant is
 * enforced by convention at every call site (short, structured strings only, e.g.
 * {@code "attempt=2/3"}). This service additionally applies defense-in-depth scrubbing: any
 * token-shaped substring is redacted and the text is hard-capped in length, so an accidental leak by
 * a future caller cannot dump an unbounded secret or payload into the audit trail or its backups.
 *
 * <p><b>WOT-05/WOR-07 (Worker Observability & Claim Latency):</b> {@code POST /jobs/{id}/fail} is the
 * first caller whose {@code details} text is genuinely attacker-influenced (the sanitized, Worker
 * -supplied {@code detail} field, embedded by {@code RetryManager} — see its javadoc for the exact
 * grammar). {@link #scrub} therefore additionally strips Cc/Cf/Zl/Zp (delegating to {@link
 * TextSanitizer}, not a second implementation of the F-DC-02 lesson) <b>before</b> the existing
 * token-shape redaction and length cap — CSR-09 ordering (strip control/format characters first).
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** Defense-in-depth cap: `details` is free text, never a payload; this is generous for a short note. */
    private static final int MAX_DETAILS_LENGTH = 500;

    /**
     * Bound passed to {@link TextSanitizer#sanitizePath} for the Cc/Cf/Zl/Zp strip pass — deliberately
     * larger than {@link #MAX_DETAILS_LENGTH} so the strip pass never truncates text that the existing
     * cap below still needs to see in full for its own truncation-suffix accounting.
     */
    private static final int STRIP_PASS_MAX_LENGTH = 4096;

    private static final String REDACTED = "[REDACTED]";

    /** Matches "Bearer <token>" and "key: value"/"key=value" shapes for common secret-ish key names. */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:token|password|secret|apikey|api_key)\\s*[:=]\\s*[\"']?)([^\\s\"',;]+)");

    private final ReviewEventRepository reviewEventRepository;
    private final TextSanitizer textSanitizer;

    public EventService(ReviewEventRepository reviewEventRepository, TextSanitizer textSanitizer) {
        this.reviewEventRepository = reviewEventRepository;
        this.textSanitizer = textSanitizer;
    }

    /**
     * Appends one audit event. Must be called within an active transaction (participates in the
     * caller's transaction boundary; does not open its own).
     */
    @Transactional
    public ReviewEvent record(Long reviewId, EventType eventType, String workerId, Long backendId, String details) {
        return record(reviewId, eventType, workerId, backendId, null, null, details);
    }

    /**
     * V2 (diff chunking) overload: same as {@link #record(Long, EventType, String, Long, String)} but
     * additionally attributes the event to a specific chunk/job (audit/debug only — {@code
     * chunk_index}/{@code job_id} carry no constraint and are never used for dedup/business logic).
     */
    @Transactional
    public ReviewEvent record(Long reviewId, EventType eventType, String workerId, Long backendId,
                               Integer chunkIndex, Long jobId, String details) {
        String scrubbed = scrub(details);
        ReviewEvent event = new ReviewEvent(reviewId, eventType, workerId, backendId, chunkIndex, jobId, scrubbed);
        ReviewEvent saved = reviewEventRepository.save(event);
        log.debug("review_events: reviewId={} type={} workerId={} backendId={} chunkIndex={} jobId={} details={}",
                reviewId, eventType, workerId, backendId, chunkIndex, jobId, scrubbed);
        return saved;
    }

    private String scrub(String details) {
        if (details == null) {
            return null;
        }
        // WOR-07: Cc (incl. \r/\n/\t)/Cf (bidi overrides)/Zl/Zp first (CSR-09 ordering), before the
        // existing token-shape redaction and length cap -- this is the choke point that makes log/audit
        // injection via a Worker-supplied `detail` structurally impossible, not just discouraged by
        // caller convention.
        String stripped = textSanitizer.sanitizePath(details, STRIP_PASS_MAX_LENGTH);
        if (stripped == null) {
            return null;
        }
        String masked = SECRET_PATTERN.matcher(stripped).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + REDACTED));
        if (masked.length() > MAX_DETAILS_LENGTH) {
            masked = masked.substring(0, MAX_DETAILS_LENGTH) + "...(truncated)";
        }
        return masked;
    }
}
