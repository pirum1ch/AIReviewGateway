package com.review.worker.llama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.worker.config.WorkerProperties;
import com.review.worker.core.LlamaResult;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The Worker's sole channel to the local llama-server: a single OpenAI-compatible
 * {@code POST /v1/chat/completions} call per job. Never retries (retry logic lives only in the Gateway,
 * per the architecture's non-negotiable principles) and never logs the diff or the raw completion body
 * (WSR-10) — only sizes/durations/token counts.
 *
 * <p>Offers two call shapes:
 * <ul>
 *   <li>{@link #chatCompletion} — synchronous, built on the shared {@code llamaRestClient}. Simple,
 *       blocking; used where cancellation is not needed.</li>
 *   <li>{@link #startChatCompletion} — asynchronous, built directly on the single shared
 *       {@link HttpClient} bean (architecture §5). Returns the <em>raw</em>
 *       {@code CompletableFuture<HttpResponse<InputStream>>} from {@code HttpClient.sendAsync}
 *       untouched (no {@code thenApply} chaining) specifically so that cancelling it
 *       ({@code future.cancel(true)}) tears down the underlying HTTP exchange promptly instead of
 *       waiting out the full read timeout — this is what {@code WorkerLoop}/{@code AbortSignal} rely on
 *       to interrupt a llama call mid-generation on {@code shouldContinue:false}/{@code 403}/{@code 404}.
 *       Call {@link #parseResponse} once the future completes to get the {@link LlamaResult}.</li>
 * </ul>
 */
@Component
public class LlamaClient {

    private static final Logger log = LoggerFactory.getLogger(LlamaClient.class);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final RestClient llamaRestClient;
    private final HttpClient sharedHttpClient;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;

    public LlamaClient(@Qualifier("llamaRestClient") RestClient llamaRestClient,
                        HttpClient sharedHttpClient,
                        ObjectMapper objectMapper,
                        WorkerProperties properties) {
        this.llamaRestClient = llamaRestClient;
        this.sharedHttpClient = sharedHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        HttpStatusCode status = clientResponse.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new LlamaException("llama-server returned status " + status.value());
                        }
                        try {
                            return readBounded(clientResponse.getBody());
                        } catch (BoundedInputStream.ResponseTooLargeException e) {
                            throw oversizeException(e);
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

    /**
     * Issues the chat-completion request asynchronously via the shared {@link HttpClient} and returns
     * immediately with a handle on the in-flight exchange. The caller is responsible for awaiting
     * {@link AsyncCompletion#future()} (with its own timeout) and then calling {@link #parseResponse} on
     * the result; cancelling {@link AsyncCompletion#future()} aborts the underlying HTTP exchange.
     *
     * @throws LlamaException if the request body cannot be serialized (should not happen for a
     *                         validated {@link ChatMessage} list, but never assume).
     */
    public AsyncCompletion startChatCompletion(List<ChatMessage> messages, String model, double temperature,
                                                int maxTokens) {
        ChatCompletionRequest requestBody = new ChatCompletionRequest(model, messages, temperature, maxTokens);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requestBody);
        } catch (IOException e) {
            throw new LlamaException("Could not serialize llama-server request", e);
        }
        int requestTimeoutSec = properties.getNetwork().getRequestTimeoutSec();
        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri())
                .timeout(Duration.ofSeconds(requestTimeoutSec))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        long startedAt = System.currentTimeMillis();
        CompletableFuture<HttpResponse<InputStream>> future =
                sharedHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        return new AsyncCompletion(future, startedAt);
    }

    /**
     * Parses a completed {@link AsyncCompletion} response. Kept as a separate step from
     * {@link #startChatCompletion} so the caller (WorkerLoop) can attach the raw future to an
     * {@code AbortSignal} the moment it exists, before any parsing work begins.
     *
     * @throws LlamaException on a non-2xx status, a malformed/empty body, or an oversized body
     *                         (WSR-04/05).
     */
    public LlamaResult parseResponse(HttpResponse<InputStream> response, String requestedModel, long durationMs) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new LlamaException("llama-server returned status " + status);
        }
        ChatCompletionResponse parsed;
        try {
            parsed = readBounded(response.body());
        } catch (BoundedInputStream.ResponseTooLargeException e) {
            throw oversizeException(e);
        } catch (IOException e) {
            throw new LlamaException("Could not parse llama-server response", e);
        }
        return toResult(parsed, requestedModel, durationMs);
    }

    private ChatCompletionResponse readBounded(InputStream rawBody) throws IOException {
        long maxResponseBytes = properties.getWorker().getLimits().getMaxResponseBytes();
        try (InputStream bounded = new BoundedInputStream(rawBody, maxResponseBytes)) {
            return objectMapper.readValue(bounded, ChatCompletionResponse.class);
        }
    }

    private LlamaException oversizeException(BoundedInputStream.ResponseTooLargeException cause) {
        long maxResponseBytes = properties.getWorker().getLimits().getMaxResponseBytes();
        return new LlamaException(
                "llama-server response exceeded " + maxResponseBytes + " bytes -- abandoning job", cause);
    }

    private URI chatCompletionsUri() {
        String base = properties.getLlama().getUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + CHAT_COMPLETIONS_PATH);
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

    /**
     * Handle on an in-flight asynchronous chat-completion call: the raw, directly-cancellable future from
     * {@code HttpClient.sendAsync} plus the wall-clock start time (for {@code durationMs} accounting).
     */
    public record AsyncCompletion(CompletableFuture<HttpResponse<InputStream>> future, long startedAtMillis) {
    }
}
