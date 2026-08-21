# Diff Position Anchoring — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**. No code for this feature exists. This model threat-models the approved-in-draft
design for attaching a GitLab `position` object to review comments so they render as native diff threads
(new `DiffPositionResolver`; `GitLabClient.fetchDiffRefs`; a nullable `DiffPosition` parameter on
`postDiscussion` with an automatic position-less retry on HTTP 400; `GitLabPublisher.buildPositionContext`;
one new flag `gateway.publish.position-anchoring-enabled`, default `true`).

It **extends** `docs/threat-model.md` (`T-01..T-26` / `SR-01..SR-24`), the diff-chunking controls
`CSR-01..CSR-22` and their SAST findings `F-DC-01..F-DC-13`, `docs/prompt-manager-threat-model.md`
(`PMT-nn`/`PMR-nn`) and `docs/worker-observability-and-claim-latency-threat-model.md` (`WOT-nn`/`WOR-nn`).
It rewrites none of them.

**ID convention.** Mirrors the established threat-model split (`T-`/`SR-`, `PMT-`/`PMR-`, `WOT-`/`WOR-`):
threats are **`DPT-nn`**, requirements are **`DPR-nn`**. The `F-DP-nn` prefix is reserved for *findings* in
the post-implementation SAST round (`docs/security/feature-diff-position-anchoring-sast-report.md`), matching
the `F-DC-`/`F-PM-`/`F-WOC-` filename-and-finding convention.

Methodology: STRIDE per element/flow + OWASP Top 10 (2021) + OWASP LLM Top 10 (LLM01) + CWE. Risk =
qualitative Likelihood × Impact (Critical/High/Medium/Low). Every requirement is tagged MUST / SHOULD /
ACCEPTED-RISK.

### Framing note that drives the ratings

This feature has an unusually clean *injection* posture and an unusually dirty *availability* posture, and
the design document has the emphasis backwards.

The central trust move — LLM-supplied `file`/`line` are a **lookup key only**, never transmitted; the
transmitted `old_path`/`new_path`/`old_line`/`new_line` are Gateway-computed from the Gateway's own parse of
`review_inputs.diff`; the three SHAs come live from GitLab and are hex-pinned — is **correct and I endorse
it** (§4.3). It genuinely closes the string-injection surface.

What it does *not* close is that this feature inserts, for the first time, a **new failure source into
`GitLabPublisher.publishReview` outside the per-comment `try`/`catch`**. `PublishRetryService.retryPublications`
(`PublishRetryService.java:36-45`) loops over `findByStatusOrderByCreatedAtAsc(COMPLETED)` with **no per-review
`try`/`catch`**, and `publishReview` is today effectively non-throwing (every GitLab failure is caught per
comment at `GitLabPublisher.java:75-79`). One unhandled `RuntimeException` out of `buildPositionContext` —
a `NumberFormatException` on a hunk header like `@@ -99999999999999999999,1 +1,1 @@`, which any MR author can
commit — therefore aborts **the entire publish pass**, and because the candidate list is ordered
`createdAt ASC` the poisoned Review sits at the head of it on every subsequent pass. That is a permanent,
remotely-triggerable, silent denial of the product's only output channel. It is **DPT-01**, it is the one
blocking finding here, and the fix is roughly six lines.

Everything else is Medium or below, and several of the design's own decisions (exact-match-only paths, no
cross-run cache, new-file-only line interpretation, reusing the existing write-scoped token) are **endorsed
on security grounds** in §4.

---

## 0. Branch-point discrepancy (procedural, must be resolved before dev starts)

The design as written cites `GitLabClientImpl`'s `COMMIT_SHA_PATTERN` ("mirrors the existing pattern already in
this class for PMR-13"), `gateway.prompt.enabled`, `TextSanitizer`, `BoundedInputStream` and `MetricsCounters`.
**None of these exist on `master`.** `master` is `c8d0a9c` (the diff-chunking merge); Prompt Manager and Worker
Observability live only on `feature/prompt-manager` / `fix/worker-observability-and-claim-latency`, which is 54
commits ahead. Verified:

```
git cat-file -e master:src/main/java/com/review/gateway/service/TextSanitizer.java     -> missing
git cat-file -e master:src/main/java/com/review/gateway/service/MetricsCounters.java   -> missing
git cat-file -e master:src/main/java/com/review/gateway/service/BoundedInputStream.java-> missing
git show master:.../GitLabClientImpl.java | grep COMMIT_SHA                            -> no match
```

This branch was created from `master` as instructed, so this document is committed there — but the developer
must either (a) rebase onto the integrated line before implementing, or (b) treat DPR-06's SHA pattern and
DPR-12's counters as *new* code rather than reuse. Option (a) is strongly preferred: DPR-12 is materially
cheaper with `MetricsCounters` already present, and re-implementing a second `^[0-9a-f]{40}$` constant in a
class that will later merge with one is exactly the "second implementation of the same lesson" the F-DC-02 /
WOR-07 reviews called out.

---

## 1. Decomposition — new elements, boundaries, flows

### New / changed elements

| Element | Change | Notes |
|---|---|---|
| `DiffPositionResolver` | **New** `@Service`, pure function, no DB/network | `(diff, Set<PathLine>) -> Map<PathLine, ResolvedLine>`; single `BufferedReader` line scan, `DiffChunker` style |
| `PathLine`, `ResolvedLine`, `DiffPosition`, `DiffRefs` | **New** records | Carry LLM-controlled *and* MR-author-controlled paths — `toString()` exposure class (DPT-08) |
| `GitLabClient.fetchDiffRefs` | **New** outbound `GET /projects/{id}/merge_requests/{iid}` | First **read** call on the write-scoped `gitLabRestClient`/`GITLAB_TOKEN` |
| `GitLabClientImpl.postDiscussion` | Gains `@Nullable DiffPosition`; auto-retry once without `position` on **400** | The single choke point for the fallback |
| `DiscussionRequest`/`PositionRequest` | Wire shape; `@JsonInclude(NON_NULL)` load-bearing | DPT-02 |
| `GitLabPublisher.buildPositionContext` | **New** best-effort step inside `publishReview`'s `@Transactional(readOnly = true)`, before the per-comment loop | **The new failure source outside the per-comment `try`** — DPT-01 |
| `GitLabPublisher.publishOneComment` | Gains a `DiffPosition` parameter | `filePath`/`lineNumber` are `updatable = false` (`ReviewComment.java:39,42`) — reading them outside the `REQUIRES_NEW` block is genuinely safe, confirmed |
| `GatewayProperties.Publish` | One new boolean, default `true` | F-PM-02 / F-DC-04 drift class (DPT-13) |
| Schema | **None.** No migration, no new column, no new table | Rollback is trivially safe both directions |

### Trust boundaries (delta on `docs/threat-model.md` §1)

| # | Boundary | Channel | Trust posture |
|---|---|---|---|
| **DPTB-MR** | Gateway → GitLab, `GET .../merge_requests/{iid}` | HTTPS, `PRIVATE-TOKEN` = write-scoped `GITLAB_TOKEN` | **New direction on an existing boundary (TB-GITLAB).** Response is GitLab-authored but *contains* MR-author-controlled fields (`description`, `title`, branch names). Only the three SHAs are bound (DPR-07). |
| **DPTB-DIFF** | `review_inputs.diff` → `DiffPositionResolver` → GitLab write body | in-process | **New.** MR-author-controlled text (submitted by CI, never verified against GitLab's own diff) becomes the `old_path`/`new_path`/`old_line`/`new_line` of an authenticated **write** to GitLab. Previously the diff only ever flowed *outward to a Worker*; it now flows into a write payload. |
| **DPTB-KEY** | `review_comments.file_path`/`line_number` → resolver map key | in-process | **New use of an existing column.** LLM-controlled, sanitized by `CommentParser.sanitizeFilePath` — but note `HtmlUtils.htmlEscape` does **not** strip C0 controls ≤ 0x7F (ESC, BEL, …); only `\R` is collapsed. Latent since F02-04; this feature is the first consumer (DPT-03). |

### Flow

```
 PublishRetryService.retryPublications        @Transactional(readOnly = true)   [no per-review try/catch]
   └─ for each COMPLETED review:
        GitLabPublisher.publishReview          @Transactional(readOnly = true)  [holds the same connection]
          ├─ unpublished = findByReviewIdAndPublishedAtIsNull(reviewId)
          ├─ buildPositionContext(review, unpublished)      <-- NEW, OUTSIDE the per-comment try  (DPT-01)
          │     ├─ flag off | no comment has file+line            -> null, no GitLab call
          │     ├─ fetchDiffRefs(projectId, mrIid)   ──► DPTB-MR  -> Optional.empty on any failure
          │     ├─ diff_refs.head_sha != review.headSha           -> null   (DPT-05)
          │     ├─ review_inputs row missing / diff null          -> null   (DPR-02)
          │     └─ DiffPositionResolver.resolve(diff, wanted) ──► DPTB-DIFF
          └─ for each comment:  try { publishOneComment(..., position) }  catch (GitLabPublishException)
                                   └─ postDiscussion(pid, iid, body, position)
                                        └─ HTTP 400 ⇒ retry ONCE with position omitted   (DPT-02, DPT-07)
```

The chain that matters: **one malformed hunk header → one uncaught exception → the whole publish pass dies →
head-of-line block forever.** That path does not exist today.

---

## 2. Assets (delta)

| # | Asset | C | I | A | Notes |
|---|---|:-:|:-:|:-:|---|
| **DPA1** | The publish pipeline's liveness | — | M | **H** | New. `retryPublications` is the only path that ever gets a COMPLETED Review to PUBLISHED. It has a single-threaded, unguarded, `createdAt ASC`-ordered loop. One poison Review starves all of them. |
| **DPA2** | Anchor correctness (which line a finding points at) | — | M | — | New. A confidently-wrong anchor is worse than no anchor (the design says so, and is right — §4.4). Blast radius is bounded by GitLab's own server-side validation (§4.5). |
| A1 (inherited) | `GITLAB_TOKEN` | H | H | — | Now also used for a **read**; its required scope widens (DPT-11). Blast radius on leak is unchanged (it was already `api`-class). |
| A6 (inherited) | `review_inputs.diff` | **H** | M | — | New in-process consumer inside the publish path; new `toString()`/log exposure surface (DPT-03, DPT-08). Never leaves the process except as ≤1024-char path fragments in the position object. |

---

## 3. STRIDE threats — DPT-01..DPT-13

"New" = introduced by this design. "Amp" = pre-existing residual this design amplifies. "Found" = pre-existing
defect found while reviewing this design, in code it touches.

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | Status |
|----|--------|-------------|-----------|----------|:---:|--------|
| **DPT-01** | Denial of Service | CWE-248, CWE-755, CWE-390 / A04 | `buildPositionContext` → `publishReview` → `retryPublications` | **An unhandled `RuntimeException` in position resolution permanently kills the publish subsystem.** `buildPositionContext` sits *before* the per-comment loop, outside the only `try` in `publishReview` (`GitLabPublisher.java:72-80`), and `retryPublications` (`PublishRetryService.java:38-43`) wraps nothing. Sources are numerous and all MR-author- or GitLab-reachable: `Integer.parseInt` on `@@ -99999999999999999999,1 +1,1 @@` (**NumberFormatException**), index-scan arithmetic on a truncated `@@` line (**StringIndexOutOfBounds**), `int` overflow of the running line counter, `Pattern.matcher(null)` on a `"diff_refs": null` or `"head_sha": null` response (**NPE** — DPT-09), `HtmlUtils.htmlUnescape(null)`, a `null`/purged `review_inputs.diff` (SR-22's retention purge is a tracked SHOULD), and any `RestClientException` subclass `fetchDiffRefs` forgets to catch (`UnknownContentTypeException`, `RestClientSsl` failures, `IllegalArgumentException` from URI building). Because candidates are ordered `createdAt ASC`, the poisoned Review is at the head of every subsequent pass ⇒ **no Review is ever published again** until an operator notices. Trigger requires only committing a file whose diff carries a crafted hunk header. | **High** | **BLOCKING** (New) |
| **DPT-02** | Denial of Service / Tampering | CWE-1287, CWE-116 / A04 | `DiscussionRequest`/`PositionRequest` serialization; `/dev/null`; half-filled positions | **The "always falls back to a plain note" invariant is one annotation deep.** If `@JsonInclude(NON_NULL)` is placed only on `PositionRequest` and not on `DiscussionRequest`, or is later overridden by a global `spring.jackson.default-property-inclusion`, the fallback POST sends `"position": null` — GitLab 400s it, the 400-retry sends the *same* body, 400s again, and the comment is never published, **on every comment of every Review**, with `PublishRetryService` looping forever. The same permanent-400 shape is reachable from two design gaps: transmitting `old_path: "/dev/null"` for an added file (GitLab's convention is `old_path == new_path` with `old_line` omitted), and emitting an illegal line-type combination (neither `old_line` nor `new_line`, or both set for an added line). Note the failure is *silent* — 400 is not an exception path the operator sees. | Medium | **Needs mitigation** (New) |
| **DPT-03** | Tampering / Repudiation | CWE-117, CWE-93, CWE-116 / A09 | any new log statement handling a path | **Two unsanitized path families reach new code for the first time.** (a) `review_comments.file_path` is LLM-controlled and only *partly* sanitized: `CommentParser.sanitizeFilePath` (`:231-242`) collapses `\R` and HTML-escapes, but `HtmlUtils.htmlEscape` leaves every C0 control ≤ 0x7F untouched (ESC/``, BEL) — U+202E and friends are escaped only because they exceed the ISO-8859-1 range. So ANSI/terminal-escape payloads survive in that column today; nothing has ever read it. (b) The diff-derived path is MR-author-controlled and, per the design's own reject-don't-sanitize rule, deliberately **never cleaned**. Either one in an slf4j placeholder is F-DC-06/WOT-04 all over again, in a log the operator reads to diagnose why anchoring is degrading. | Medium | **Needs mitigation** (New sink + Found latent) |
| **DPT-04** | Tampering | CWE-41 (path equivalence), CWE-706 / A01 | `DiffPositionResolver` path normalization | **Stripping `a/`/`b/`/`./` on *both* sides re-opens the selection ambiguity that "exact match only" was chosen to avoid.** A repo containing both `x.java` and `a/x.java` normalizes to the same key (`--- a/a/x.java` → `a/x.java` → strip → `x.java`; `--- a/x.java` → `x.java`). The LLM naming `a/x.java` then resolves to the *wrong file's* line numbers, and the resulting position may still be valid for the wrong file ⇒ GitLab accepts it ⇒ a finding is anchored to an unrelated file with no 400 backstop. The `a/`/`b/` prefix is git's own structural artifact on the diff side; on the LLM side it is just text. Prefix stripping belongs on one side only. | Medium | **Needs mitigation** (New) |
| **DPT-05** | Spoofing / Tampering | CWE-20, CWE-1289 / A03 | `diff_refs.head_sha` vs `review.headSha` | **The freshness check binds a hex-pinned GitLab value to a completely unvalidated one.** `CreateReviewRequest.headSha` is `@NotBlank` and nothing more (`CreateReviewRequest.java:14`) — CI may legitimately send `CI_COMMIT_SHORT_SHA` (8 hex), an uppercase SHA, or arbitrary text. Two consequences: (i) the equality never holds, so anchoring is silently disabled fleet-wide with no diagnosable signal; (ii) the obvious "fix" a future maintainer reaches for is `startsWith`/prefix matching, which would make a 4-character `headSha` match *every* MR state and defeat the only control tying the stored diff to the live MR. This check is load-bearing (it is what makes DPT-10's blast radius "the same MR"), so it must be exact, normalized, and explicitly documented as never a prefix match. | Medium | **Needs mitigation** (New + Found) |
| **DPT-06** | Tampering (into GitLab UI) | CWE-1427 (LLM01), CWE-451 / A03 | LLM output → position selection → diff thread | **T-06's impact escalates even though its injection surface does not.** No LLM string is transmitted — endorsed (§4.3). But the LLM gains a *selection oracle*: it chooses which of the Gateway's own parsed diff entries a comment attaches to. Combined with the already-accepted ability to write arbitrary comment text, a prompt-injection payload can place a fabricated "critical vulnerability" note on a specific innocent line of a *different file in the same MR*, and GitLab's native diff-thread UI lends it the visual authority of a machine-verified anchor that today's top-level note does not have. Aggravating: T-06's "prefix every published comment with an AI-generated banner" SHOULD is **still unimplemented** (grep of `src/main` finds no banner). | Medium | **Needs mitigation** (Amp of T-06) |
| **DPT-07** | Denial of Service | CWE-770, CWE-1088 / A04 | the 400 fallback retry | Write amplification and scope creep. Keyed correctly (400 only, once, only when a position was attached, non-recursive) the worst case is 2× POSTs per comment — 100 writes per pass at `max-comment-count: 50`, acceptable. Keyed loosely ("any 4xx") it would retry **429** — doubling load against a GitLab that is explicitly asking for less — and mask a **401/403** token failure behind a successful-looking plain note, so a revoked token looks like "anchoring just isn't working". The recursion guard matters too: a retry that re-enters the same method with the position still attached is an infinite loop inside a `REQUIRES_NEW` transaction. | Low | **Needs mitigation** (New) |
| **DPT-08** | Info disclosure | CWE-532 / A09 | `toString()` of `PathLine`/`ResolvedLine`/`DiffPosition`/`DiffRefs` and the context `Map` | Records auto-generate a content-dumping `toString()`. `PathLine` carries the LLM path, `ResolvedLine` the MR-author paths, `DiffRefs` three commit SHAs, and the context `Map` carries all of them at once — and a `Map`/`Collection` in an slf4j placeholder bypasses any per-record masking anyway (the exact WOR-17 trap). Latent, not live, but this is the fourth feature in a row to introduce it (F-DC-07, PMR-25, WOR-17). | Low | **Needs mitigation** (New, cheap) |
| **DPT-09** | Denial of Service | CWE-476 / A04 | `fetchDiffRefs` response mapping | GitLab returns `"diff_refs": null` for MRs in several real states (no diff yet, some import/closed states), and individual members can be null. `COMMIT_SHA_PATTERN.matcher(null)` is an NPE, which becomes DPT-01. Null must be checked *before* the pattern match, and all three SHAs must be present and matching or the whole `DiffRefs` is discarded — never a partially-populated position. | Low | **Needs mitigation** (New) |
| **DPT-10** | Tampering | CWE-345 (insufficient verification) / A08 | `review_inputs.diff` vs GitLab's real diff | The Gateway computes `old_path`/`new_path`/lines from a diff **CI supplied and the Gateway never verified**, while pairing them with SHAs GitLab itself vouches for. A CI-token holder (shared token, T-21) can therefore submit a fabricated diff for a real MR and steer anchors. **The architect's blast-radius bound is confirmed**: GitLab validates every position against its own diff server-side and 400s anything that does not match, so the reachable set is exactly "positions that exist in the real MR's diff" — same MR, same project, cosmetic misplacement. One correction to the architect's framing: the stronger attacker position here is the **CI token**, not the LLM — CI controls the whole document the resolver reads, whereas the LLM only picks among what it finds. | Low | **Accepted** (Amp of T-21; bounded by GitLab validation + DPR-06) |
| **DPT-11** | Elevation / Info disclosure | CWE-250, CWE-269 / A01 | `GITLAB_TOKEN` scope for the new GET | The write-scoped token now performs a read. **The design's choice to reuse it rather than mint a third credential is correct and I endorse it** (§4.2), but two consequences must be written down: `GITLAB_TOKEN` now requires *read* access to the reviewed MR (Reporter+/`api`), and a deployment whose token lacks it degrades **silently** — 403 → `Optional.empty()` → plain notes forever, indistinguishable from "no comment had a line number". T-11's least-privilege requirement is unchanged in substance but its minimum viable scope grows. | Low | **Needs mitigation** (New, doc + signal) |
| **DPT-12** | Denial of Service | CWE-400 / A04 | publish-pass cost | +1 GET per COMPLETED Review per retry pass, serially, inside `retryPublications`'s single `readOnly` transaction (one pinned Hikari connection of 20). Assessed and **found not to warrant a new control**: the existing per-comment `postDiscussion` calls already expose the same pass to up to `50 × 30 s`, so one more 30 s-bounded call adds ~2% to an already-accepted worst case, and the design's own "skip `fetchDiffRefs` entirely when no comment has both file and line" short-circuit removes it for the common case. The bound is real — see §4.1 for the verification that the 30 s read timeout genuinely covers the body read. | Low | **Accepted** (New) |
| **DPT-13** | Security misconfiguration | CWE-1188, CWE-710 / A05 | `gateway.publish.position-anchoring-enabled` | **Default `true` is endorsed** (every failure mode degrades to today's behavior; nothing about this feature can fail a Review once DPT-01 is fixed). Two config hazards carry over verbatim from prior features: the Java field default and `application.yml` must agree (F-PM-02 shipped a drift; F-DC-04 shipped an `application.yml` value that silently *won* over a corrected Java default), and the yml value must be an env placeholder so an operator can kill the feature without a rebuild — which matters more than usual precisely *because* it ships on. | Info | **Needs mitigation** (New, cheap) |

**Tally:** Critical = 0, **High = 1** (DPT-01), Medium = 5, Low = 6, Info = 1. Total **13**.

---

## 4. Deep dives — the four questions asked, answered

### 4.1 (a) Does the new GET need PMR-17's `BoundedInputStream` discipline? — **No. Verified, not assumed.**

The question is a good one and the reflexive answer ("bound everything") is wrong here. Three checks:

1. **The read timeout genuinely covers the body, not just the headers.** I disassembled the actual
   `spring-web 6.2.19` on this machine. `JdkClientHttpRequest` builds a `TimeoutHandler` whose constructor is
   `new CompletableFuture<Void>().completeOnTimeout(null, timeout).thenRun(() -> cancel(responseFuture))`, and
   the response body is returned through `TimeoutHandler.wrapInputStream(...)`, with `handleCancellationException`
   translating the cancel into an `HttpTimeoutException`. So `gateway.gitlab.read-timeout` (30 s) is a
   **whole-exchange deadline** including body consumption, not a time-to-headers timeout. A slow-drip response
   cannot pin the publish thread indefinitely. (This is materially different from the streamed-file case PMR-17
   was written for, where the bound was about *bytes*, not time.)
2. **The author-controlled bulk is never materialized.** `@JsonIgnoreProperties(ignoreUnknown = true)` means
   Jackson calls `nextToken()` + `skipChildren()` for `description`/`title`/`labels`; for a `VALUE_STRING`,
   `UTF8StreamJsonParser` leaves the token incomplete and `_skipString()`s past it without ever building a
   `String`. A 1 MiB MR description is scanned, not held.
3. **Only three fields are bound, and each is pattern-pinned to 40 hex characters before use** (DPR-07). The
   maximum retained attacker-influenced data from this call is 120 bytes.

**Verdict: the existing `gitLabRestClient` config (5 s connect / 30 s exchange / `followRedirects(NEVER)`) is
sufficient. No `BoundedInputStream`, no new timeout property, no new client bean.** The control that *is*
required is narrow and already in the design: bind exactly the three SHA fields and nothing else (DPR-07) —
adding, say, a `title` field to `MergeRequestResponse` "for logging" would reintroduce the whole question.

Optional hardening (DPR-14, SHOULD, not required): `GET .../merge_requests/{iid}/versions?per_page=1` returns
the same three refs (`base_commit_sha`/`start_commit_sha`/`head_commit_sha`) in a small, fixed shape containing
no free-text fields at all. Prefer it *if* the equivalence is confirmed cheaply against the deployment's GitLab
version; otherwise keep the MR endpoint, which is the documented source of `diff_refs`.

### 4.2 (b) Is `^[0-9a-f]{40}$` sufficient defense for GitLab-returned values used in a write?

**Sufficient for what it defends against; it is not the control that matters most — and the design should say so.**

- Against *structural* abuse (URI injection, JSON breakout, log forging, oversize): yes, completely. Note the
  SHAs go into a Jackson-serialized **body**, not a URI, so structural injection was never really available;
  the pattern is defense-in-depth against a future refactor that puts a SHA in a path or a log line. Keep it —
  it is the PMR-13 lesson and it costs one line.
- Against a *semantic* problem — a well-formed SHA that is the wrong one (stale MR, an MR retargeted mid-publish,
  a spoofed/MITM'd GitLab) — the pattern does nothing. The actual controls are (i) SR-15's `https`-pinned,
  operator-configured `gateway.gitlab.base-url` plus `followRedirects(NEVER)` (already verified present in
  `RestClientConfig.java:38`), and (ii) the `diff_refs.head_sha == review.headSha` equality check. Control (ii)
  is currently the weakest link in the whole design, because the value it compares against is `@NotBlank` and
  nothing else — that is DPT-05, and it is why DPR-06 is a MUST rather than a nicety.
- Reject-don't-repair is the right posture and must be stated: a SHA that fails the pattern makes the **whole**
  `DiffRefs` unavailable (all three or none), never a partially-populated position (DPR-07). A position with a
  correct `head_sha` and a garbage `base_sha` is exactly the shape that produces DPT-02's permanent 400 loop.

### 4.3 (c) Does "lookup key only" actually close T-06's injection surface? — **Yes for injection. No for impact.**

The string-injection surface is genuinely closed, and the reasoning holds up under the two ways it usually
fails:

- *Round-trip aliasing.* `CommentParser.sanitizeFilePath` HTML-escapes; the resolver `htmlUnescape`s once to
  rebuild the lookup key. `htmlEscape`/`htmlUnescape` are inverse and `htmlEscape` is injective, so no two
  distinct real paths can collide via the round trip, and no LLM string can decode into a character it did not
  originally contain (`&#10;` typed by the model becomes `&amp;#10;` on the way in and `&#10;` — the literal
  text — on the way out, not a newline). The one hard requirement is **exactly one** unescape pass; a second
  would turn `&amp;#10;` into a newline. State it in the javadoc (DPR-05).
- *Truncation.* A path longer than 1024 chars after escaping gets `... [truncated]` appended, which matches no
  diff entry ⇒ unresolvable ⇒ plain note. Fail-safe, no action needed.

**The residual is not a string, it is a choice.** The LLM fully controls *which* Gateway-parsed entry a comment
binds to (DPT-06), and the diff-thread UI makes an injected finding materially more credible than today's
top-level note. That is an impact escalation of T-06, not a new injection class, and the proportionate response
is the control T-06 already asked for and never got: the AI-generated banner (DPR-13). One further residual
worth naming: this feature makes `review_comments.file_path` a *read* column for the first time, which is what
surfaces the pre-existing C0-control-character gap in `sanitizeFilePath` (DPT-03) — a gap that was harmless
only because nothing consumed the value.

### 4.4 (d) Can anything break the "never fails a Review, always falls back" invariant?

**Yes — three ways, one of them serious.**

1. **Unhandled exception (DPT-01, High).** Covered in the framing note and DPR-01/DPR-02. This is the gap.
2. **Permanent 400 loop (DPT-02, Medium).** The fallback is only a fallback if the second POST is *materially
   different* from the first. `"position": null`, `old_path: "/dev/null"`, or an illegal line-type combination
   each produce a body GitLab rejects on both attempts, converting "degrades to a plain note" into "publishes
   nothing, forever, silently". DPR-03 makes the wire contract a test on serialized bytes rather than an
   annotation nobody re-reads.
3. **Silent total disablement (DPT-05/DPT-11, Medium/Low).** Not an invariant break — it fails *safe* — but a
   short-SHA `headSha` or a read-denied token turns the feature into a no-op with no signal whatsoever. DPR-12's
   counters are the cheap fix and follow WOR-03's precedent exactly.

**On memory/CPU, the resolver does *not* need its own size bound, and I verified why rather than assuming it.**
`review_inputs.diff` is bounded upstream: `DiffSizeValidator.rejectIfAbsurdlyLarge` is the first statement of
`ReviewService.createReview` (CSR-01, re-verified in the F-DC-01 round) and caps the diff at
`max-chunks × (budgetTokens − chunk-header-reserve) × chars-per-token` = **194,880 chars** at stock config, with
`RequestBodySizeLimitFilter`'s 320,000-byte edge cap behind it. A single line scan over ~195 KB, once per publish
attempt, emitting at most `max-comment-count` (50) entries, is free. What the resolver *does* need is
**totality** — DPR-02 — because the danger is a thrown exception, not a big allocation. Two specific bounds are
still required regardless of input size: the hunk-header number parse must not throw or overflow, and a
diff-derived path must be rejected (not repaired) at >1024 chars / blank / control-character-bearing, which the
design already specifies and which I endorse as written.

### 4.5 Design decisions I am explicitly endorsing (so the developer does not "improve" them)

- **Exact match only, no suffix/fuzzy matching.** Correct on security grounds, not just YAGNI: suffix matching
  would widen the LLM's selection oracle (DPT-06) from "name a file exactly" to "name any suffix and let the
  Gateway guess", and it is inherently ambiguous in any repo with `src/main/.../Foo.java` and
  `src/test/.../Foo.java`. **Do not add it later without a new threat-model round.**
- **New-file line interpretation only; no second "try old-file" pass.** Correct. A second pass converts a clean
  "unresolvable → plain note" into a coin flip, and near the top of a file the two interpretations frequently
  both validate — GitLab would accept the wrong one with no 400 to catch it. Removed lines being non-emittable
  is a feature, not a limitation.
- **No cross-run cache of `diff_refs`.** Correct twice over: it is exactly the non-reconstructible in-memory
  state the project principles ban, *and* a cache of a time-varying GitLab-owned fact is a stale-anchor
  generator — the `head_sha` check (DPR-06) would be validating against a cached lie.
- **Reusing the write-scoped `gitLabRestClient`/`GITLAB_TOKEN` rather than minting a third credential.**
  Correct, and the counter-argument is worth recording since it looks attractive: `GITLAB_PROMPT_TOKEN`
  (`read_api`, PMR-15) is read-only and would be more least-privilege in the abstract — but it is scoped for the
  *prompt* project rather than the reviewed ones, and it belongs to a feature that is **off by default**, so a
  stock deployment may not have it set at all. Coupling comment publishing to an optional feature's credential
  is a worse failure mode than reusing a token that already has `api` on these projects. Reuse is right;
  document the widened scope requirement (DPR-09).
- **Reject-don't-sanitize for diff-derived paths.** Correct and worth keeping verbatim in the javadoc: a value
  that addresses a write call must be byte-exact-from-the-real-diff or absent. This is the one place in the
  codebase where sanitize-and-forward would be the *wrong* answer, which is surprising enough to need the
  comment.

---

## 5. Security requirements — DPR-01..DPR-16

Testable assertions for `backend-developer`; AppSec re-verifies each in the SAST round
(`docs/security/feature-diff-position-anchoring-sast-report.md`, findings prefix `F-DP-`).

### The blocking set

- **DPR-01 (MUST, DPT-01).** Position resolution can never propagate an exception. Two guards, both required:
  (a) the entire `buildPositionContext(...)` call in `GitLabPublisher.publishReview` is wrapped in
  `try { ... } catch (RuntimeException e) { log.warn("position context unavailable: {}", e.getClass().getSimpleName()); return null; }`
  — class name only, never `e.getMessage()` (WOR-05 / F02-03: a Jackson or parse message can quote diff text);
  (b) `PublishRetryService.retryPublications`'s loop body gets its own per-review `try`/`catch (RuntimeException)`
  so **no** single Review can abort a pass — a pre-existing gap this feature is the first to make reachable, and
  the one that turns a one-Review bug into a subsystem outage. *Test:* a stubbed `DiffPositionResolver` that
  throws unconditionally still publishes every comment as a plain note and still reaches `PUBLISHED`; a
  `retryPublications` pass over 3 COMPLETED Reviews where the first throws still publishes the other two.
- **DPR-02 (MUST, DPT-01).** `DiffPositionResolver` is a **total function**: it returns a (possibly empty) map
  for every input string, including `null`, empty, binary, and adversarial. Specifically: hunk-header numbers
  are parsed with an explicit digit-count cap and a non-throwing parse (no bare `Integer.parseInt` on
  caller-controlled digits); line counters are `long` or saturating so no `int` overflow occurs; a malformed
  `@@` line skips to the next `@@` rather than throwing; every `substring`/`indexOf` is bounds-checked; a `null`
  or blank `review_inputs.diff` (SR-22 retention purge, missing row) yields a null context, never an NPE. No
  `throw` statement exists in the class. *Test:* a property/table test over ≥12 adversarial diffs
  (`@@ -99999999999999999999,1 +1,1 @@`, `@@`, `@@ -`, `@@ - + @@`, header with no `+`, `+++ ` with no path,
  100k-line hunk, lone surrogates, NUL bytes, `\r`-only line endings, a diff that is one 195 KB line, empty
  string) asserts *no exception* and a valid map for each.
- **DPR-03 (MUST, DPT-02).** The wire contract is asserted on **serialized bytes**, not on object state:
  (a) the fallback body serializes to exactly `{"body":"..."}` with **no** `position` key — byte-identical to
  today's output, so the plain-note path is provably unchanged; (b) a positioned body contains
  `position_type`, `base_sha`, `start_sha`, `head_sha`, `new_path`, `old_path` and exactly the legal line
  fields; (c) `old_line`/`new_line` are omitted, not null, per line type — added line ⇒ `new_line` only,
  context line ⇒ both. In code: a `ResolvedLine` with neither line set, or with a `null` `new_path`, is treated
  as **unresolvable** and never sent; `/dev/null` is never transmitted in `old_path`/`new_path` (for an added
  file set `old_path = new_path` and omit `old_line`, per GitLab's own convention — confirm once by hand against
  the target GitLab version before merge). *Test:* `ObjectMapper.writeValueAsString` assertions on both shapes;
  a test that a global `default-property-inclusion: always` does not reintroduce `"position": null`.
- **DPR-04 (MUST, DPT-03).** No log statement anywhere in `DiffPositionResolver`, `buildPositionContext`,
  `fetchDiffRefs` or `postDiscussion` emits a file path from either family (LLM-supplied or diff-derived), a
  commit SHA, or any diff fragment. Diagnostics are counts, comment ids, and fixed reason codes only — the
  defensive-reject branch logs `reason=path-too-long len=<n>`, never the value. *Test:* grep/architecture test
  asserting no `getFilePath()`/path variable reaches an slf4j call in these classes; a runtime test that a
  comment whose `file_path` contains `[31m` and a diff whose path contains a bidi override produce no log
  line at any level ≤ INFO containing either.
- **DPR-05 (MUST, DPT-04).** Path normalization is asymmetric and ambiguity-rejecting: the leading `a/`/`b/`
  prefix is stripped **only** from diff-derived paths (where git guarantees it), never from the LLM-supplied
  key; `HtmlUtils.htmlUnescape` is applied to the LLM key **exactly once** and its result is used only for map
  equality, never transmitted or logged; if two distinct diff entries normalize to the same key, **both** are
  dropped from the index (ambiguous ⇒ unresolvable). Exact match only — no suffix, prefix, basename or
  case-insensitive fallback (§4.5). *Test:* a diff containing **both** a top-level `x.java` (git header
  `--- a/x.java`, normalizing to `x.java`) and a nested `a/x.java` (git header `--- a/a/x.java`,
  normalizing — **one** strip only — to `a/x.java`); an LLM `file` of `a/x.java` resolves to **the nested
  file's own line numbers, never to `x.java`'s**. Plus a second case for the ambiguity arm: two diff
  entries that genuinely *do* collide on one key (e.g. a `--- a/x.java` header and a `--- ./x.java` header
  in the same diff, both normalizing to `x.java`) ⇒ **both** dropped, so an LLM `file` of `x.java` resolves
  to nothing.

  > **Wording correction (SAST round, `F-DP-`).** An earlier draft of this bullet said the LLM key
  > `a/x.java` "resolves to **nothing**". That sentence describes the outcome of the **rejected**
  > strip-both-sides design, not the asymmetric one §6 point 3 actually mandates, and it is not a spec
  > deviation for the implementation to resolve `a/x.java` to the nested file. The asymmetric single strip
  > is precisely what keeps the two keys distinct; the ambiguity-drop is the belt-and-braces backstop for
  > the shapes where they still collide. Both arms are verified implemented and tested — see
  > `docs/security/feature-diff-position-anchoring-sast-report.md`, DPR-05.
- **DPR-06 (MUST, DPT-05).** The freshness check is exact-equality on normalized full SHAs: both sides are
  trimmed and lowercased, and `review.headSha` must itself match `^[0-9a-f]{40}$` (case-insensitively) or the
  Review is treated as "freshness unverifiable" ⇒ no position, DEBUG log naming that specific reason (so the
  short-SHA deployment failure is diagnosable rather than invisible). `startsWith`/prefix/abbreviated-SHA
  matching is **forbidden**, with the reason stated in the javadoc so it is not "fixed" later. *Test:* a
  `review.headSha` of the correct SHA's first 8 characters produces **no** position; an uppercase full SHA does
  produce one; a mismatched full SHA does not.
- **DPR-07 (MUST, DPT-09).** `fetchDiffRefs` binds exactly three fields and nothing else. A `null` `diff_refs`
  object, a `null`/blank member, or any member failing `^[0-9a-f]{40}$` makes the **whole** result
  `Optional.empty()` — never a partially-populated `DiffRefs`. Null checks precede pattern matching. The method
  catches `RestClientException` **and** `RuntimeException` and returns `Optional.empty()`; it has no `throws`
  path. *Test:* responses of `{}`, `{"diff_refs":null}`, `{"diff_refs":{"base_sha":null,...}}`, a 39-hex SHA, a
  non-JSON body, a 500, and a connection reset all yield `Optional.empty()` and no exception.
- **DPR-08 (MUST, DPT-07).** The fallback retry is scoped exactly: triggered only by HTTP **400**, only when a
  non-null position was attached to the first attempt, at most **once**, and implemented non-recursively (a
  flag/parameter, not a re-entrant call). 401/403/404/409/422/429/5xx and network failures keep today's
  `GitLabPublishException` transient path verbatim. *Test:* a stubbed GitLab returning 400-then-201 publishes a
  plain note and marks the comment published; returning 429 does **not** retry and surfaces as transient;
  returning 400-then-400 surfaces as transient exactly once (no infinite loop).
- **DPR-09 (MUST, DPT-11).** `DEPLOYMENT.md` and `README.md` record that `GITLAB_TOKEN` now additionally
  requires **read** access to the reviewed merge requests (Reporter+/`api`), that a token lacking it degrades
  silently to plain notes, and that `gateway.publish.position-anchoring-enabled=false` is the supported kill
  switch. T-11/SR-14's least-privilege wording is updated, not weakened. *Verify:* doc review at merge.
- **DPR-10 (MUST, DPT-13).** `gateway.publish.position-anchoring-enabled` has the identical default in the Java
  field and in `application.yml`, and the yml value is an env placeholder
  (`${POSITION_ANCHORING_ENABLED:true}`) so it is overridable without a rebuild — the F-PM-02 drift and F-DC-04
  "`application.yml` silently wins" lessons, both of which shipped. *Test:* an assertion that the bound property
  value under the default profile equals the Java field default; grep asserts no hard-coded non-placeholder
  value.
- **DPR-11 (MUST, DPT-01/DPT-12).** `fetchDiffRefs` is called **at most once per publish attempt** and is
  skipped entirely — no GitLab call at all — when the flag is off, when there are no unpublished comments, or
  when no unpublished comment has both a non-null `filePath` and a non-null `lineNumber`. Never per-comment.
  *Test:* a `GitLabClient` spy records exactly 0 `fetchDiffRefs` calls for a Review whose comments all lack
  line numbers, and exactly 1 for a Review with 20 anchorable comments.

### Should-fix in the same pass

- **DPR-12 (SHOULD, DPT-05/DPT-06/DPT-11).** `GET /metrics` (ADMIN-only) exposes counters for
  `positionsAnchored`, `positionsUnresolved`, `diffRefsUnavailable` and `positionRejectedByGitLab` (the 400
  fallback), following `MetricsCounters`/WOR-03's existing pattern. Rationale: every failure mode of this
  feature is silent by design, so without a counter "anchoring stopped working three weeks ago" is
  undiscoverable. *Test:* a 400-fallback publish increments `positionRejectedByGitLab` by exactly 1.
- **DPR-13 (SHOULD, DPT-06).** Land T-06/SR-08's long-tracked banner now: every published comment body is
  prefixed with a Gateway-emitted constant identifying it as AI-generated. This feature is what makes it worth
  doing — a diff-anchored thread reads as a verified finding in a way a top-level note does not. *Test:* every
  published body starts with the constant; the constant contains no LLM-derived text.
- **DPR-14 (SHOULD, §4.1).** Prefer `GET .../merge_requests/{iid}/versions?per_page=1` over the full MR object
  **if** the `base_commit_sha`/`start_commit_sha`/`head_commit_sha` ⇒ `diff_refs` equivalence is confirmed
  against the deployment's GitLab version; it carries no author-controlled free text at all. Otherwise keep the
  MR endpoint — the existing client config is sufficient either way (§4.1). Explicitly **not** required:
  `BoundedInputStream`, a new timeout property, or a new `RestClient` bean.
- **DPR-15 (SHOULD, DPT-08).** `PathLine`, `ResolvedLine`, `DiffRefs` and `DiffPosition` get masked
  `toString()`s (paths as char counts, SHAs as first-7-or-masked) in the `JobPayload`/`ClaimedJob`/`DiffChunk`
  style, and `SensitiveDtoToStringMaskingTest` is extended to cover them. The position-context `Map` is never
  passed to an slf4j placeholder (WOR-17). *Test:* the four records rendered with tainted content leak neither
  path nor full SHA; a `Map` of them does not either.

### Accepted residuals

- **DPR-16 (ACCEPTED-RISK, DPT-06/DPT-10/DPT-12).** Three residuals are accepted and recorded, not fixed here:
  (i) the LLM's **selection oracle** — it can anchor a comment to any line of the MR's own diff; bounded by
  GitLab's server-side position validation to the same MR/project, compensated by DPR-13's banner;
  (ii) `review_inputs.diff` is **never verified** against GitLab's own diff, so a CI-token holder can steer
  anchors within that same bound (an amplification of T-21's shared-CI-token residual, not a new class);
  (iii) SR-20 per-token rate limiting remains unimplemented — this feature adds one GET and up to 2× POSTs per
  comment to the publish path's cost, which does not change the SR-20 argument but adds to its justification.

---

## 6. Corrections required to the design BEFORE dev starts

1. **`buildPositionContext` must be wrapped, and `retryPublications` must gain a per-review guard (DPR-01).**
   The design says buildPositionContext "never throws" and then enumerates the cases it handles. Enumeration is
   not a guarantee; a blanket `catch (RuntimeException)` at the single call site is. Without both halves, the
   feature can permanently disable the publish subsystem — the invariant the design's own headline promises.
2. **`DiffPositionResolver` is specified as a total function with no `throw` statement (DPR-02),** with the
   hunk-header number parse explicitly guarded against `NumberFormatException` and `int` overflow. The design's
   "malformed → skip to next `@@`" is right in spirit but does not cover a *well-formed* header carrying a
   21-digit number.
3. **Path normalization becomes asymmetric with ambiguity rejection (DPR-05).** Stripping `a/`/`b/` from the
   LLM-supplied key — as the design specifies — creates a wrong-file anchor that GitLab will happily accept.
   Strip on the diff side only; drop colliding keys.
4. **The `head_sha` comparison is specified as normalized full-40-hex exact equality, with prefix matching
   explicitly forbidden in the javadoc (DPR-06),** and the "unverifiable `review.headSha`" case gets its own
   DEBUG reason. `CreateReviewRequest.headSha` is `@NotBlank` only; the design assumes more than the API
   validates.
5. **`/dev/null` and half-filled positions are added to the "never transmit" list (DPR-03),** and the
   `@JsonInclude(NON_NULL)` contract is asserted on serialized bytes. As specified, the fallback can be a
   permanent 400 loop rather than a fallback.
6. **§4.1's answer is recorded in the design: no `BoundedInputStream` for `fetchDiffRefs`,** with the reason
   (whole-exchange timeout, Jackson skip-without-materialize, three pinned fields) — so a later reviewer does
   not re-litigate it, and so the "bind exactly three fields" invariant has a stated rationale.
7. **Resolve the branch point (§0).** The design cites four classes that do not exist on `master`. Rebase onto
   the integrated line, or downgrade DPR-12/DPR-15 to new implementations and accept a duplicated SHA constant.

---

## 7. CI/CD gates

The existing SR-23 gate (`gitleaks` + `semgrep p/java,p/sql-injection,p/secrets` + SCA + `mvn verify`) covers
this branch; **no new tooling, no new dependency** (the feature adds none — `HtmlUtils`, `BufferedReader`,
Jackson and `RestClient` are all already present). Two Semgrep rules are worth adding while the feature is in
flight, both cheap and both targeting findings the gate would otherwise miss entirely:

- flag any slf4j call in `DiffPositionResolver`/`GitLabPublisher`/`GitLabClientImpl` whose argument is a path
  variable, a `Map`, or a `Collection` (DPR-04, DPR-15);
- flag `Integer.parseInt` / `Integer.valueOf` in `DiffPositionResolver` (DPR-02).

Recording the same caveat the F-DC round did: none of DPT-01..DPT-05 is machine-detectable by the configured
gate. A green gate is not evidence against them.

**Deployment note:** no migration, no schema change, no new env var. Rollback is safe in both directions — an
older JAR posts plain notes; a newer JAR against an unchanged DB works unmodified. The only operational
prerequisite is DPR-09's token scope.

---

## 8. Release gate

**Verdict: CHANGES REQUIRED — the design is sound in its core trust decisions and may proceed to implementation
*with* the eleven MUSTs below folded in. There is one blocking issue (DPT-01) and it is not a design rethink.**

The central trust move (LLM `file`/`line` as a lookup key only) is correct, the blast-radius bound the architect
claimed is **confirmed** (§4.4/DPT-10, with one correction: the CI token is the stronger attacker position, not
the LLM), the SHA pinning is appropriate defense-in-depth (§4.2), the new GET needs **no** `BoundedInputStream`
(§4.1, verified against the actual Spring bytecode), and five of the architect's judgment calls — exact-match
only, new-file-only lines, no cache, token reuse, reject-don't-sanitize — are endorsed on security grounds and
should not be revisited without a new round. What is missing is failure containment, not trust containment.

**Blocking MUSTs:** DPR-01, DPR-02, DPR-03, DPR-04, DPR-05, DPR-06, DPR-07, DPR-08, DPR-09, DPR-10, DPR-11.

**Tracked SHOULDs:** DPR-12, DPR-13, DPR-14, DPR-15.

**Accepted residuals:** DPR-16 (LLM selection oracle; unverified CI-supplied diff; SR-20 still open).

**Non-regression set to re-verify in the SAST round:** SR-08/SR-09 (comment sanitation unchanged — the `body`
field must be byte-identical to today's on both the positioned and fallback paths), SR-12/T-09 (no diff, path or
SHA in logs — DPR-04), SR-14/T-11 (GitLab token least privilege, scope widened by DPR-09), SR-15 (`https` base
URL and `followRedirects(NEVER)` on `gitLabRestClient` — unchanged, re-assert), SR-17 (no internal detail in
error bodies — this feature adds no HTTP response surface, confirm it stays that way), CSR-01 (the diff size
ceiling the resolver's safety depends on — DPR-02's premise), F-DC-06/F-DC-07 (path echo and `toString()`
masking — DPR-04/DPR-15), and the publish-path idempotency invariants (`discussion_id` + `published_at` set only
on success inside the per-comment `REQUIRES_NEW` transaction — unchanged, but the 400-retry is new code inside
that transaction).

---

Relevant files for the developer picking this up:
`src/main/java/com/review/gateway/service/GitLabPublisher.java:62-101` (the new step and the `try` it must sit
inside), `src/main/java/com/review/gateway/service/PublishRetryService.java:36-45` (the unguarded loop —
DPR-01b), `src/main/java/com/review/gateway/service/GitLabClientImpl.java:62-84` (`postDiscussion`, the
`DiscussionRequest` record, the fallback choke point),
`src/main/java/com/review/gateway/service/CommentParser.java:231-252` (`sanitizeFilePath`/`normalizeLineNumber`
— what the map key actually contains, and what it does *not* strip),
`src/main/java/com/review/gateway/service/DiffChunker.java` (the line-scan parsing style and the
`Section`/hunk-header handling to mirror, CSR-11),
`src/main/java/com/review/gateway/service/DiffSizeValidator.java:107-124` (`rejectIfAbsurdlyLarge` — the upstream
bound DPR-02's safety argument rests on), `src/main/java/com/review/gateway/config/RestClientConfig.java:29-49`
(`gitLabRestClient`: 5 s connect / 30 s exchange / redirects NEVER — §4.1),
`src/main/java/com/review/gateway/model/ReviewComment.java:39-43` (`updatable = false`, which is what makes
reading `filePath`/`lineNumber` outside the `REQUIRES_NEW` block safe),
`src/main/java/com/review/gateway/dto/CreateReviewRequest.java:14` (`headSha` is `@NotBlank` only — DPR-06),
and `docs/threat-model.md` (T-06/T-09/T-11/T-21, SR-08/SR-12/SR-14/SR-15/SR-20).
