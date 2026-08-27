package com.review.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ReviewSchemaBuilder} (architecture §4.1/§4.2, SRO-18/20-28, threat model
 * SOR-05a/SRO-67a).
 */
class ReviewSchemaBuilderTest {

    private final ReviewSchemaBuilder builder = new ReviewSchemaBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewSchemaBuilder.SchemaOptions defaultOptions() {
        return new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true);
    }

    @Test
    void throwsOnNullOrEmptyPathList() {
        assertThatThrownBy(() -> builder.build(null, defaultOptions()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.build(List.of(), defaultOptions()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsOnADuplicatePathEntry() {
        // F-SRO-02: an unconditional builder invariant -- a duplicate would otherwise emit a schema whose
        // "required" array names the same key twice against a single "properties" entry.
        assertThatThrownBy(() -> builder.build(List.of("src/A.java", "src/A.java"), defaultOptions()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filesKeySetIsExactlyTheGivenPathsAllRequiredNoAdditional() throws Exception {
        String schema = builder.build(List.of("src/A.java", "src/B.java"), defaultOptions());
        JsonNode root = mapper.readTree(schema);

        assertThat(root.get("type").asText()).isEqualTo("object");
        assertThat(root.get("additionalProperties").asBoolean()).isFalse();
        assertThat(toList(root.get("required"))).containsExactly("files", "summary");

        JsonNode files = root.at("/properties/files");
        assertThat(files.get("type").asText()).isEqualTo("object");
        assertThat(files.get("additionalProperties").asBoolean()).isFalse();
        assertThat(toList(files.get("required"))).containsExactlyInAnyOrder("src/A.java", "src/B.java");
        assertThat(files.get("properties").has("src/A.java")).isTrue();
        assertThat(files.get("properties").has("src/B.java")).isTrue();
    }

    @Test
    void everyPathBecomesAnInlinedObjectNeverARefOrSharedDefinition() throws Exception {
        String schema = builder.build(List.of("src/A.java", "src/B.java"), defaultOptions());
        JsonNode root = mapper.readTree(schema);

        // SRO-02: fully inlined -- no $ref/$defs anywhere in the document.
        assertThat(schema).doesNotContain("$ref").doesNotContain("$defs");

        JsonNode fileA = root.at("/properties/files/properties/src~1A.java");
        JsonNode fileB = root.at("/properties/files/properties/src~1B.java");
        assertThat(fileA.isMissingNode()).isFalse();
        assertThat(fileB.isMissingNode()).isFalse();
        assertThat(fileA).isNotSameAs(fileB);
    }

    @Test
    void perFileObjectRequiresFindingsAndSummaryInThatOrderWhenPerFileSummaryEnabled() throws Exception {
        String schema = builder.build(List.of("A.java"), defaultOptions());
        JsonNode fileEntry = mapper.readTree(schema).at("/properties/files/properties/A.java");

        assertThat(toList(fileEntry.get("required"))).containsExactly("findings", "summary");
        assertThat(toList(fileEntry.get("properties").fieldNames())).containsExactly("findings", "summary");
    }

    @Test
    void perFileSummaryDisabledOmitsSummaryFromThePerFileObject() throws Exception {
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, false);
        String schema = builder.build(List.of("A.java"), options);
        JsonNode fileEntry = mapper.readTree(schema).at("/properties/files/properties/A.java");

        assertThat(toList(fileEntry.get("required"))).containsExactly("findings");
        assertThat(fileEntry.get("properties").has("summary")).isFalse();
    }

    @Test
    void findingObjectHasNoFileFieldAndEveryPropertyIsRequired() throws Exception {
        String schema = builder.build(List.of("A.java"), defaultOptions());
        JsonNode findingItem = mapper.readTree(schema).at("/properties/files/properties/A.java/properties/findings/items");

        assertThat(findingItem.get("additionalProperties").asBoolean()).isFalse();
        assertThat(toList(findingItem.get("required"))).containsExactly("line", "severity", "comment", "suggestion");
        assertThat(findingItem.get("properties").has("file")).isFalse();
        assertThat(findingItem.at("/properties/line/type").asText()).isEqualTo("integer");
    }

    @Test
    void severityIsAClosedLowercaseEnum() throws Exception {
        String schema = builder.build(List.of("A.java"), defaultOptions());
        JsonNode severityEnum = mapper.readTree(schema)
                .at("/properties/files/properties/A.java/properties/findings/items/properties/severity/enum");

        assertThat(toList(severityEnum)).containsExactly("critical", "major", "minor", "info");
    }

    /**
     * T-1.4 inverted (Structured Output Grammar Budget, SGB-01): {@code maxLength} used to be asserted
     * here; it is now asserted ABSENT at every level, and {@code maxItems} is the only structural bound
     * left. A bounded string (a GBNF {@code char{0,N}} repetition) is what tripped llama.cpp's grammar-
     * parser complexity guard in production (architecture doc §1) -- {@code maxItems} on {@code findings}
     * is a much smaller repetition site (~20 of a 2000 budget) and is the one bound this feature actually
     * needs at the decoder.
     */
    @Test
    void maxItemsReflectsTheSuppliedOptionsAndMaxLengthIsNeverEmittedAnywhere() throws Exception {
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(7, 111, 222, true);
        String schema = builder.build(List.of("A.java"), options);
        JsonNode findings = mapper.readTree(schema).at("/properties/files/properties/A.java/properties/findings");

        assertThat(findings.get("maxItems").asInt()).isEqualTo(7);
        assertThat(schema).as("SGB-01: maxLength must never be emitted -- it is what compiles to the "
                        + "GBNF repetition that tripped llama.cpp's MAX_REPETITION_THRESHOLD in production")
                .doesNotContain("maxLength");
    }

    @Test
    void findingsIsDeclaredBeforeSummaryAndFilesBeforeChunkSummary() throws Exception {
        // SRO-03: property declaration order is normative -- findings before summary at every level, and
        // "files" before the chunk-level "summary".
        String schema = builder.build(List.of("A.java"), defaultOptions());
        JsonNode root = mapper.readTree(schema);

        assertThat(toList(root.get("properties").fieldNames())).containsExactly("files", "summary");
    }

    @Test
    void pathsContainingJsonSpecialCharactersAreEscapedStructurallyNotConcatenated() throws Exception {
        // SOR-05a: the schema is built via Jackson tree construction, so a path containing a quote or
        // backslash round-trips as data, never breaking the document's structure.
        String tricky = "src/\"weird\"\\file.java";
        String schema = builder.build(List.of(tricky), defaultOptions());

        JsonNode root = mapper.readTree(schema);
        assertThat(toList(root.at("/properties/files/required"))).containsExactly(tricky);
    }

    @Test
    void schemaIsDeterministicForTheSameInputs() {
        List<String> paths = List.of("A.java", "B.java", "C.java");
        String first = builder.build(paths, defaultOptions());
        String second = builder.build(paths, defaultOptions());

        assertThat(first).isEqualTo(second);
    }

    /**
     * T-1.10 / SGB-07 (Structured Output Grammar Budget): golden fixtures for {@code DEPLOYMENT.md}'s
     * capability-verification recipe. A "two-field toy schema" (the recipe's previous {@code {"ok":
     * boolean}}/{@code {"verdict": enum}} bodies) has no repetition site and therefore cannot detect this
     * failure class by construction -- see architecture doc §1.2. These fixtures are the ACTUAL schema
     * {@code ReviewSchemaBuilder} emits at production config defaults (from {@code application.yml}:
     * {@code max-findings-per-file=20}, {@code max-files-per-chunk=40}), for a 1-file chunk and a
     * {@code max-files-per-chunk}-file (40) chunk, so the deployment recipe can post the SAME document
     * this Gateway would actually send and cannot silently drift from the builder.
     */
    @Test
    void oneFileAndFortyFileSchemaFixturesMatchProductionDefaultsAndTheCommittedGoldenFiles() throws Exception {
        ReviewSchemaBuilder.SchemaOptions productionDefaults = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true);
        ObjectMapper pretty = new ObjectMapper();

        String oneFileSchema = builder.build(List.of("src/main/java/com/example/Service.java"), productionDefaults);
        assertThat(prettyPrint(pretty, oneFileSchema)).isEqualToNormalizingNewlines(readFixture("schema-1-file.json"));

        List<String> fortyFiles = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            fortyFiles.add(String.format("src/main/java/com/example/File%02d.java", i));
        }
        String fortyFileSchema = builder.build(fortyFiles, productionDefaults);
        assertThat(prettyPrint(pretty, fortyFileSchema)).isEqualToNormalizingNewlines(readFixture("schema-40-files.json"));
    }

    private String prettyPrint(ObjectMapper mapper, String compactJson) throws Exception {
        JsonNode tree = mapper.readTree(compactJson);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    }

    private String readFixture(String name) throws java.io.IOException {
        try (var in = getClass().getResourceAsStream(
                "/fixtures/structured-output-grammar-budget/" + name)) {
            if (in == null) {
                throw new java.io.FileNotFoundException(
                        "Missing fixture: fixtures/structured-output-grammar-budget/" + name);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private List<String> toList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        Iterator<JsonNode> it = arrayNode.elements();
        while (it.hasNext()) {
            values.add(it.next().asText());
        }
        return values;
    }

    private List<String> toList(Iterator<String> fieldNames) {
        List<String> values = new ArrayList<>();
        fieldNames.forEachRemaining(values::add);
        return values;
    }
}
