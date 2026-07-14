package com.review.worker.llama.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** {@code POST /v1/chat/completions} request body (OpenAI-compatible llama-server API). */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens) {
}
