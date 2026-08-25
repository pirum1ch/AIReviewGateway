package com.review.gateway.service.dto;

/**
 * Input to {@code QueueManager#submitResult} (architecture §11 {@code SubmitResultRequest}).
 */
public record SubmitResultCommand(
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model,
        String finishReason) {

    /**
     * F-DC-07: masked {@code toString()} — {@code rawResponse} is the full, untrusted raw LLM output
     * and must never be dumped whole into a log/exception-message rendering. Latent (no current call
     * site logs this record), fixed while applying the same CSR-14 pattern to the other content-
     * carrying DTOs in this area.
     *
     * <p><b>F-SRO-08(b):</b> {@code finishReason} is Worker-supplied and, before this fix, was rendered
     * raw here — a compromised/buggy Worker could put CR/LF into it (WOR-06/WOR-07 log-injection
     * concern) and it would reach any accidental {@code log.debug("{}", command)} as real newlines.
     * {@code TextSanitizer} is not available in this record (no DI), so this filters to a conservative
     * {@code [A-Za-z0-9_]} alphabet inline — sufficient since the only legitimate wire values
     * ({@code stop}/{@code length}/{@code content_filter}/{@code tool_calls}) are already within it.
     */
    @Override
    public String toString() {
        int rawResponseChars = rawResponse == null ? 0 : rawResponse.length();
        return "SubmitResultCommand[rawResponse=<masked, " + rawResponseChars + " chars>, promptTokens="
                + promptTokens + ", completionTokens=" + completionTokens + ", durationMs=" + durationMs
                + ", model=" + model + ", finishReason=" + maskFinishReason() + "]";
    }

    private String maskFinishReason() {
        if (finishReason == null) {
            return "null";
        }
        int cap = Math.min(finishReason.length(), 32);
        StringBuilder safe = new StringBuilder(cap);
        for (int i = 0; i < cap; i++) {
            char c = finishReason.charAt(i);
            safe.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' ? c : '?');
        }
        if (finishReason.length() > cap) {
            safe.append("...");
        }
        return safe.toString();
    }
}
