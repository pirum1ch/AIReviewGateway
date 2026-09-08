package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.exception.DiffIntegrityException;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * GitLab Webhook Diff Trigger — the single orchestrator shared by {@code WebhookController} and {@code
 * ReviewerSweepService} (plan §"Триггер": "гарантия, что оба пути не разойдутся в поведении"): fetch
 * ({@link GitLabClient}) → verify ({@link DiffIntegrityVerifier}) → assemble ({@link DiffAssembler}) →
 * the unchanged {@link ReviewService#createReview}.
 *
 * <p><b>The webhook body is a hint, not a fact (WHT-02/WHR-03).</b> Callers pass only
 * {@code (projectId, mergeRequestIid)} plus an OPTIONAL, untrusted {@code lastCommitHint} — every fact
 * actually acted on (the reviewer set, both SHAs, MR state) is read fresh from
 * {@link GitLabClient#fetchMergeRequest} inside this class, never from the caller.
 *
 * <p><b>Never throws (WHR-07).</b> Every exception this class's own dependencies can raise is caught
 * here — {@code WebhookController} always responds with the same coarse outcome regardless of cause, and
 * {@code ReviewerSweepService} must be able to move on to the next candidate MR in its tick.
 */
@Service
public class WebhookReviewTriggerService {

    private static final Logger log = LoggerFactory.getLogger(WebhookReviewTriggerService.class);

    /** WHR-06/WHR-11: the same shape {@link GitLabClientImpl} pins SHAs to before any URI. */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{7,64}$");
    private static final String OPENED_STATE = "opened";

    private final GatewayProperties properties;
    private final GitLabClient gitLabClient;
    private final DiffIntegrityVerifier diffIntegrityVerifier;
    private final DiffAssembler diffAssembler;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final MetricsCounters metricsCounters;

    /** WHR-25 (SHOULD): bounded concurrency permit, PromptManager's exact pattern — non-blocking tryAcquire. */
    private final Semaphore fetchPermits;
    /** WHR-26 (SHOULD): single-flight guard so the webhook and the sweep never do the same expensive work twice. */
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    /** WHR-22: in-memory (single instance) rate limiting, per project and global. */
    private final ConcurrentHashMap<Long, RateWindow> perProjectWindows = new ConcurrentHashMap<>();
    private final RateWindow globalWindow = new RateWindow();

    public WebhookReviewTriggerService(GatewayProperties properties, GitLabClient gitLabClient,
                                        DiffIntegrityVerifier diffIntegrityVerifier, DiffAssembler diffAssembler,
                                        ReviewService reviewService, ReviewRepository reviewRepository,
                                        MetricsCounters metricsCounters) {
        this.properties = properties;
        this.gitLabClient = gitLabClient;
        this.diffIntegrityVerifier = diffIntegrityVerifier;
        this.diffAssembler = diffAssembler;
        this.reviewService = reviewService;
        this.reviewRepository = reviewRepository;
        this.metricsCounters = metricsCounters;
        this.fetchPermits = new Semaphore(Math.max(1, properties.getWebhook().getMaxConcurrentFetches()));
    }

    /** Convenience overload for the webhook path — the diagnostic-comment budget is never sweep-capped here. */
    public void handle(Long projectId, Long mergeRequestIid, String lastCommitHint) {
        handle(projectId, mergeRequestIid, lastCommitHint, true);
    }

    /**
     * @param lastCommitHint an UNTRUSTED hint (webhook's {@code object_attributes.last_commit.id} or the
     *                       sweep listing's {@code sha}) used only for the WHR-05 cheap pre-fetch dedup
     *                       fast path — never persisted, never becomes the Review's actual {@code
     *                       headSha} (that always comes from the server-fetched {@link
     *                       GitLabClient#fetchMergeRequest}).
     * @param allowDiagnosticComment WHR-24: lets {@code ReviewerSweepService} cap its own per-tick
     *                               diagnostic-comment budget while WHR-28's authoritative log line and
     *                               metric still fire unconditionally.
     */
    public void handle(Long projectId, Long mergeRequestIid, String lastCommitHint, boolean allowDiagnosticComment) {
        if (!properties.getWebhook().isEnabled() || projectId == null || mergeRequestIid == null
                || projectId <= 0 || mergeRequestIid <= 0) {
            return;
        }
        // WHR-09: deploy-time allowlist gate, before ANY GitLab call.
        Set<Long> allowedProjectIds = properties.getWebhook().getAllowedProjectIds();
        if (!allowedProjectIds.isEmpty() && !allowedProjectIds.contains(projectId)) {
            log.info("Webhook trigger ignored: project {} is not in gateway.webhook.allowed-project-ids", projectId);
            return;
        }
        // WHR-05: cheap pre-fetch fast path -- zero GitLab calls when the hint already matches a Review.
        if (lastCommitHint != null && !lastCommitHint.isBlank()
                && reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(projectId, mergeRequestIid, lastCommitHint)) {
            log.debug("Webhook trigger fast-path dedup: project={} mr={} (untrusted hint matched an existing Review)",
                    projectId, mergeRequestIid);
            return;
        }
        if (!tryConsumeRateLimit(projectId)) {
            metricsCounters.incrementWebhookRateLimited();
            log.warn("Webhook trigger suppressed by rate limit: project={} mr={}", projectId, mergeRequestIid);
            return;
        }
        String inFlightKey = projectId + ":" + mergeRequestIid;
        if (inFlight.putIfAbsent(inFlightKey, Boolean.TRUE) != null) {
            log.info("Webhook trigger skipped: already in-flight for project={} mr={}", projectId, mergeRequestIid);
            return;
        }
        try {
            if (!fetchPermits.tryAcquire()) {
                log.warn("Webhook trigger declined: gateway.webhook.max-concurrent-fetches saturated");
                return;
            }
            try {
                doHandle(projectId, mergeRequestIid, allowDiagnosticComment);
            } catch (RuntimeException unexpected) {
                // F-WH-02: doHandle's own two catches (DiffFetchUnavailableException/DiffIntegrityException)
                // are an enumeration, and an enumeration is only ever as complete as its last edit --
                // ReviewService.createReview alone can throw several unrelated types (DiffTooLargeException,
                // PromptTooLargeException, IncompatiblePromptVersionException, StructuredOutputUnsupportedException,
                // a re-thrown DataIntegrityViolationException, ...) for a legitimately-reachable input. This
                // backstop is what actually guarantees WHR-07 ("never throws") rather than the enumeration
                // merely making it likely: class name only, per WHR-29, never the message (which may carry
                // GitLab response detail).
                log.warn("Webhook trigger: unexpected failure handling project={} mr={}: {}",
                        projectId, mergeRequestIid, unexpected.getClass().getSimpleName());
                metricsCounters.incrementWebhookUnexpectedFailure();
            } finally {
                fetchPermits.release();
            }
        } finally {
            inFlight.remove(inFlightKey);
        }
    }

    private void doHandle(Long projectId, Long mergeRequestIid, boolean allowDiagnosticComment) {
        GitLabClient.MergeRequestSnapshot mr;
        try {
            mr = gitLabClient.fetchMergeRequest(projectId, mergeRequestIid);
        } catch (DiffFetchUnavailableException transientFailure) {
            log.warn("Webhook trigger: transient failure fetching merge request project={} mr={}: {}",
                    projectId, mergeRequestIid, transientFailure.getMessage());
            return; // relies on webhook redelivery / hourly sweep backstop
        }

        if (!OPENED_STATE.equals(mr.state())) {
            return; // coarse no-op -- not an open MR
        }
        // WHR-03: the reviewer check runs against THIS response, never the webhook payload.
        if (!mr.reviewerIds().contains(properties.getWebhook().getBotUserId())) {
            return; // coarse no-op -- bot is not currently a reviewer
        }

        String baseSha = mr.baseSha();
        String headSha = mr.headSha();
        // WHR-11: same shape/length bound as reviews.head_sha VARCHAR(64) -- a deterministic reject
        // here, never a 500 at persistence time.
        if (!isValidPersistableSha(baseSha) || !isValidPersistableSha(headSha)) {
            log.warn("Webhook trigger: GitLab returned an unusable sha shape for project={} mr={}", projectId, mergeRequestIid);
            return;
        }

        // WHR-20: base_sha/head_sha are taken from this single diff_refs read and never re-read/recomputed.
        if (reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(projectId, mergeRequestIid, headSha)) {
            return; // authoritative dedup, now that we have the real head_sha
        }

        String promptVersion = properties.getWebhook().getPromptVersion();
        boolean structured = StructuredOutputSupport.isStructured(promptVersion);
        try {
            List<GitLabClient.DiffEntry> coverageBearing =
                    diffIntegrityVerifier.verify(projectId, mergeRequestIid, baseSha, headSha, structured);
            if (coverageBearing.isEmpty()) {
                // F-WH-05: every file was a sound WHR-15 exemption (binary/mode-only/pure-rename) --
                // there is nothing to review, so don't spend a Review row and Worker minutes on an empty
                // diff, coarse no-op consistent with the other "nothing to do" outcomes on this path.
                log.info("Webhook trigger: nothing reviewable (all files exempt) for project={} mr={}",
                        projectId, mergeRequestIid);
                return;
            }
            String assembledDiff = diffAssembler.assemble(coverageBearing);
            CreateReviewCommand command = new CreateReviewCommand(
                    projectId, mergeRequestIid, headSha, baseSha, assembledDiff, promptVersion, null);
            CreateReviewResult result = reviewService.createReview(command);
            log.info("Webhook-triggered review: project={} mr={} reviewId={} deduplicated={}",
                    projectId, mergeRequestIid, result.reviewId(), result.deduplicated());
        } catch (DiffFetchUnavailableException transientFailure) {
            log.warn("Webhook trigger: transient failure fetching diff for project={} mr={}: {}",
                    projectId, mergeRequestIid, transientFailure.getMessage());
            // architecture decision #2: transient failures rely on redelivery/sweep, never a comment.
        } catch (DiffIntegrityException integrityFailure) {
            handleIntegrityFailure(projectId, mergeRequestIid, headSha, integrityFailure, allowDiagnosticComment);
        }
    }

    private boolean isValidPersistableSha(String sha) {
        return sha != null && sha.length() <= 64 && SHA_PATTERN.matcher(sha).matches();
    }

    /**
     * WHR-27/WHR-28 (§4.4): the authoritative signal (structured ERROR log + metric) is unconditional;
     * the courtesy MR comment is best-effort and may be capped by the caller (sweep, WHR-24).
     */
    private void handleIntegrityFailure(Long projectId, Long mergeRequestIid, String headSha,
                                         DiffIntegrityException failure, boolean allowDiagnosticComment) {
        log.error("event=diff_integrity_failed project_id={} mr_iid={} head_sha={} reason={}",
                projectId, mergeRequestIid, headSha, failure.reason());
        metricsCounters.incrementWebhookDiffIntegrityFailure(failure.reason().name());

        if (allowDiagnosticComment) {
            postDiagnosticCommentIfNotAlreadyPosted(projectId, mergeRequestIid, headSha, failure.reason());
        }
    }

    /**
     * WHT-20/WHR-28/F-WH-03: filters notes by {@code author.id} (server-side field) AND the
     * Gateway-constant marker AND this exact {@code head_sha} — never by body text alone. {@link
     * GitLabClient#listRecentNotes} returns {@link Optional#empty()} (never throws) on any
     * read failure or ambiguity — that case, and only that case, means "do not post" (§4.4): it must
     * never be conflated with a genuinely-successful read that simply found no matching prior comment.
     */
    private void postDiagnosticCommentIfNotAlreadyPosted(Long projectId, Long mergeRequestIid, String headSha,
                                                           DiffIntegrityException.Reason reason) {
        Long botUserId = properties.getWebhook().getBotUserId();
        Optional<List<GitLabClient.Note>> notes = gitLabClient.listRecentNotes(projectId, mergeRequestIid);
        if (notes.isEmpty()) {
            log.warn("Webhook trigger: notes read was unreadable/ambiguous for project={} mr={}; "
                    + "not posting a diagnostic comment (WHR-28 fail-safe direction)", projectId, mergeRequestIid);
            return;
        }
        boolean alreadyPosted = notes.get().stream().anyMatch(note ->
                botUserId.equals(note.authorId())
                        && note.body() != null
                        && note.body().contains(DiagnosticCommentRenderer.MARKER)
                        && note.body().contains(headSha));
        if (alreadyPosted) {
            return;
        }
        try {
            gitLabClient.postDiscussion(projectId, mergeRequestIid, DiagnosticCommentRenderer.render(reason, headSha));
        } catch (GitLabPublishException publishFailure) {
            log.warn("Failed to post diagnostic comment for project={} mr={}: {}",
                    projectId, mergeRequestIid, publishFailure.getMessage());
        }
    }

    // ---- WHR-22: in-memory rate limiting (single Gateway instance, so in-memory is correct+sufficient) ----

    private synchronized boolean tryConsumeRateLimit(Long projectId) {
        RateWindow perProject = perProjectWindows.computeIfAbsent(projectId, id -> new RateWindow());
        if (!perProject.tryConsume(properties.getWebhook().getMaxReviewsPerProjectPerHour())) {
            return false;
        }
        if (!globalWindow.tryConsume(properties.getWebhook().getMaxReviewsPerHour())) {
            perProject.rollback(); // don't burn a per-project slot for a globally-rejected attempt
            return false;
        }
        return true;
    }

    /**
     * ponytail: a simple fixed (not sliding) hourly window — a burst straddling a window boundary can
     * momentarily allow up to ~2x the configured limit. Add a sliding/token-bucket window if that
     * imprecision ever actually matters at this project's scale (20-30 MRs/day); a fixed window is the
     * smallest correct implementation of "bound the cost", which is all WHR-22 asks for.
     */
    private static final class RateWindow {
        private long windowStartMillis = System.currentTimeMillis();
        private int count;

        boolean tryConsume(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStartMillis > Duration.ofHours(1).toMillis()) {
                windowStartMillis = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }

        void rollback() {
            if (count > 0) {
                count--;
            }
        }
    }
}
