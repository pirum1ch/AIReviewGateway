package com.review.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.model.enums.StructuredOutputMode;
import com.review.gateway.service.DecoderConstraintRenderer.DecoderConstraint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DecoderConstraintRenderer} (architecture §3.1/§3.2, SRO-05/06/13).
 */
class DecoderConstraintRendererTest {

    private final DecoderConstraintRenderer renderer = new DecoderConstraintRenderer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReviewSchemaBuilder schemaBuilder = new ReviewSchemaBuilder();

    private String sampleSchema() {
        return schemaBuilder.build(java.util.List.of("A.java"),
                new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true));
    }

    @Test
    void offModeProducesNoConstraint() {
        DecoderConstraint result = renderer.render(sampleSchema(), StructuredOutputMode.OFF);

        assertThat(result.responseFormat()).isNull();
        assertThat(result.jsonSchema()).isNull();
    }

    @Test
    void nullModeProducesNoConstraint() {
        DecoderConstraint result = renderer.render(sampleSchema(), null);

        assertThat(result.responseFormat()).isNull();
        assertThat(result.jsonSchema()).isNull();
    }

    @Test
    void topLevelJsonSchemaModeSetsOnlyJsonSchemaVerbatim() {
        String schema = sampleSchema();
        DecoderConstraint result = renderer.render(schema, StructuredOutputMode.TOP_LEVEL_JSON_SCHEMA);

        assertThat(result.jsonSchema()).isEqualTo(schema);
        assertThat(result.responseFormat()).isNull();
    }

    @Test
    void responseFormatJsonSchemaModeWrapsWithNameAndStrictTrue() throws Exception {
        DecoderConstraint result = renderer.render(sampleSchema(), StructuredOutputMode.RESPONSE_FORMAT_JSON_SCHEMA);

        assertThat(result.jsonSchema()).isNull();
        assertThat(result.responseFormat()).isNotNull();
        JsonNode root = mapper.readTree(result.responseFormat());
        assertThat(root.get("type").asText()).isEqualTo("json_schema");
        assertThat(root.at("/json_schema/name").asText()).isEqualTo("code_review");
        assertThat(root.at("/json_schema/strict").asBoolean()).isTrue();
        assertThat(root.at("/json_schema/schema/type").asText()).isEqualTo("object");
    }

    @Test
    void responseFormatSchemaModeWrapsAsJsonObjectLegacyShape() throws Exception {
        DecoderConstraint result = renderer.render(sampleSchema(), StructuredOutputMode.RESPONSE_FORMAT_SCHEMA);

        assertThat(result.jsonSchema()).isNull();
        JsonNode root = mapper.readTree(result.responseFormat());
        assertThat(root.get("type").asText()).isEqualTo("json_object");
        assertThat(root.at("/schema/type").asText()).isEqualTo("object");
    }

    @Test
    void exactlyOneFieldIsEverNonNullAcrossEveryMode() {
        for (StructuredOutputMode mode : StructuredOutputMode.values()) {
            DecoderConstraint result = renderer.render(sampleSchema(), mode);
            boolean responseFormatSet = result.responseFormat() != null;
            boolean jsonSchemaSet = result.jsonSchema() != null;
            assertThat(responseFormatSet && jsonSchemaSet)
                    .as("mode %s must never set both fields", mode)
                    .isFalse();
        }
    }

    @Test
    void unparseableSchemaDegradesToNoConstraintRatherThanThrowing() {
        DecoderConstraint result = renderer.render("not valid json", StructuredOutputMode.RESPONSE_FORMAT_JSON_SCHEMA);

        assertThat(result.responseFormat()).isNull();
        assertThat(result.jsonSchema()).isNull();
    }

    // ---- resolveMode: PMR-22-style fromNullable, never Enum.valueOf ----

    @Test
    void resolveModePrefersTheBackendOverride() {
        StructuredOutputMode resolved = renderer.resolveMode("TOP_LEVEL_JSON_SCHEMA", "OFF");

        assertThat(resolved).isEqualTo(StructuredOutputMode.TOP_LEVEL_JSON_SCHEMA);
    }

    @Test
    void resolveModeFallsBackToDefaultWhenBackendOverrideIsNullOrBlank() {
        assertThat(renderer.resolveMode(null, "RESPONSE_FORMAT_SCHEMA")).isEqualTo(StructuredOutputMode.RESPONSE_FORMAT_SCHEMA);
        assertThat(renderer.resolveMode("  ", "RESPONSE_FORMAT_SCHEMA")).isEqualTo(StructuredOutputMode.RESPONSE_FORMAT_SCHEMA);
    }

    @Test
    void resolveModeFallsBackToDefaultForAnUnrecognizedBackendOverride() {
        StructuredOutputMode resolved = renderer.resolveMode("NOT_A_REAL_MODE", "RESPONSE_FORMAT_SCHEMA");

        assertThat(resolved).isEqualTo(StructuredOutputMode.RESPONSE_FORMAT_SCHEMA);
    }

    @Test
    void resolveModeDegradesToOffWhenNeitherBackendNorDefaultIsRecognized() {
        StructuredOutputMode resolved = renderer.resolveMode("garbage", "also garbage");

        assertThat(resolved).isEqualTo(StructuredOutputMode.OFF);
    }
}
