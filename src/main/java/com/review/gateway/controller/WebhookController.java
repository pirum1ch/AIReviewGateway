package com.review.gateway.controller;

import com.review.gateway.dto.GitLabWebhookPayload;
import com.review.gateway.service.WebhookReviewTriggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitLab Webhook Diff Trigger — {@code POST gateway.webhook.path} (WHTB-HOOK). Authorization is entirely
 * {@code SecurityConfig}'s job (WHT-23/WHR-02, {@code .hasRole("WEBHOOK")}); this controller contains no
 * authorization logic of its own, exactly like every other controller in this codebase.
 *
 * <p><b>WHT-24:</b> {@code @ConditionalOnProperty} means this bean — and therefore its {@code
 * @PostMapping}, i.e. the route itself — does not exist in the application context at all when {@code
 * gateway.webhook.enabled=false} (the default): not merely unauthenticated, genuinely absent.
 *
 * <p><b>WHR-07:</b> exactly one response for everything this endpoint accepts (authenticated + a
 * parseable body), regardless of what {@link WebhookReviewTriggerService} actually did downstream
 * (created / deduplicated / not-a-reviewer / rate-limited / integrity-failed / fetch-failed) — that
 * service never lets an exception escape to this class, and this class never inspects its outcome.
 */
@RestController
@ConditionalOnProperty(prefix = "gateway.webhook", name = "enabled", havingValue = "true")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookReviewTriggerService triggerService;

    public WebhookController(WebhookReviewTriggerService triggerService) {
        this.triggerService = triggerService;
    }

    @PostMapping("${gateway.webhook.path:/webhooks/gitlab}")
    public ResponseEntity<Void> handleWebhook(@RequestBody(required = false) GitLabWebhookPayload payload) {
        if (payload != null && payload.isMergeRequestEvent()
                && payload.projectId() != null && payload.projectId() > 0
                && payload.mergeRequestIid() != null && payload.mergeRequestIid() > 0) {
            triggerService.handle(payload.projectId(), payload.mergeRequestIid(), payload.lastCommitHint());
        } else {
            // WHR-07: still a coarse accept -- "not an interesting event" is one of the accepted outcomes.
            log.info("Webhook delivery ignored: not a recognizable merge_request event");
        }
        return ResponseEntity.accepted().build();
    }
}
