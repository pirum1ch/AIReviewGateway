package com.review.gateway.model.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * How claim-time assembly renders persisted {@code review_prompt_sections} rows into
 * {@code JobPayload.systemMessages} (architecture §8, PMR-22): {@code MULTI} — the default — emits one
 * {@code ChatMessage} per section (section boundaries stay structural, independent of the PMR-02
 * delimiter); {@code SINGLE} concatenates every section into one message using
 * {@code gateway.prompt.section-separator}.
 *
 * <p>{@link #fromNullable(String)} is the only way code in this project is allowed to turn a raw
 * {@code backends.prompt_message_format} DB value into this enum — deliberately never
 * {@link #valueOf(String)} directly, which throws on anything unexpected. The DB {@code CHECK}
 * constraint already restricts the column to {@code MULTI}/{@code SINGLE}/{@code NULL}, but claim-time
 * code must stay defensive regardless (a future migration, manual DB edit, or constraint drift must
 * degrade to the configured global default with a {@code WARN}, never throw and take the claim path
 * down — PMR-22).
 */
public enum PromptMessageFormat {
    MULTI,
    SINGLE;

    /** @return the parsed value, or {@link Optional#empty()} for {@code null}/blank/unrecognized input — never throws. */
    public static Optional<PromptMessageFormat> fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (PromptMessageFormat value : values()) {
            if (value.name().equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
