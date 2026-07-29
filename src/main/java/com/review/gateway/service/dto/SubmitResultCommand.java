package com.review.gateway.service.dto;

/**
 * Input to {@code QueueManager#submitResult} (architecture §11 {@code SubmitResultRequest}).
 */
public record SubmitResultCommand(
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {

    /**
     * F-DC-07: masked {@code toString()} — {@code rawResponse} is the full, untrusted raw LLM output
     * and must never be dumped whole into a log/exception-message rendering. Latent (no current call
     * site logs this record), fixed while applying the same CSR-14 pattern to the other content-
     * carrying DTOs in this area.
     */
    @Override
    public String toString() {
        int rawResponseChars = rawResponse == null ? 0 : rawResponse.length();
        return "SubmitResultCommand[rawResponse=<masked, " + rawResponseChars + " chars>, promptTokens="
                + promptTokens + ", completionTokens=" + completionTokens + ", durationMs=" + durationMs
                + ", model=" + model + "]";
    }
}
