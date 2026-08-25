package com.review.gateway.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.dto.ParsedComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structured Review Output (architecture §5.1, SRO-30-34): strict, non-fallback parsing and validation
 * of a structured (v3) response, hand-rolled against the same expected file-path set {@link
 * ReviewSchemaBuilder} used to build the claim's schema — no JSON-Schema validation library is added
 * (CLAUDE.md "no extra infrastructure"; SRO-30).
 *
 * <p><b>SRO-31:</b> parsing is direct — {@code objectMapper.readTree(raw.strip())} — with {@code
 * strip()} the only normalization permitted. No {@code extractJsonArraySlice}-style scan, no
 * markdown-fence stripping, no prose tolerance: under this feature "malformed" is an infra/config fact
 * the operator must see, not a model mood to work around.
 *
 * <p><b>SRO-04/threat-model SOR-11 (structurally un-shortcuttable, CRITICAL):</b> this class has
 * <b>no dependency whatsoever on {@code Backend}/{@code StructuredOutputMode}/{@code finish_reason ==
 * "stop"}</b> — a conforming response from a {@code mode = OFF} backend is validated identically to one
 * from a constrained backend, and an empty expected path set is never treated as a satisfiable
 * condition (that case is a caller bug, {@link IllegalStateException}, never reported as a pass or as
 * {@code COVERAGE_SHORTFALL} — SRO-67c).
 */
@Service
public class StructuredResponseParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredResponseParser.class);

    /** SRO-45's closed vocabulary — metrics/last_error are keyed on these four kinds only. */
    public enum FailureKind {
        NOT_JSON,
        SCHEMA_MISMATCH,
        COVERAGE_SHORTFALL,
        TRUNCATED
    }

    /** Sealed two-shape validation outcome: exactly one of {@link #success()}/{@link #failure()} is non-null. */
    public record ValidationResult(Success success, Failure failure) {

        public boolean isSuccess() {
            return success != null;
        }

        static ValidationResult ok(Success success) {
            return new ValidationResult(success, null);
        }

        static ValidationResult fail(FailureKind kind, String detail) {
            return new ValidationResult(null, new Failure(kind, detail));
        }
    }

    public record Success(List<ParsedComment> comments, String summary) {
    }

    /** {@code detail} is already sanitized/capped — safe to embed directly in {@code last_error}. */
    public record Failure(FailureKind kind, String detail) {
    }

    private static final Set<String> TOP_LEVEL_REQUIRED_KEYS = Set.of("files", "summary");
    private static final int MAX_LISTED_KEYS = 5;
    private static final int MAX_KEY_CHARS = 64;

    private final CommentParser commentParser;
    private final CommentRenderer commentRenderer;
    private final TextSanitizer textSanitizer;
    private final ObjectMapper objectMapper;

    public StructuredResponseParser(CommentParser commentParser, CommentRenderer commentRenderer,
                                     TextSanitizer textSanitizer, GatewayProperties properties) {
        this.commentParser = commentParser;
        this.commentRenderer = commentRenderer;
        this.textSanitizer = textSanitizer;
        // Threat model SOR-14 (TRACKED): a dedicated ObjectMapper with explicit StreamReadConstraints,
        // turning inherited Jackson defaults into a stated contract on the one parser that eats
        // adversarial input by design.
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxNameLength(1024)
                .maxStringLength(Math.max(20_000, properties.getPublish().getMaxRawResponseLength()))
                .build();
        JsonFactory jsonFactory = JsonFactory.builder().streamReadConstraints(constraints).build();
        this.objectMapper = JsonMapper.builder(jsonFactory).build();
    }

    /**
     * @param rawResponse          the (already {@code capRawResponseIfNeeded}-capped, SRO-32 step 4
     *                              depends on that ordering) raw model response
     * @param expectedPaths        the exact same {@code List<String>} instance {@code
     *                              ReviewSchemaBuilder} was given for this chunk (SRO-64c) — the
     *                              validator's "expected" half of the coverage check
     * @param rawResponseTruncated whether {@code ResultProcessor.capRawResponseIfNeeded} truncated the
     *                              raw response before this call
     * @param finishReason         the whitelist-parsed {@link com.review.gateway.model.enums.FinishReason}
     *                              wire value for this completion, or {@code null}
     * @param chunkDiff             {@code review_chunks.diff} for this job's own (reviewId, chunkIndex) —
     *                              forwarded to {@link CommentRenderer} verbatim (SOR-12)
     * @throws IllegalStateException if {@code expectedPaths} is null/empty (SRO-67c) — an internal
     *                                 invariant violation, never a validation outcome
     */
    public ValidationResult validate(String rawResponse, List<String> expectedPaths, boolean rawResponseTruncated,
                                      String finishReason, String chunkDiff, ReviewSchemaBuilder.SchemaOptions options) {
        if (expectedPaths == null || expectedPaths.isEmpty()) {
            // SRO-67c: the more rigorously SRO-04 is implemented, the more confidently an empty expected
            // set would otherwise pass -- this is OUR bug (SRO-67b should have failed the job closed
            // before this was ever dispatched), never reported as a satisfiable coverage check.
            throw new IllegalStateException(
                    "StructuredResponseParser.validate called with an empty/null expected path set -- "
                            + "SRO-67b should have failed this job closed at claim time; this is a Gateway bug");
        }
        Set<String> expectedSet = new LinkedHashSet<>(expectedPaths);

        // SRO-32 step 4 (TRUNCATED), checked before NOT_JSON: known-truncated signals take precedence
        // over whatever readTree would have made of a (possibly coincidentally valid-looking) prefix.
        boolean knownTruncated = "length".equals(finishReason) || rawResponseTruncated;
        if (knownTruncated) {
            return ValidationResult.fail(FailureKind.TRUNCATED,
                    "finish_reason=" + (finishReason == null ? "null" : finishReason)
                            + "; raw_response_truncated=" + rawResponseTruncated);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawResponse == null ? "" : rawResponse.strip());
        } catch (JsonEOFException unexpectedEof) {
            return ValidationResult.fail(FailureKind.TRUNCATED, "unexpected end of input while parsing JSON");
        } catch (JsonParseException | com.fasterxml.jackson.databind.exc.MismatchedInputException malformed) {
            // F02-03/SR-14: never malformed.getMessage() -- Jackson quotes a source excerpt of the
            // untrusted raw response. Only the exception class is safe.
            return ValidationResult.fail(FailureKind.NOT_JSON, malformed.getClass().getSimpleName());
        } catch (Exception malformed) {
            return ValidationResult.fail(FailureKind.NOT_JSON, malformed.getClass().getSimpleName());
        }
        if (root == null || !root.isObject()) {
            return ValidationResult.fail(FailureKind.NOT_JSON, "response root is not a JSON object");
        }

        ValidationResult shapeFailure = validateTopLevelShape(root);
        if (shapeFailure != null) {
            return shapeFailure;
        }
        String chunkSummary = root.get("summary").asText();
        JsonNode filesNode = root.get("files");

        Set<String> actualKeys = fieldNames(filesNode);
        if (!actualKeys.equals(expectedSet)) {
            return ValidationResult.fail(FailureKind.COVERAGE_SHORTFALL, formatCoverageDetail(expectedSet, actualKeys));
        }

        List<RawFinding> findings = new ArrayList<>();
        for (String path : expectedPaths) {
            ValidationResult fileShapeFailure = validateFileEntryAndCollect(filesNode.get(path), path, options, findings);
            if (fileShapeFailure != null) {
                return fileShapeFailure;
            }
        }

        List<ParsedComment> comments = new ArrayList<>();
        for (RawFinding finding : findings) {
            // SRO-34: a file whose findings is empty simply contributes no entries to `findings` above.
            Integer normalizedLine = commentParser.normalizeLineNumber(finding.line());
            String renderedText = commentRenderer.render(finding.filePath(), normalizedLine, finding.severity(),
                    finding.comment(), finding.suggestion(), chunkDiff);
            String sanitizedFilePath = commentParser.sanitizeFilePath(finding.filePath());
            comments.add(new ParsedComment(sanitizedFilePath, normalizedLine, finding.severity(), renderedText));
        }
        return ValidationResult.ok(new Success(comments, chunkSummary));
    }

    private ValidationResult validateTopLevelShape(JsonNode root) {
        Set<String> topKeys = fieldNames(root);
        if (!topKeys.equals(TOP_LEVEL_REQUIRED_KEYS)) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH,
                    "top-level keys did not match the required {files, summary} set");
        }
        JsonNode filesNode = root.get("files");
        if (filesNode == null || !filesNode.isObject()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "'files' is missing or not a JSON object");
        }
        JsonNode summaryNode = root.get("summary");
        if (summaryNode == null || !summaryNode.isTextual()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "'summary' is missing or not a string");
        }
        return null;
    }

    /** @return a failure result, or {@code null} on success (findings appended to {@code out}). */
    private ValidationResult validateFileEntryAndCollect(JsonNode fileNode, String path,
                                                           ReviewSchemaBuilder.SchemaOptions options,
                                                           List<RawFinding> out) {
        if (fileNode == null || !fileNode.isObject()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "a file entry is missing or not a JSON object");
        }
        JsonNode findingsNode = fileNode.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "'findings' is missing or not an array for a file entry");
        }
        if (options.perFileSummary()) {
            JsonNode fileSummaryNode = fileNode.get("summary");
            if (fileSummaryNode == null || !fileSummaryNode.isTextual()) {
                return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "per-file 'summary' is missing or not a string");
            }
        }
        if (findingsNode.size() > Math.max(0, options.maxFindingsPerFile())) {
            // SRO-04: never trust the decoder constraint -- the maxItems bound is re-checked here too.
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "'findings' array exceeded the configured maximum");
        }
        for (JsonNode findingNode : findingsNode) {
            ValidationResult findingFailure = validateFindingAndCollect(findingNode, path, options, out);
            if (findingFailure != null) {
                return findingFailure;
            }
        }
        return null;
    }

    private ValidationResult validateFindingAndCollect(JsonNode findingNode, String path,
                                                         ReviewSchemaBuilder.SchemaOptions options, List<RawFinding> out) {
        if (findingNode == null || !findingNode.isObject()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "a finding is not a JSON object");
        }
        JsonNode lineNode = findingNode.get("line");
        JsonNode severityNode = findingNode.get("severity");
        JsonNode commentNode = findingNode.get("comment");
        JsonNode suggestionNode = findingNode.get("suggestion");
        if (lineNode == null || !lineNode.isIntegralNumber()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'line' is missing or not an integer");
        }
        if (severityNode == null || !severityNode.isTextual()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'severity' is missing or not a string");
        }
        if (commentNode == null || !commentNode.isTextual()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'comment' is missing or not a string");
        }
        if (suggestionNode == null || !suggestionNode.isTextual()) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'suggestion' is missing or not a string");
        }
        Severity severity = parseSeverityStrict(severityNode.asText());
        if (severity == null) {
            // SRO-24: an out-of-set severity is a validation failure, never a silent CommentParser-style
            // downgrade to INFO.
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'severity' is outside the allowed enum");
        }
        String comment = commentNode.asText();
        String suggestion = suggestionNode.asText();
        if (comment.length() > Math.max(0, options.maxCommentChars())) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'comment' exceeded the configured maxLength");
        }
        if (suggestion.length() > Math.max(0, options.maxSuggestionChars())) {
            return ValidationResult.fail(FailureKind.SCHEMA_MISMATCH, "finding 'suggestion' exceeded the configured maxLength");
        }
        out.add(new RawFinding(path, lineNode.asInt(), severity, comment, suggestion));
        return null;
    }

    private Severity parseSeverityStrict(String rawSeverity) {
        if (rawSeverity == null) {
            return null;
        }
        return switch (rawSeverity) {
            case "critical" -> Severity.CRITICAL;
            case "major" -> Severity.MAJOR;
            case "minor" -> Severity.MINOR;
            case "info" -> Severity.INFO;
            default -> null;
        };
    }

    private Set<String> fieldNames(JsonNode objectNode) {
        Set<String> names = new LinkedHashSet<>();
        objectNode.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Threat model SOR-15 (TRACKED): lists at most {@value #MAX_LISTED_KEYS} missing and {@value
     * #MAX_LISTED_KEYS} unexpected keys, each capped to {@value #MAX_KEY_CHARS} chars, with a
     * {@code (+K more)} remainder marker -- so {@code RetryManager}'s own 512-char cap on the whole
     * {@code last_error} string can never truncate away the one diagnosis {@code COVERAGE_SHORTFALL}
     * exists to carry ("omitted src/B.java" vs. "invented src/Z.java" are different diagnoses).
     */
    private String formatCoverageDetail(Set<String> expected, Set<String> actual) {
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        return "missing=" + formatKeyList(missing) + "; unexpected=" + formatKeyList(unexpected);
    }

    private String formatKeyList(Set<String> keys) {
        if (keys.isEmpty()) {
            return "none";
        }
        List<String> capped = keys.stream()
                .limit(MAX_LISTED_KEYS)
                .map(key -> textSanitizer.sanitizeSingleLine(key, MAX_KEY_CHARS))
                .collect(Collectors.toList());
        StringBuilder rendered = new StringBuilder("[").append(String.join(", ", capped)).append(']');
        if (keys.size() > MAX_LISTED_KEYS) {
            rendered.append(" (+").append(keys.size() - MAX_LISTED_KEYS).append(" more)");
        }
        return rendered.toString();
    }

    private record RawFinding(String filePath, int line, Severity severity, String comment, String suggestion) {
    }
}
