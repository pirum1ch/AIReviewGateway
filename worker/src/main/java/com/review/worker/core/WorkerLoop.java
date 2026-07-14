package com.review.worker.core;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.error.GatewayUnavailableException;
import com.review.worker.error.LlamaException;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.ResultOutcome;
import com.review.worker.gateway.dto.ClaimResponse;
import com.review.worker.gateway.dto.ResultRequest;
import com.review.worker.llama.LlamaClient;
import com.review.worker.metrics.WorkerMetrics;
import com.review.worker.prompt.PromptTemplateService;
import com.review.worker.prompt.ResolvedPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single job-loop thread (architecture §5/§6): claim → resolve prompt → start heartbeat → call
 * llama-server (async, cancellable) → submit result → loop. Capacity is structurally 1 (a single plain
 * thread, never a pool) matching the backend's {@code parallel:1} assumption (architecture §2).
 *
 * <p>This class owns no Spring-managed lifecycle by itself — {@code lifecycle.WorkerRunner} starts it and
 * {@code lifecycle.GracefulShutdown} stops it — so it can be constructed and driven directly in tests
 * without booting a Spring context.
 */
@Component
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final String THREAD_NAME = "worker-loop";

    private final GatewayClient gatewayClient;
    private final LlamaClient llamaClient;
    private final PromptTemplateService promptTemplateService;
    private final HeartbeatScheduler heartbeatScheduler;
    private final WorkerMetrics metrics;
    private final WorkerProperties properties;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile AbortSignal currentAbortSignal;

    public WorkerLoop(GatewayClient gatewayClient,
                       LlamaClient llamaClient,
                       PromptTemplateService promptTemplateService,
                       HeartbeatScheduler heartbeatScheduler,
                       WorkerMetrics metrics,
                       WorkerProperties properties) {
        this.gatewayClient = gatewayClient;
        this.llamaClient = llamaClient;
        this.promptTemplateService = promptTemplateService;
        this.heartbeatScheduler = heartbeatScheduler;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** Starts the {@code worker-loop} thread. A no-op if already started. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        shuttingDown.set(false);
        running.set(true);
        thread = new Thread(this::runLoop, THREAD_NAME);
        thread.setDaemon(false);
        thread.start();
    }

    /**
     * Signals the loop to stop claiming new jobs once it next checks. Deliberately does <em>not</em>
     * interrupt the thread: architecture §9 requires a near-done generation to be allowed to finish
     * within the grace window, and interrupting unconditionally here would abort an in-flight llama call
     * or result-redelivery attempt that was about to succeed on its own. A poll-sleep between jobs is
     * bounded by {@code pollIntervalMs} anyway, so shutdown is noticed promptly without needing to
     * interrupt; {@link #abandonCurrentJob()} is the (later, explicit) point where interrupting becomes
     * appropriate.
     */
    public void requestShutdown() {
        shuttingDown.set(true);
    }

    /** Blocks up to {@code timeout} for the loop thread to exit. Returns {@code true} if it did. */
    public boolean awaitTermination(Duration timeout) {
        Thread t = thread;
        if (t == null) {
            return true;
        }
        try {
            t.join(Math.max(timeout.toMillis(), 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /**
     * Forces the currently in-flight job (if any) to abort: used by {@code GracefulShutdown} once the
     * shutdown grace period has genuinely elapsed with a job still running. Unlike
     * {@link #requestShutdown()}, this deliberately also interrupts the loop thread directly -- by this
     * point waiting further is no longer appropriate, so the interrupt is needed both to cancel the
     * llama future (belt-and-suspenders alongside {@link AbortSignal#abort()}) and to wake up a blocked
     * {@code claim} call or a result-redelivery backoff sleep. Safe to call when no job is in flight.
     */
    public void abandonCurrentJob() {
        AbortSignal signal = currentAbortSignal;
        if (signal != null) {
            log.warn("Forcing abandonment of the in-flight job (shutdown grace period elapsed)");
            signal.abort();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runLoop() {
        long backoffMs = 0;
        try {
            while (!shuttingDown.get()) {
                Optional<ClaimResponse> claimed;
                try {
                    claimed = gatewayClient.claim(properties.getBackend().getId(), properties.getWorker().getId());
                    backoffMs = 0;
                } catch (GatewayUnavailableException e) {
                    metrics.incrementGatewayErrors();
                    backoffMs = nextBackoff(backoffMs);
                    log.warn("Gateway unavailable while claiming a job; backing off {} ms", backoffMs, e);
                    sleepInterruptibly(backoffMs);
                    continue;
                }

                if (shuttingDown.get()) {
                    break;
                }

                if (claimed.isEmpty()) {
                    sleepInterruptibly(properties.getNetwork().getPollIntervalMs());
                    continue;
                }

                processJob(claimed.get());
            }
        } finally {
            running.set(false);
            log.info("worker-loop thread exiting");
        }
    }

    private long nextBackoff(long previousMs) {
        long base = Math.max(properties.getNetwork().getPollIntervalMs(), 1);
        long next = previousMs <= 0 ? base : previousMs * 2;
        return Math.min(next, MAX_BACKOFF_MS);
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(Math.max(millis, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processJob(ClaimResponse job) {
        metrics.incrementJobsTotal();
        String workerId = properties.getWorker().getId();
        AbortSignal abortSignal = new AbortSignal();
        currentAbortSignal = abortSignal;
        try {
            ResolvedPrompt prompt = promptTemplateService.resolve(job.payload().promptVersion(), job.payload().diff());

            heartbeatScheduler.start(job.jobId(), workerId, abortSignal);
            try {
                runInference(job, workerId, prompt, abortSignal);
            } finally {
                heartbeatScheduler.stop();
            }
        } catch (AbandonJobException | LlamaException e) {
            log.warn("Job abandoned (jobId={}): {}", job.jobId(), e.getMessage());
            metrics.incrementJobsFailed();
        } finally {
            currentAbortSignal = null;
        }
    }

    private void runInference(ClaimResponse job, String workerId, ResolvedPrompt prompt, AbortSignal abortSignal) {
        LlamaClient.AsyncCompletion call = llamaClient.startChatCompletion(
                prompt.messages(), prompt.model(), prompt.temperature(), prompt.maxTokens());
        abortSignal.attach(call.future());

        HttpResponse<InputStream> httpResponse = awaitLlamaResponse(call, abortSignal);

        if (abortSignal.isAborted() || httpResponse == null) {
            // D6/§6: the job was cancelled/superseded mid-generation -- submit nothing, no metric either
            // way (this is neither a Worker-side completion nor a Worker-side failure).
            log.info("Job aborted before/while awaiting llama response; submitting nothing (jobId={})", job.jobId());
            return;
        }

        long durationMs = System.currentTimeMillis() - call.startedAtMillis();
        LlamaResult result = llamaClient.parseResponse(httpResponse, prompt.model(), durationMs);
        metrics.recordLlamaDuration(Duration.ofMillis(result.durationMs()));

        if (abortSignal.isAborted()) {
            log.info("Job aborted after the llama response arrived; discarding it (jobId={})", job.jobId());
            return;
        }

        submitResultWithRedelivery(job.jobId(), workerId, result);
        metrics.incrementJobsCompleted();
    }

    /**
     * Awaits the llama response, bounded by {@code requestTimeoutSec}. Returns {@code null} if the call
     * was aborted (the abort race is inherently ambiguous: depending on exactly when
     * {@code future.cancel(true)} lands relative to the JDK {@code HttpClient}'s internal exchange state,
     * a cancelled call can surface as a {@link CancellationException}, an {@link ExecutionException}, or
     * (rarely) a {@link TimeoutException} that happens to race with the cancellation — so
     * {@link AbortSignal#isAborted()} is checked in <em>every</em> catch branch and takes precedence over
     * classifying the failure: an aborted job is never counted as a Worker-side failure, only a genuine,
     * non-aborted llama error is.
     */
    private HttpResponse<InputStream> awaitLlamaResponse(LlamaClient.AsyncCompletion call, AbortSignal abortSignal) {
        try {
            return call.future().get(properties.getNetwork().getRequestTimeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            call.future().cancel(true);
            if (abortSignal.isAborted()) {
                return null;
            }
            throw new LlamaException("llama-server did not respond within requestTimeoutSec", e);
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            if (abortSignal.isAborted()) {
                return null;
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new LlamaException("llama-server call failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.future().cancel(true);
            if (abortSignal.isAborted()) {
                return null;
            }
            throw new LlamaException("Interrupted while awaiting llama-server response", e);
        }
    }

    /**
     * Transport-level redelivery of an already-computed, idempotent result (architecture §7): retries
     * with capped exponential backoff until the Gateway accepts it, rejects it as not-owned/not-found
     * (both terminal from the Worker's perspective), or this thread is interrupted (shutdown abandoning
     * it). Never re-invokes the LLM -- this is not business retry.
     */
    private void submitResultWithRedelivery(long jobId, String workerId, LlamaResult result) {
        ResultRequest request = new ResultRequest(workerId, result.rawResponse(), result.promptTokens(),
                result.completionTokens(), result.durationMs(), result.model());
        long backoffMs = 0;
        while (true) {
            try {
                ResultOutcome outcome = gatewayClient.submitResult(jobId, request);
                log.info("Result delivered (jobId={}, status={})", jobId, outcome.status());
                return;
            } catch (GatewayUnavailableException e) {
                metrics.incrementGatewayErrors();
                backoffMs = nextBackoff(backoffMs);
                log.warn("Gateway unavailable while submitting result; retrying in {} ms (jobId={})",
                        backoffMs, jobId, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while redelivering result; giving up (jobId={})", jobId);
                    return;
                }
            }
        }
    }
}
