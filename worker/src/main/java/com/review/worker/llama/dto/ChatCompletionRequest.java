package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/chat/completions} request body (OpenAI-compatible llama-server API).
 *
 * <p>{@code chat_template_kwargs.enable_thinking=false} is always sent: a "thinking" model (e.g. a
 * Qwen3-family reasoning/coder build) otherwise spends part of {@code max_tokens} on a hidden
 * {@code reasoning_content} block before the actual answer, which can exhaust the whole budget on a
 * large diff and leave {@code message.content} empty (surfaces as {@code LLM_EMPTY_RESPONSE} even though
 * the backend is healthy). The Worker only ever reads {@code message.content}, so the reasoning trace has
 * no consumer anyway. llama-server ignores the field for chat templates that don't support it.
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("chat_template_kwargs") Map<String, Object> chatTemplateKwargs) {

    public ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature, int maxTokens) {
        this(model, messages, temperature, maxTokens, Map.of("enable_thinking", false));
    }
}
