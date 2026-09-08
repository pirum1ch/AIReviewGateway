package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GitLab Webhook Diff Trigger — hourly backstop against GitLab silently failing to deliver (or an
 * operator disabling) the webhook (plan §"Подстраховка", threat model WHR-23). Mirrors {@link
 * PublishRetryService}/{@link BackendHealthChecker}'s shape: a scheduled-job driver that selects
 * candidates and delegates to the same orchestrator the webhook itself uses ({@link
 * WebhookReviewTriggerService}), so the two trigger paths can never diverge in behavior (plan
 * §"Триггер").
 *
 * <p><b>WHR-23 (bounded per tick):</b> {@code gateway.webhook.sweep.max-mrs-per-tick} caps how many
 * candidate MRs one tick processes, {@code updated-within-days} bounds the org-wide listing query, and
 * {@code max-pages} bounds pagination — all enforced by {@link
 * GitLabClient#listOpenMergeRequestsForReviewer} itself. A cheap {@code
 * existsByProjectIdAndMergeRequestIdAndHeadSha} pre-check runs before any diff fetch (the sweep listing's
 * {@code sha} is an UNTRUSTED hint, same discipline as WHR-05).
 *
 * <p><b>WHR-24 (diagnostic-comment budget):</b> {@code gateway.webhook.sweep.max-diagnostic-comments-per-tick}
 * caps how many integrity failures in one tick are allowed to post a courtesy MR comment; the
 * authoritative log line + metric ({@link WebhookReviewTriggerService}) always fire regardless.
 *
 * <p><b>WHT-17 (no overlapping ticks):</b> an {@link AtomicBoolean} guard skips (never queues) a tick
 * that starts while the previous one is still running, exactly like {@link BackendHealthChecker}'s
 * {@code passInProgress} pattern.
 */
@Service
public class ReviewerSweepService {

    private static final Logger log = LoggerFactory.getLogger(ReviewerSweepService.class);

    private final GatewayProperties properties;
    private final GitLabClient gitLabClient;
    private final ReviewRepository reviewRepository;
    private final WebhookReviewTriggerService triggerService;

    private final AtomicBoolean sweepInProgress = new AtomicBoolean(false);

    public ReviewerSweepService(GatewayProperties properties, GitLabClient gitLabClient,
                                 ReviewRepository reviewRepository, WebhookReviewTriggerService triggerService) {
        this.properties = properties;
        this.gitLabClient = gitLabClient;
        this.reviewRepository = reviewRepository;
        this.triggerService = triggerService;
    }

    /** @return the number of candidate MRs this pass attempted to trigger (0 if disabled, saturated, or none found) */
    public int sweep() {
        GatewayProperties.Webhook webhook = properties.getWebhook();
        if (!webhook.isEnabled()) {
            return 0;
        }
        if (!sweepInProgress.compareAndSet(false, true)) {
            log.warn("Reviewer sweep tick skipped: previous tick is still in progress");
            return 0;
        }
        try {
            return runSweep(webhook);
        } finally {
            sweepInProgress.set(false);
        }
    }

    private int runSweep(GatewayProperties.Webhook webhook) {
        GatewayProperties.Webhook.Sweep sweepConfig = webhook.getSweep();
        Instant updatedAfter = Instant.now().minus(java.time.Duration.ofDays(sweepConfig.getUpdatedWithinDays()));

        List<GitLabClient.MergeRequestRef> candidates;
        try {
            candidates = gitLabClient.listOpenMergeRequestsForReviewer(
                    webhook.getBotUsername(), updatedAfter, sweepConfig.getMaxPages(), sweepConfig.getMaxMrsPerTick());
        } catch (DiffFetchUnavailableException transientFailure) {
            log.warn("Reviewer sweep: failed to list candidate merge requests: {}", transientFailure.getMessage());
            return 0;
        }

        // WHR-24: caps how many integrity failures in this tick may post a courtesy MR comment; the
        // authoritative log line + metric in WebhookReviewTriggerService always fire regardless.
        AtomicInteger diagnosticCommentBudget = new AtomicInteger(sweepConfig.getMaxDiagnosticCommentsPerTick());
        int attempted = 0;
        for (GitLabClient.MergeRequestRef candidate : candidates) {
            // WHR-23: cheap dedup pre-check before any diff fetch (the listing's sha is an untrusted hint).
            if (candidate.sha() != null && !candidate.sha().isBlank()
                    && reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(
                            candidate.projectId(), candidate.iid(), candidate.sha())) {
                continue;
            }
            boolean allowComment = diagnosticCommentBudget.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
            triggerService.handle(candidate.projectId(), candidate.iid(), candidate.sha(), allowComment);
            attempted++;
        }
        if (attempted > 0) {
            log.info("Reviewer sweep tick: {} candidate merge request(s) attempted out of {} listed",
                    attempted, candidates.size());
        }
        return attempted;
    }
}
