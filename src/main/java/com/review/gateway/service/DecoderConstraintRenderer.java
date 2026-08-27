package com.review.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.gateway.model.enums.StructuredOutputMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Transport-adaptation half of Structured Review Output (architecture §3.1/§3.2, SRO-05/06): wraps a
 * {@link ReviewSchemaBuilder}-produced JSON Schema in the wire shape a specific llama-server build
 * expects. Exactly the {@code PromptMessageFormatter}/{@code prompt_message_format} precedent (PMR-22),
 * applied to a second per-backend LLM quirk.
 *
 * <p>{@link #render} always returns a <b>sealed two-shape result</b> (SRO-13): at most one of {@code
 * responseFormat}/{@code jsonSchema} is ever non-null, both null for {@link StructuredOutputMode#OFF} or
 * an unparseable schema. This is what lets the Worker's own defensive mutual-exclusivity re-check
 * (WSR-03-style) exist purely as defense in depth — the Gateway already guarantees the invariant by
 * construction.
 *
 * <p><b>SOR-05a discipline extended here too:</b> the already-fully-formed schema JSON is re-parsed into
 * a {@link JsonNode} and embedded <em>structurally</em> into the wrapper via Jackson tree construction
 * (never string concatenation) — even though the schema text is Gateway-generated (not raw attacker
 * input at this point), keeping every JSON-assembly step in this feature on the same disciplined path
 * avoids relitigating the string-templating question at each new call site.
 */
@Service
public class DecoderConstraintRenderer {

    private static final Logger log = LoggerFactory.getLogger(DecoderConstraintRenderer.class);

    private static final String SCHEMA_NAME = "code_review";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Sealed two-shape result (SRO-13): at most one field non-null, or both null ("no constraint"). */
    public record DecoderConstraint(String responseFormat, String jsonSchema) {

        public static DecoderConstraint none() {
            return new DecoderConstraint(null, null);
        }
    }

    /**
     * PMR-22-style resolution: a non-blank, recognized {@code backendModeRaw} wins; otherwise falls back
     * to {@code defaultModeRaw} (itself parsed the same defensive way); otherwise {@link
     * StructuredOutputMode#OFF}. Never throws.
     */
    public StructuredOutputMode resolveMode(String backendModeRaw, String defaultModeRaw) {
        if (backendModeRaw != null && !backendModeRaw.isBlank()) {
            var parsed = StructuredOutputMode.fromNullable(backendModeRaw);
            if (parsed.isPresent()) {
                return parsed.get();
            }
            log.warn("Unrecognized backends.structured_output_mode (length={}) -- falling back to the "
                    + "configured default", backendModeRaw.length());
        }
        return StructuredOutputMode.fromNullable(defaultModeRaw).orElse(StructuredOutputMode.OFF);
    }

    /**
     * @param schemaJson the {@link ReviewSchemaBuilder}-produced schema text; ignored (result is
     *                    {@link DecoderConstraint#none()}) when {@code mode} is {@code null}/{@code OFF}
     * @param mode        the resolved wire shape (see {@link #resolveMode})
     */
    public DecoderConstraint render(String schemaJson, StructuredOutputMode mode) {
        if (mode == null || mode == StructuredOutputMode.OFF) {
            return DecoderConstraint.none();
        }
        if (mode == StructuredOutputMode.TOP_LEVEL_JSON_SCHEMA) {
            // The bare schema text itself is the wire value -- no wrapper, no re-serialization needed.
            return new DecoderConstraint(null, schemaJson);
        }

        JsonNode schemaNode;
        try {
            schemaNode = objectMapper.readTree(schemaJson);
        } catch (JsonProcessingException malformed) {
            // Should be unreachable: schemaJson is always this Gateway's own ReviewSchemaBuilder output.
            // Never OFF's the whole Review though -- the caller (QueueManager) already committed to
            // building a constraint; falling back to "no constraint" here degrades to unconstrained
            // decoding (still validated unconditionally, SRO-04) rather than failing the claim.
            log.warn("Failed to re-parse a Gateway-generated schema while rendering the decoder constraint "
                    + "({}) -- degrading to no constraint for this job", malformed.getClass().getSimpleName());
            return DecoderConstraint.none();
        }

        return switch (mode) {
            case RESPONSE_FORMAT_JSON_SCHEMA -> new DecoderConstraint(renderResponseFormatJsonSchema(schemaNode), null);
            case RESPONSE_FORMAT_SCHEMA -> new DecoderConstraint(renderResponseFormatSchema(schemaNode), null);
            case OFF, TOP_LEVEL_JSON_SCHEMA -> DecoderConstraint.none(); // unreachable, handled above
        };
    }

    private String renderResponseFormatJsonSchema(JsonNode schemaNode) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "json_schema");
        ObjectNode jsonSchema = root.putObject("json_schema");
        jsonSchema.put("name", SCHEMA_NAME);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", schemaNode);
        return writeOrNull(root);
    }

    private String renderResponseFormatSchema(JsonNode schemaNode) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "json_object");
        root.set("schema", schemaNode);
        return writeOrNull(root);
    }

    private String writeOrNull(ObjectNode root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            // A tree built exclusively from ObjectNode/textual/boolean values and an already-parsed
            // JsonNode can never fail to serialize.
            throw new IllegalStateException("Unexpected failure serializing a well-formed constraint tree", impossible);
        }
    }
}
