package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.JobPayload} field-for-field. */
public record JobPayload(String diff, String promptVersion) {

    /**
     * FW-05/WSR-10 hardening: the default record {@code toString()} would dump the full (proprietary)
     * diff into any accidental {@code log.debug("{}", job)}/exception-message rendering. This does not
     * affect JSON (de)serialization, which Jackson performs via the accessors/canonical constructor, not
     * {@code toString()}.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        return "JobPayload[diff=<masked, " + diffChars + " chars>, promptVersion=" + promptVersion + "]";
    }
}
