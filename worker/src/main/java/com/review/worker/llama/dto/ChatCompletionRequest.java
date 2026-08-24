package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code POST /v1/chat/completions} request body (OpenAI-compatible llama-server API).
 *
 * <p>Structured Review Output (architecture §3.3/§9.3.6, threat model SOR-07 CRITICAL):
 * {@code responseFormat}/{@code jsonSchema} are the Gateway-computed decoder constraint, forwarded
 * <b>verbatim</b> — {@code @JsonInclude(NON_NULL)} means today's four-field request body is byte-for-
 * byte unchanged whenever both are {@code null} (backend {@code OFF}, kill switch, or a non-structured
 * {@code promptVersion}). Deliberately <b>typed {@code JsonNode} fields</b>, never a generic
 * {@code Map<String, Object>} request-body overlay (§9.3.6's rejected alternative) — this is what
 * bounds a compromised/buggy Gateway to influencing only output <i>shape</i> via these two fixed wire
 * destinations, never arbitrary llama.cpp sampling parameters.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("response_format") JsonNode responseFormat,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("json_schema") JsonNode jsonSchema) {

    /** Legacy four-field shape (no decoder constraint) — every existing call site stays unchanged. */
    public ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, null, null);
    }
}
