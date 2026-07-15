package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.SubmitResultRequest} field-for-field. */
public record ResultRequest(
        String workerId,
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {

    /**
     * FW-05/WSR-10 hardening: the default record {@code toString()} would dump the full raw LLM response
     * into any accidental {@code log.debug("{}", request)}/exception-message rendering. This does not
     * affect JSON serialization, which Jackson performs via the accessors/canonical constructor, not
     * {@code toString()}.
     */
    @Override
    public String toString() {
        int rawResponseChars = rawResponse == null ? 0 : rawResponse.length();
        return "ResultRequest[workerId=" + workerId + ", rawResponse=<masked, " + rawResponseChars + " chars>, "
                + "promptTokens=" + promptTokens + ", completionTokens=" + completionTokens
                + ", durationMs=" + durationMs + ", model=" + model + "]";
    }
}
