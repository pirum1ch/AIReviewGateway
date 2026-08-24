package com.review.gateway.model.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * Wire shape a llama-server build expects the decoder-level structured-output constraint in
 * (architecture §3.2, SRO-05/06) — a per-backend quirk, following the {@code
 * backends.prompt_message_format}/{@link PromptMessageFormat} precedent (PMR-22) byte-for-byte.
 *
 * <ul>
 *   <li>{@code OFF} — no constraint field is sent at all (today's request body, byte-identical).</li>
 *   <li>{@code RESPONSE_FORMAT_JSON_SCHEMA} — OpenAI-canonical {@code response_format:
 *       {"type":"json_schema","json_schema":{"name":"code_review","strict":true,"schema":&lt;S&gt;}}}.</li>
 *   <li>{@code RESPONSE_FORMAT_SCHEMA} — llama.cpp-native legacy {@code response_format:
 *       {"type":"json_object","schema":&lt;S&gt;}}.</li>
 *   <li>{@code TOP_LEVEL_JSON_SCHEMA} — llama.cpp-native top-level {@code json_schema: &lt;S&gt;}.</li>
 * </ul>
 *
 * <p>{@link #fromNullable(String)} is the only way code in this project is allowed to turn a raw
 * {@code backends.structured_output_mode} DB value (or the configured
 * {@code gateway.structured.default-mode}) into this enum — deliberately never {@link #valueOf(String)}
 * directly. The DB {@code CHECK} constraint already restricts the column, but claim-time code must stay
 * defensive regardless (PMR-22 precedent): a stale row / future relaxed constraint / manual DB edit
 * degrades to {@code OFF} with a {@code WARN}, never throws and takes the claim path down.
 */
public enum StructuredOutputMode {
    OFF,
    RESPONSE_FORMAT_JSON_SCHEMA,
    RESPONSE_FORMAT_SCHEMA,
    TOP_LEVEL_JSON_SCHEMA;

    /** @return the parsed value, or {@link Optional#empty()} for {@code null}/blank/unrecognized input — never throws. */
    public static Optional<StructuredOutputMode> fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (StructuredOutputMode value : values()) {
            if (value.name().equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
