package com.review.gateway.model.enums;

/**
 * Closed set of Worker-reported failure classifications for {@code POST /jobs/{id}/fail} (architecture
 * §5.3, WOC-22/WOC-23). Audit-only — {@code RetryManager}'s requeue-vs-fail decision never depends on
 * this value (WOC-24: "retry logic lives only in the Gateway" stays true even though the Worker now
 * speaks about failures).
 *
 * <p><b>WOC-23 (untrusted input):</b> the wire value is parsed via {@link #fromWireValue}, never
 * {@code Enum.valueOf} on caller-supplied text — an unknown/malformed value maps to {@link #UNKNOWN} with
 * a WARN, never a {@code 400}. This mirrors {@code Backend.promptMessageFormat}'s forward-compatibility
 * precedent: an independently-deployed Worker fleet may send a reason code this Gateway build does not
 * yet recognize, and that must degrade safely rather than reject the report.
 */
public enum JobFailureReason {
    LLM_EMPTY_RESPONSE,
    LLM_ERROR,
    LLM_TIMEOUT,
    LLM_RESPONSE_TOO_LARGE,
    PROMPT_INVALID,
    WORKER_ERROR,
    /** Structured Review Output (SRO-13): the Worker's own defensive decoder-constraint re-check failed. */
    CONSTRAINT_INVALID,
    /** Not a wire value — the safe fallback for anything unrecognized. */
    UNKNOWN;

    /**
     * @return the matching constant (excluding {@link #UNKNOWN} itself, which is never a valid wire
     *         value), or {@link #UNKNOWN} for anything else — never throws.
     */
    public static JobFailureReason fromWireValue(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        for (JobFailureReason candidate : values()) {
            if (candidate != UNKNOWN && candidate.name().equals(raw)) {
                return candidate;
            }
        }
        return UNKNOWN;
    }
}
