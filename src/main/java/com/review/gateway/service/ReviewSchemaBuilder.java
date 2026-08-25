package com.review.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structured Review Output (architecture §4.1/§4.2, SRO-18/20-28): renders the per-chunk JSON Schema
 * that both (a) constrains the decoder via {@link DecoderConstraintRenderer} and (b) is the exact
 * document {@code StructuredResponseParser} validates a response against — one artifact, two uses,
 * never regenerated differently for either (SRO-18).
 *
 * <p>A <b>pure function</b> over {@code (filePaths, options)} — no DB access, no HTTP, no state,
 * exactly like {@link DiffChunker}/{@link ChunkContextRenderer}/{@link DiffSizeValidator}. Deterministic:
 * the same inputs always produce byte-identical output (an {@link ObjectNode} preserves insertion
 * order, which is what makes the SRO-03 normative property order — {@code findings} before
 * {@code summary}, at every level — a structural property of the tree, not an accident of a particular
 * {@link ObjectMapper} configuration).
 *
 * <p><b>SRO-05a (BLOCKING, threat model):</b> the schema is built exclusively via Jackson's
 * {@code ObjectNode} tree, then serialized with {@link ObjectMapper#writeValueAsString}. There is no
 * {@code StringBuilder}/{@code String.format}/{@code +} string templating anywhere in this class — the
 * schema's structure <em>is</em> the security control (SOTB-KEY in the threat model), so JSON-escaping
 * of an attacker-controlled path must be structural (Jackson's), never something this class has to get
 * right by hand.
 *
 * <p><b>SRO-67a (BLOCKING, threat model SOR-04a):</b> {@link #build} throws on a {@code null}/empty
 * path list — there is no code path here that can ever emit a {@code files} object with an empty
 * {@code required} array/empty {@code properties}, which would otherwise be a well-formed schema that
 * trivially validates a response with zero coverage. Callers (claim time) must check the SRO-67b
 * fail-closed condition <em>before</em> ever reaching this method — this is a defense-in-depth
 * invariant of the builder itself, not the primary mechanism.
 */
@Service
public class ReviewSchemaBuilder {

    /** SRO-20/9.3.2: array-of-enum was rejected as the coverage mechanism — see the class/architecture doc. */
    private static final String[] SEVERITY_VALUES = {"critical", "major", "minor", "info"};

    /** Per-file summary cap (SRO-25) — not independently configurable, unlike the finding-level caps. */
    private static final int PER_FILE_SUMMARY_MAX_LENGTH = 200;
    /** Chunk-level summary cap (SRO-26) — populates {@code review_results.summary}. */
    private static final int CHUNK_SUMMARY_MAX_LENGTH = 500;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Structural knobs (architecture §8 {@code gateway.structured.*}), threaded in by the caller rather
     * than read from {@code GatewayProperties} directly — keeps this class a pure function with no
     * config-object dependency, exactly like {@code DiffSizeValidator}'s own explicit-parameter style.
     */
    public record SchemaOptions(int maxFindingsPerFile, int maxCommentChars, int maxSuggestionChars,
                                 boolean perFileSummary) {
    }

    /**
     * @param filePaths the chunk's exact, already-sanitized, already-alphabet-checked (SRO-65) file
     *                   paths — becomes the {@code files} object's key set, verbatim and in order
     * @return the fully-inlined JSON Schema document (SRO-02), never {@code null}/blank
     * @throws IllegalArgumentException if {@code filePaths} is {@code null} or empty (SRO-67a), or
     *                                    contains a duplicate entry (F-SRO-02: an unconditional invariant
     *                                    of the builder itself, never a caller obligation — a duplicate
     *                                    would otherwise produce a schema whose {@code required} array
     *                                    names the same key twice against a single {@code properties}
     *                                    entry, handed verbatim to the decoder-constraint compiler)
     */
    public String build(List<String> filePaths, SchemaOptions options) {
        if (filePaths == null || filePaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "ReviewSchemaBuilder.build requires a non-empty file path list (SRO-67a: an empty "
                            + "coverage set must be impossible to build)");
        }
        Set<String> distinct = new LinkedHashSet<>(filePaths);
        if (distinct.size() != filePaths.size()) {
            throw new IllegalArgumentException(
                    "ReviewSchemaBuilder.build requires distinct file paths (F-SRO-02: a duplicate would "
                            + "produce a schema with a duplicated 'required' entry)");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ArrayNode topRequired = root.putArray("required");
        topRequired.add("files");
        topRequired.add("summary");

        ObjectNode properties = root.putObject("properties");
        // SRO-03: "files" before "summary" -- chunk-level findings-bearing content before the summary
        // that describes it, matching the per-file findings-before-summary rule one level down.
        properties.set("files", buildFilesNode(filePaths, options));
        properties.set("summary", stringSchema(CHUNK_SUMMARY_MAX_LENGTH));

        try {
            return objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            // A tree built exclusively from ObjectNode/ArrayNode/textual values can never fail to
            // serialize; this catch exists only because writeValueAsString's checked signature demands it.
            throw new IllegalStateException("Unexpected failure serializing a well-formed schema tree", impossible);
        }
    }

    private ObjectNode buildFilesNode(List<String> filePaths, SchemaOptions options) {
        ObjectNode filesNode = objectMapper.createObjectNode();
        filesNode.put("type", "object");
        filesNode.put("additionalProperties", false);
        ArrayNode filesRequired = filesNode.putArray("required");
        for (String path : filePaths) {
            filesRequired.add(path);
        }
        ObjectNode filesProperties = filesNode.putObject("properties");
        for (String path : filePaths) {
            filesProperties.set(path, buildFileEntryNode(options));
        }
        return filesNode;
    }

    /** SRO-21/SRO-22: no {@code file} field inside a finding — the enclosing key already is the file. */
    private ObjectNode buildFileEntryNode(SchemaOptions options) {
        ObjectNode fileEntry = objectMapper.createObjectNode();
        fileEntry.put("type", "object");
        fileEntry.put("additionalProperties", false);
        ArrayNode required = fileEntry.putArray("required");
        required.add("findings");
        ObjectNode properties = fileEntry.putObject("properties");
        // SRO-03: "findings" before "summary".
        properties.set("findings", buildFindingsArrayNode(options));
        if (options.perFileSummary()) {
            required.add("summary");
            properties.set("summary", stringSchema(PER_FILE_SUMMARY_MAX_LENGTH));
        }
        return fileEntry;
    }

    private ObjectNode buildFindingsArrayNode(SchemaOptions options) {
        ObjectNode findings = objectMapper.createObjectNode();
        findings.put("type", "array");
        findings.put("maxItems", Math.max(0, options.maxFindingsPerFile()));
        findings.set("items", buildFindingItemNode(options));
        return findings;
    }

    /** SRO-21: every property required; "nothing to say" is a sentinel value, never omission. */
    private ObjectNode buildFindingItemNode(SchemaOptions options) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "object");
        item.put("additionalProperties", false);
        ArrayNode required = item.putArray("required");
        required.add("line");
        required.add("severity");
        required.add("comment");
        required.add("suggestion");

        ObjectNode properties = item.putObject("properties");
        ObjectNode lineNode = objectMapper.createObjectNode();
        lineNode.put("type", "integer");
        properties.set("line", lineNode);

        ObjectNode severityNode = objectMapper.createObjectNode();
        severityNode.put("type", "string");
        ArrayNode severityEnum = severityNode.putArray("enum");
        for (String value : SEVERITY_VALUES) {
            severityEnum.add(value);
        }
        properties.set("severity", severityNode);

        properties.set("comment", stringSchema(options.maxCommentChars()));
        properties.set("suggestion", stringSchema(options.maxSuggestionChars()));
        return item;
    }

    private ObjectNode stringSchema(int maxLength) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "string");
        node.put("maxLength", Math.max(0, maxLength));
        return node;
    }

    /** Normalizes a whitelist-parsed severity token to the schema's lowercase enum vocabulary (SRO-24). */
    public static String toLowerSeverity(String severity) {
        return severity == null ? null : severity.toLowerCase(Locale.ROOT);
    }
}
