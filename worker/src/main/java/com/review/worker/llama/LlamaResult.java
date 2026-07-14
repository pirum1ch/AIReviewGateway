package com.review.worker.llama;

/**
 * Outcome of a successful llama-server chat completion, ready to be forwarded to the Gateway as a
 * {@code ResultRequest}.
 *
 * <p>Deliberately placed in {@code llama/} rather than {@code core/} as architecture §11 literally shows:
 * {@code core/} (the {@code WorkerLoop}/job-lifecycle orchestration package) is explicitly out of scope
 * for this branch (§14 — Branch 2), so this type lives alongside the client that produces it instead of
 * a package that must not be created here. It can be moved to {@code core/} in Branch 2 without changing
 * its shape.
 */
public record LlamaResult(
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        long durationMs,
        String model) {
}
