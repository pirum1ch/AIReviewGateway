package com.review.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.dto.ParsedComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses an LLM's raw review response into structured, publish-safe comments (req. 1.9). Tries a
 * tolerant JSON-array extraction first ({@code [{"file":...,"line":...,"severity":...,"comment":...}]},
 * with a few common key aliases); if no valid array is found, the entire raw response becomes a
 * single {@code INFO}-severity comment. Every comment — JSON-derived or fallback — is put through the
 * same sanitation pipeline before being handed back (SR-08/SR-09/T-06):
 * <ol>
 *   <li>strip lines that are GitLab quick-actions (trimmed line starts with {@code /});</li>
 *   <li>cap length (excess is truncated with a marker);</li>
 *   <li>neutralize {@code @mentions} by inserting a zero-width space right after {@code @}, so
 *       GitLab does not resolve it as a mention trigger while it still reads as {@code @name} to a
 *       human;</li>
 *   <li>HTML-escape the result, so any injected {@code <script>}/markup renders inert.</li>
 * </ol>
 * The comment <em>count</em> is capped separately, after sanitation, dropping any excess.
 *
 * <p>This class does not throw for "the response wasn't well-formed JSON" — that is the expected,
 * common case (fallback mode). {@code ResultProcessor} reserves the FAILED transition for genuinely
 * unexpected exceptions raised out of {@link #parse}.
 */
@Service
public class CommentParser {

    private static final Logger log = LoggerFactory.getLogger(CommentParser.class);

    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w[\\w.-]*)");
    private static final String ZERO_WIDTH_SPACE = "​";
    private static final String TRUNCATION_SUFFIX = "... [truncated]";

    /** Matches {@code review_comments.file_path VARCHAR(1024)} (V1 migration) — F02-04/KD-2. */
    private static final int FILE_PATH_MAX_LENGTH = 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayProperties properties;

    public CommentParser(GatewayProperties properties) {
        this.properties = properties;
    }

    public List<ParsedComment> parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return List.of(new ParsedComment(null, null, Severity.INFO, "(model returned an empty response)"));
        }

        List<RawComment> jsonComments = tryParseJsonArray(rawResponse);
        List<RawComment> candidates = (jsonComments != null && !jsonComments.isEmpty())
                ? jsonComments
                : List.of(new RawComment(null, null, Severity.INFO, rawResponse));

        int maxCount = Math.max(0, properties.getPublish().getMaxCommentCount());
        List<ParsedComment> sanitized = new ArrayList<>();
        int dropped = 0;
        for (RawComment candidate : candidates) {
            ParsedComment comment = sanitize(candidate);
            if (comment == null) {
                continue;
            }
            if (sanitized.size() >= maxCount) {
                dropped++;
                continue;
            }
            sanitized.add(comment);
        }
        if (dropped > 0) {
            log.warn("Dropped {} parsed comment(s) beyond the configured cap of {}", dropped, maxCount);
        }
        if (sanitized.isEmpty()) {
            sanitized.add(new ParsedComment(null, null, Severity.INFO, "(model response contained no publishable content)"));
        }
        return sanitized;
    }

    private List<RawComment> tryParseJsonArray(String rawResponse) {
        String jsonSlice = extractJsonArraySlice(rawResponse);
        if (jsonSlice == null) {
            return null;
        }
        try {
            JsonNode arrayNode = objectMapper.readTree(jsonSlice);
            if (!arrayNode.isArray()) {
                return null;
            }
            List<RawComment> comments = new ArrayList<>();
            for (JsonNode entry : arrayNode) {
                if (!entry.isObject()) {
                    continue;
                }
                String text = firstNonBlankText(entry, "comment", "text", "message", "body");
                if (text == null) {
                    continue;
                }
                String file = firstNonBlankText(entry, "file", "filePath", "path");
                Integer line = firstInt(entry, "line", "lineNumber", "line_number");
                Severity severity = parseSeverity(firstNonBlankText(entry, "severity"));
                comments.add(new RawComment(file, line, severity, text));
            }
            return comments;
        } catch (Exception malformed) {
            // F02-03/SR-14: never log malformed.toString() here -- Jackson's exception toString()
            // includes a source excerpt (INCLUDE_SOURCE_IN_LOCATION is default-on), which would leak a
            // fragment of the untrusted raw_response (possibly proprietary source, A7) into file logs.
            // Log only the exception class and a static message.
            log.debug("Raw response has a JSON-array-shaped slice that failed to parse ({}); falling back to plain text",
                    malformed.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Finds the outermost {@code [...]} slice — a deliberately simple heuristic for LLM output that
     * wraps JSON in surrounding prose or markdown code fences ("minimum magic" per project principles).
     */
    private String extractJsonArraySlice(String rawResponse) {
        int start = rawResponse.indexOf('[');
        int end = rawResponse.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            return null;
        }
        return rawResponse.substring(start, end + 1);
    }

    private String firstNonBlankText(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText("").isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private Integer firstInt(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value == null) {
                continue;
            }
            if (value.isInt()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // try the next alias
                }
            }
        }
        return null;
    }

    private Severity parseSeverity(String raw) {
        if (raw == null) {
            return Severity.INFO;
        }
        try {
            return Severity.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return Severity.INFO;
        }
    }

    /** @return the sanitized comment, or {@code null} if nothing publishable remains (SR-08/SR-09). */
    private ParsedComment sanitize(RawComment candidate) {
        String withoutQuickActions = stripQuickActionLines(candidate.text());
        String trimmed = withoutQuickActions.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String capped = capLength(trimmed, properties.getPublish().getMaxCommentLength());
        String mentionsNeutralized = neutralizeMentions(capped);
        String escaped = HtmlUtils.htmlEscape(mentionsNeutralized);

        String sanitizedFilePath = sanitizeFilePath(candidate.filePath());
        Integer normalizedLine = normalizeLineNumber(candidate.lineNumber());

        return new ParsedComment(sanitizedFilePath, normalizedLine, candidate.severity(), escaped);
    }

    /**
     * F02-04/KD-2: {@code filePath} is LLM-controlled (T-06/T-19) and, until this fix, bypassed the
     * comment-text sanitation pipeline entirely — no length cap (crashing persistence past the
     * {@code VARCHAR(1024)} column, KD-2) and no HTML-escape/mention-neutralization (a latent
     * injection sink the moment file/line are published in a future feature). Embedded newlines/control
     * characters are collapsed first so a multi-line payload cannot smuggle content via this field.
     *
     * @return the sanitized file path, or {@code null} if nothing remains after sanitation
     */
    private String sanitizeFilePath(String filePath) {
        if (filePath == null) {
            return null;
        }
        String singleLine = filePath.replaceAll("\\R", " ").trim();
        if (singleLine.isEmpty()) {
            return null;
        }
        String capped = capLength(singleLine, FILE_PATH_MAX_LENGTH);
        String mentionsNeutralized = neutralizeMentions(capped);
        return HtmlUtils.htmlEscape(mentionsNeutralized);
    }

    /**
     * F02-04/KD-2: line numbers are 1-based; a non-positive value is nonsensical (and an out-of-{@code
     * int}-range value is already filtered out upstream in {@link #firstInt}, which only accepts
     * {@link JsonNode#isInt()} values or values that parse cleanly as {@code int}). Normalizes any
     * invalid value to {@code null} rather than persisting/publishing a misleading line reference.
     */
    private Integer normalizeLineNumber(Integer lineNumber) {
        return (lineNumber != null && lineNumber > 0) ? lineNumber : null;
    }

    private String stripQuickActionLines(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (line.trim().startsWith("/")) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    private String capLength(String text, int maxLength) {
        int max = Math.max(0, maxLength);
        if (text.length() <= max) {
            return text;
        }
        int cut = Math.max(0, max - TRUNCATION_SUFFIX.length());
        return text.substring(0, cut) + TRUNCATION_SUFFIX;
    }

    private String neutralizeMentions(String text) {
        return MENTION_PATTERN.matcher(text).replaceAll(mr -> "@" + ZERO_WIDTH_SPACE + mr.group(1));
    }

    private record RawComment(String filePath, Integer lineNumber, Severity severity, String text) {
    }
}
