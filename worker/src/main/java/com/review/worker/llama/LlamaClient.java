package com.review.worker.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.error.LlamaException;
import com.review.worker.llama.dto.ChatCompletionRequest;
import com.review.worker.llama.dto.ChatCompletionResponse;
import com.review.worker.llama.dto.ChatMessage;
import com.review.worker.llama.dto.Choice;
import com.review.worker.llama.dto.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The Worker's sole channel to the local llama-server: a single OpenAI-compatible
 * {@code POST /v1/chat/completions} call per job. Never retries (retry logic lives only in the Gateway,
 * per the architecture's non-negotiable principles) and never logs the diff or the raw completion body
 * (WSR-10) — only sizes/durations/token counts.
 */
@Component
public class LlamaClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaClient.class);

    private final RestClient llamaRestClient;
    private final ObjectMapper objectMapper;
    private final long maxResponseBytes;

    public LlamaClient(@Qualifier("llamaRestClient") RestClient llamaRestClient,
                        ObjectMapper objectMapper,
                        WorkerProperties properties) {
        this.llamaRestClient = llamaRestClient;
        this.objectMapper = objectMapper;
        this.maxResponseBytes = properties.getWorker().getLimits().getMaxResponseBytes();
    }

    /**
     * @throws LlamaException on a 5xx/unexpected status, a connection failure, a malformed/empty body,
     *                         or a body exceeding {@code worker.limits.max-response-bytes} (WSR-04/05 —
     *                         the caller must treat this the same as any other {@code LlamaException}:
     *                         abandon the job, never submit a synthetic/partial result).
     */
    public LlamaResult chatCompletion(List<ChatMessage> messages, String model, double temperature, int maxTokens) {
        ChatCompletionRequest request = new ChatCompletionRequest(model, messages, temperature, maxTokens);
        long startedAt = System.currentTimeMillis();
        ChatCompletionResponse response;
        try {
            response = llamaRestClient.post()
                    .uri("/v1/chat/completions")
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new LlamaException("llama-server returned status " + status.value());
                        }
                        try (InputStream bounded = new BoundedInputStream(clientResponse.getBody(), maxResponseBytes)) {
                            return objectMapper.readValue(bounded, ChatCompletionResponse.class);
                        } catch (BoundedInputStream.ResponseTooLargeException e) {
                            throw new LlamaException(
                                    "llama-server response exceeded " + maxResponseBytes + " bytes -- abandoning job", e);
                        } catch (IOException e) {
                            throw new LlamaException("Could not parse llama-server response", e);
                        }
                    });
        } catch (ResourceAccessException e) {
            throw new LlamaException("Could not reach llama-server", e);
        }
        long durationMs = System.currentTimeMillis() - startedAt;
        return toResult(response, model, durationMs);
    }

    private LlamaResult toResult(ChatCompletionResponse response, String requestedModel, long durationMs) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlamaException("llama-server response had no choices");
        }
        Choice firstChoice = response.choices().get(0);
        if (firstChoice.message() == null || firstChoice.message().content() == null
                || firstChoice.message().content().isEmpty()) {
            throw new LlamaException("llama-server response choice had no message content");
        }
        Usage usage = response.usage();
        Integer promptTokens = usage != null ? usage.promptTokens() : null;
        Integer completionTokens = usage != null ? usage.completionTokens() : null;
        log.info("llama-server completion received (durationMs={}, promptTokens={}, completionTokens={})",
                durationMs, promptTokens, completionTokens);
        return new LlamaResult(firstChoice.message().content(), promptTokens, completionTokens, durationMs, requestedModel);
    }
}
