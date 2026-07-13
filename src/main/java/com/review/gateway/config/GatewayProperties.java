package com.review.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Typed {@code gateway.*} configuration surface consumed by the core services
 * (feature/02-core-services). Only the keys needed by this stage are present: diff-size budget,
 * heartbeat timeout, retry attempts, max job duration, and publish/parse caps. The full surface
 * (GitLab/backend/security sub-trees, scheduler intervals) is added in feature/03-api-security —
 * this class is extended there, not replaced, so field names here are considered stable.
 *
 * <p>Registered directly via {@code @Component} (rather than {@code @EnableConfigurationProperties}
 * on a yet-to-exist {@code @Configuration} class) so it is available for constructor injection into
 * services immediately; feature/03 may add JSR-380 validation annotations without needing to change
 * how this bean is discovered.
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Diff diff = new Diff();
    private final Heartbeat heartbeat = new Heartbeat();
    private final Retry retry = new Retry();
    private final Job job = new Job();
    private final Publish publish = new Publish();

    public Diff getDiff() {
        return diff;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Retry getRetry() {
        return retry;
    }

    public Job getJob() {
        return job;
    }

    public Publish getPublish() {
        return publish;
    }

    /** Diff-size token-budget heuristic (§9 {@code gateway.diff.*}). */
    public static class Diff {
        /** Total LLM context window, in tokens. */
        private int contextWindow = 16384;
        /** Tokens reserved for the fixed prompt scaffolding. */
        private int promptReserve = 2000;
        /** Tokens reserved for the model's answer. */
        private int answerReserve = 4000;
        /** Explicit derived cap; the enforced budget is {@code min(this, contextWindow - reserves)}. */
        private int maxDiffTokens = 10000;
        /** Heuristic characters-per-token ratio used to estimate diff size without a real tokenizer. */
        private int charsPerToken = 4;

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
        }

        public int getPromptReserve() {
            return promptReserve;
        }

        public void setPromptReserve(int promptReserve) {
            this.promptReserve = promptReserve;
        }

        public int getAnswerReserve() {
            return answerReserve;
        }

        public void setAnswerReserve(int answerReserve) {
            this.answerReserve = answerReserve;
        }

        public int getMaxDiffTokens() {
            return maxDiffTokens;
        }

        public void setMaxDiffTokens(int maxDiffTokens) {
            this.maxDiffTokens = maxDiffTokens;
        }

        public int getCharsPerToken() {
            return charsPerToken;
        }

        public void setCharsPerToken(int charsPerToken) {
            this.charsPerToken = charsPerToken;
        }
    }

    /** Worker heartbeat liveness window (§9 {@code gateway.heartbeat.*}). */
    public static class Heartbeat {
        /** A RUNNING job is stale if {@code now - heartbeat_at} exceeds this. */
        private Duration timeout = Duration.ofSeconds(180);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Retry limits (§9 {@code gateway.retry.*}). */
    public static class Retry {
        private int maxAttempts = 3;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /** Hard job-duration backstop (§9 {@code gateway.job.*}). */
    public static class Job {
        private Duration maxDuration = Duration.ofMinutes(45);

        public Duration getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
        }
    }

    /** Parse/publish caps (SR-08/SR-09/SR-21). */
    public static class Publish {
        /** Max number of parsed comments kept per Review; excess is dropped. */
        private int maxCommentCount = 50;
        /** Max characters kept per parsed comment; excess is truncated. */
        private int maxCommentLength = 4000;
        /** Max characters accepted for a raw LLM response at {@code /jobs/{id}/result} (SR-21). */
        private int maxRawResponseLength = 200_000;

        public int getMaxCommentCount() {
            return maxCommentCount;
        }

        public void setMaxCommentCount(int maxCommentCount) {
            this.maxCommentCount = maxCommentCount;
        }

        public int getMaxCommentLength() {
            return maxCommentLength;
        }

        public void setMaxCommentLength(int maxCommentLength) {
            this.maxCommentLength = maxCommentLength;
        }

        public int getMaxRawResponseLength() {
            return maxRawResponseLength;
        }

        public void setMaxRawResponseLength(int maxRawResponseLength) {
            this.maxRawResponseLength = maxRawResponseLength;
        }
    }
}
