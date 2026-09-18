package com.review.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitLab Webhook Diff Trigger ({@code POST gateway.webhook.path} body), threat model WHT-02/WHR-03: the
 * body is a hint, not a fact — {@code (projectId, mrIid)} are the ONLY two fields the rest of the system
 * ever reads out of this record; {@code lastCommitId} is an additional, explicitly UNTRUSTED hint used
 * only for {@code WebhookReviewTriggerService}'s WHR-05 cheap pre-fetch dedup fast path. Every other
 * field on the real GitLab payload (reviewer lists, action, changes.*) is deliberately NOT modeled here
 * — modeling a field is what would tempt a future change to read it as if it were trustworthy.
 *
 * <p>Empirically-confirmed shape (threat model §8): {@code object_kind}/{@code event_type:
 * "merge_request"}, {@code project.id}, {@code object_attributes.iid},
 * {@code object_attributes.last_commit.id}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabWebhookPayload(
        @JsonProperty("object_kind") String objectKind,
        Project project,
        @JsonProperty("object_attributes") ObjectAttributes objectAttributes) {

    public boolean isMergeRequestEvent() {
        return "merge_request".equals(objectKind);
    }

    public Long projectId() {
        return project == null ? null : project.id();
    }

    public Long mergeRequestIid() {
        return objectAttributes == null ? null : objectAttributes.iid();
    }

    /** UNTRUSTED hint only — never persisted, never becomes a Review's actual {@code headSha} (WHR-05). */
    public String lastCommitHint() {
        return objectAttributes == null || objectAttributes.lastCommit() == null
                ? null : objectAttributes.lastCommit().id();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(Long iid, @JsonProperty("last_commit") LastCommit lastCommit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LastCommit(String id) {
    }
}
