# Architecture: Diff Position Anchoring

Status: **IMPLEMENTED**. Threat model: `docs/diff-position-anchoring-threat-model.md` (`DPT-01..13` /
`DPR-01..16`) — all 11 blocking MUSTs implemented; SHOULDs DPR-12/13/15 implemented, DPR-13 (AI-generated
banner) and DPR-14 (`versions` endpoint) explicitly deferred (see §5). This document records what was
actually built, scaled to the size of the feature (no new table, no new dependency, four new classes/records).

## 1. Summary

Comments published to GitLab today land as top-level notes. This feature attaches a GitLab `position`
object to comments the LLM tagged with a concrete `(file, line)`, so they render as native diff threads
instead — purely additive to the publish path, with every failure mode falling back to today's plain-note
behavior. No schema change: the diff already stored at Review-creation time (`review_inputs.diff`) is
parsed on the fly at publish time; nothing new is persisted.

## 2. Components

| Component | Responsibility |
|---|---|
| `DiffPositionResolver` (new `@Service`) | Pure function `(diff, Set<PathLine>) -> Map<PathLine, ResolvedLine>`. Single `BufferedReader` line-scan (mirrors `DiffChunker`'s style), total — never throws (DPR-02). |
| `GitLabClient.fetchDiffRefs` / `GitLabClientImpl` | New read call, `GET /projects/{id}/merge_requests/{iid}`, on the existing write-scoped `gitLabRestClient` (no new credential). Binds only `diff_refs.{base_sha,start_sha,head_sha}` (DPR-07). |
| `GitLabClientImpl.postDiscussion` | Gains a nullable `DiffPosition` parameter; builds the wire `position` object (or omits it entirely) and retries once without a position on HTTP 400 (DPR-08). |
| `GitLabPublisher.buildPositionContext` | New best-effort step in `publishReview`, wrapped in `catch (RuntimeException)` (DPR-01) — resolves positions for the current batch of unpublished comments. |
| `PublishRetryService.retryPublications` | Gained its own per-review `catch (RuntimeException)` guard (DPR-01b) — the first Review that can now throw (via position resolution) must not block every Review behind it. |
| `MetricsCounters` / `GET /metrics` | Four new counters: `positionsAnchored`, `positionsUnresolved`, `diffRefsUnavailable`, `positionRejectedByGitLab` (DPR-12). |
| `GatewayProperties.Publish#positionAnchoringEnabled` | Kill-switch, default `true` (DPR-10). |

New records: `DiffPositionResolver.PathLine` / `DiffPositionResolver.ResolvedLine` (in-process only),
`service.dto.DiffRefs` / `service.dto.DiffPosition` (cross the `GitLabClient` boundary). All four have
masked `toString()`s (DPR-15) in the existing `JobPayload`/`ClaimedJob`/`DiffChunk` style.

**Explicitly not built**: no new DB table/column/migration, no new GitLab credential, no new `RestClient`
bean, no `BoundedInputStream` for the new GET (verified unnecessary — see the threat model §4.1), no
fuzzy/suffix path matching, no second "old-file" line-interpretation pass, no cross-run cache of
`diff_refs`.

## 3. Flow

```
PublishRetryService.retryPublications          @Transactional(readOnly = true)
  └─ for each COMPLETED review:  try { ... } catch (RuntimeException) { skip to next }   <-- DPR-01b
       GitLabPublisher.publishReview            @Transactional(readOnly = true)
         ├─ unpublished = findByReviewIdAndPublishedAtIsNull(reviewId)
         ├─ try { positionContext = buildPositionContext(review, unpublished) }
         │   catch (RuntimeException e) { log class only; positionContext = null }        <-- DPR-01
         │     ├─ flag off | no unpublished comment has file+line  -> null, zero GitLab calls (DPR-11)
         │     ├─ review.headSha not a well-formed 40-hex SHA      -> null (DPR-06, "unverifiable")
         │     ├─ fetchDiffRefs(projectId, mrIid)                  -> Optional.empty on any failure
         │     ├─ diff_refs.head_sha != review.headSha (normalized, exact) -> null (DPR-06)
         │     ├─ review_inputs row / diff missing                 -> null
         │     └─ DiffPositionResolver.resolve(diff, wanted-keys)  -> Map<PathLine, ResolvedLine>
         └─ for each comment:  position = lookup in the resolved map (or null)
                                try { publishOneComment(..., position) } catch (GitLabPublishException)
                                     └─ postDiscussion(pid, iid, body, position)
                                          └─ HTTP 400 with a position attached -> retry ONCE, position omitted
```

## 4. The trust boundary (§4.3 of the threat model, restated)

The LLM-supplied `file`/`line` are used **only as a lookup key** — never transmitted to GitLab. The
`old_path`/`new_path`/`old_line`/`new_line` actually sent are Gateway-computed from Gateway's own parse of
`review_inputs.diff`; the three SHAs come live from GitLab and are hex-pinned. This closes the
string-injection surface entirely: no LLM-controlled text ever reaches the position object. The residual
(the LLM can still choose *which* of the Gateway's own diff entries a comment binds to) is bounded by
GitLab's own server-side position validation to the same MR/project — accepted risk, DPR-16.

### Path normalization (DPR-05)

Asymmetric, on purpose: the diff-derived path has its leading `a/`/`b/`/`./` prefix stripped exactly once
(git's own structural artifact); the LLM-supplied lookup key is **never** stripped (it is plain text, not
git-decorated — stripping it too would let two different files collide onto the same key, DPT-04). Exact
match only, no suffix/basename fallback. If two distinct diff-derived paths normalize to the same key, both
are dropped from the index — ambiguous is unresolvable, never guessed.

### Freshness check (DPR-06)

`diff_refs.head_sha` (from GitLab, hex-pinned) is compared to `review.headSha` (stored at Review-creation
time, only `@NotBlank`-validated by `CreateReviewRequest`) via exact equality on normalized (trimmed,
lowercased) full 40-hex SHAs. `review.headSha` failing that same shape is treated as "freshness
unverifiable" (DEBUG-logged, named reason) — never a prefix/`startsWith` match, which would make a short
`headSha` match every MR state and defeat the only control tying the stored diff to the live MR.

### Wire contract (DPR-03)

`DiscussionRequest`/`PositionRequest` are both `@JsonInclude(NON_NULL)`. The fallback (no position) body
serializes to exactly `{"body":"..."}` — no `position` key at all, byte-identical to pre-feature behavior.
A position is only ever sent when it is fully well-formed: `old_path`/`new_path` are never `/dev/null` (an
added file uses `old_path == new_path`, `old_line` omitted, per GitLab's convention — applied inside
`DiffPositionResolver`, not left to the wire-shaping code to patch up); `new_line` is always present (new-
file interpretation only); `old_line` is included only for a context line. `GitLabClientImpl.toPositionRequest`
is the single choke point that re-validates all of this before ever building a `PositionRequest` — defense
in depth even though `DiffPositionResolver`/`GitLabPublisher` are already specified to never produce an
invalid `DiffPosition`.

## 5. Deferred SHOULDs

- **DPR-13 (AI-generated banner)**: not implemented in this pass. It would require prefixing every
  published comment body, which is orthogonal to this feature (T-06/SR-08 have tracked it since the
  original build-out) and would force rewriting the existing byte-exact `postDiscussion(..., eq("some
  text"), ...)` assertions across `GitLabPublisherTest` for no anchoring-specific benefit. Left for a
  dedicated pass so it can be reviewed (and tested) on its own terms.
- **DPR-14 (prefer `GET .../merge_requests/{iid}/versions?per_page=1` over the full MR object)**: the
  threat model itself frames this as conditional ("if the equivalence is confirmed cheaply against the
  deployment's GitLab version; otherwise keep the MR endpoint"). Kept the MR endpoint — already correct,
  documented, and the existing client config is sufficient either way (threat model §4.1).

## 6. Configuration

`gateway.publish.position-anchoring-enabled` (env: `POSITION_ANCHORING_ENABLED`, default `true`). Every
failure mode of this feature degrades to today's plain-note behavior, so shipping on by default is safe;
the flag is the supported kill switch (DPR-09) if an operator wants to disable it outright (e.g. `GITLAB_TOKEN`
lacks the read scope documented in `DEPLOYMENT.md`/`README.md`).

## 7. Non-regression / operational notes

- No migration, no schema change, no new env var beyond the one flag above. Rollback is safe in both
  directions: an older JAR posts plain notes; a newer JAR against an unchanged DB works unmodified.
- `GITLAB_TOKEN` now additionally needs read access to the reviewed MRs (Reporter+/`api`) — see
  `DEPLOYMENT.md`. A token lacking that scope degrades silently to plain notes (indistinguishable, without
  `GET /metrics`, from "no comment had a line number" — hence DPR-12's counters).
- No new log statement in `DiffPositionResolver`/`buildPositionContext`/`fetchDiffRefs`/`postDiscussion`
  emits a file path, a commit SHA, or diff text (DPR-04) — diagnostics are counts, comment ids, and fixed
  reason codes only.
