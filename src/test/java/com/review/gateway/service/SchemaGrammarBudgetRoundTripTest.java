package com.review.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.service.StructuredResponseParser.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round: exercises the ACTUAL coupling between {@link ReviewSchemaBuilder} and {@link
 * StructuredResponseParser} that isolated unit tests can miss (task item 1). Two things a plain
 * {@code doesNotContain("maxLength")} string check cannot prove: (a) that NO node anywhere in the tree
 * (walked recursively, not grepped) carries {@code maxLength}, for a realistic multi-file input; and (b)
 * that a response conforming to the schema {@code ReviewSchemaBuilder} actually built is accepted by
 * {@code StructuredResponseParser} end to end.
 */
class SchemaGrammarBudgetRoundTripTest {

    private final ReviewSchemaBuilder builder = new ReviewSchemaBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void noNodeAnywhereInAMultiFileSchemaCarriesMaxLengthAndMaxItemsIsExactlyConfigured() throws Exception {
        List<String> paths = List.of("src/A.java", "src/B.java", "src/C.java", "pkg/sub/D.kt");
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(37, 1200, 2000, true);

        String schema = builder.build(paths, options);
        JsonNode root = mapper.readTree(schema);

        List<String> maxLengthPaths = new ArrayList<>();
        List<JsonNode> maxItemsNodes = new ArrayList<>();
        walk(root, "$", maxLengthPaths, maxItemsNodes);

        assertThat(maxLengthPaths)
                .as("SGB-01: no node anywhere in the tree may carry maxLength -- found at: %s", maxLengthPaths)
                .isEmpty();
        assertThat(maxItemsNodes).as("one maxItems site per file's findings array").hasSize(paths.size());
        for (JsonNode findingsNode : maxItemsNodes) {
            assertThat(findingsNode.get("maxItems").asInt()).isEqualTo(37);
        }
    }

    /** Recursively walks every object node in the tree, collecting maxLength sightings and findings-array nodes. */
    private void walk(JsonNode node, String path, List<String> maxLengthPaths, List<JsonNode> maxItemsNodes) {
        if (node.isObject()) {
            if (node.has("maxLength")) {
                maxLengthPaths.add(path);
            }
            if (node.isObject() && "array".equals(node.path("type").asText(null)) && node.has("maxItems")) {
                maxItemsNodes.add(node);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                walk(entry.getValue(), path + "." + entry.getKey(), maxLengthPaths, maxItemsNodes);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", maxLengthPaths, maxItemsNodes);
            }
        }
    }

    /**
     * The actual coupling: build the real schema via {@link ReviewSchemaBuilder}, derive the file-path
     * set FROM that built schema (never re-typed by the test), construct a response that satisfies it
     * (within the schema's own maxItems bound), and confirm {@link StructuredResponseParser} -- fed the
     * SAME file list and options -- validates it successfully. This is the round-trip task item 1 asks
     * for: schema builder and parser exercised together, not in isolation.
     */
    @Test
    void aResponseConformingToTheBuiltSchemaValidatesSuccessfullyThroughTheRealParser() throws Exception {
        List<String> paths = List.of("src/main/java/Service.java", "src/main/java/Repo.java", "README.md");
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true);
        String schema = builder.build(paths, options);
        JsonNode schemaRoot = mapper.readTree(schema);

        // Derive the expected file set FROM the schema's own "files.required" array, not by re-typing
        // the input list a second time -- this is what actually proves the two components are in sync.
        List<String> filePathsFromSchema = new ArrayList<>();
        schemaRoot.at("/properties/files/required").forEach(n -> filePathsFromSchema.add(n.asText()));
        assertThat(filePathsFromSchema).containsExactlyInAnyOrderElementsOf(paths);

        int maxItemsFromSchema = schemaRoot.at("/properties/files/properties/src~1main~1java~1Service.java"
                        + "/properties/findings/maxItems").asInt();
        assertThat(maxItemsFromSchema).isEqualTo(20);

        // Construct a response with exactly maxItemsFromSchema findings for one file (schema-conforming
        // shape: every file present, every finding property present) and zero for the others. Built via
        // Jackson's own tree, not hand-escaped string concatenation, so a path with special characters
        // can never produce a self-inflicted JSON-escaping bug in the test itself.
        com.fasterxml.jackson.databind.node.ObjectNode responseRoot = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode filesNode = responseRoot.putObject("files");
        for (String path : filePathsFromSchema) {
            com.fasterxml.jackson.databind.node.ObjectNode fileEntry = filesNode.putObject(path);
            com.fasterxml.jackson.databind.node.ArrayNode findingsArray = fileEntry.putArray("findings");
            if (path.equals("src/main/java/Service.java")) {
                for (int i = 0; i < maxItemsFromSchema; i++) {
                    com.fasterxml.jackson.databind.node.ObjectNode finding = findingsArray.addObject();
                    finding.put("line", i + 1);
                    finding.put("severity", "minor");
                    finding.put("comment", "c" + i);
                    finding.put("suggestion", "");
                }
            }
            fileEntry.put("summary", "ok");
        }
        responseRoot.put("summary", "overall");
        String responseJson = mapper.writeValueAsString(responseRoot);

        GatewayProperties properties = new GatewayProperties();
        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        CommentRenderer commentRenderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        StructuredResponseParser parser = new StructuredResponseParser(commentParser, commentRenderer,
                new TextSanitizer(), properties, new MetricsCounters());

        ValidationResult result = parser.validate(responseJson, filePathsFromSchema, false, null, null,
                options, 1_000);

        assertThat(result.isSuccess())
                .as("a response conforming to the ACTUAL built schema (same file set, findings count at "
                        + "exactly the schema's own maxItems) must validate successfully: %s",
                        result.isSuccess() ? null : result.failure())
                .isTrue();
        assertThat(result.success().comments()).hasSize(maxItemsFromSchema);
    }
}
