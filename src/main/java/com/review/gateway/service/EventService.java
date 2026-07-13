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
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    /** Defense-in-depth cap: `details` is free text, never a payload; this is generous for a short note. */
    private static final int MAX_DETAILS_LENGTH = 500;

    private static final String REDACTED = "[REDACTED]";

    /** Matches "Bearer <token>" and "key: value"/"key=value" shapes for common secret-ish key names. */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+|(?:token|password|secret|apikey|api_key)\\s*[:=]\\s*[\"']?)([^\\s\"',;]+)");

    private final ReviewEventRepository reviewEventRepository;

    public EventService(ReviewEventRepository reviewEventRepository) {
        this.reviewEventRepository = reviewEventRepository;
    }

    /**
     * Appends one audit event. Must be called within an active transaction (participates in the
     * caller's transaction boundary; does not open its own).
     */
    @Transactional
    public ReviewEvent record(Long reviewId, EventType eventType, String workerId, Long backendId, String details) {
        String scrubbed = scrub(details);
        ReviewEvent event = new ReviewEvent(reviewId, eventType, workerId, backendId, scrubbed);
        ReviewEvent saved = reviewEventRepository.save(event);
        log.debug("review_events: reviewId={} type={} workerId={} backendId={} details={}",
                reviewId, eventType, workerId, backendId, scrubbed);
        return saved;
    }

    private String scrub(String details) {
        if (details == null) {
            return null;
        }
        String masked = SECRET_PATTERN.matcher(details).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + REDACTED));
        if (masked.length() > MAX_DETAILS_LENGTH) {
            masked = masked.substring(0, MAX_DETAILS_LENGTH) + "...(truncated)";
        }
        return masked;
    }
}
