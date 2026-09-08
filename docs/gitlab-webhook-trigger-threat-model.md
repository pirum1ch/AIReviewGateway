# GitLab Webhook Diff Trigger — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**. No code exists on `feature/gitlab-webhook-diff-trigger`. This model
threat-models the approved design (inbound `merge_request` webhook → bot-is-reviewer gate → Gateway-side
GitLab REST diff fetch → integrity verification → unified-diff assembly → the *unchanged*
`ReviewService.createReview(...)`, plus an hourly reviewer sweep as a delivery-failure backstop).

It **extends** `docs/threat-model.md` (SR-01..SR-24), `docs/worker-threat-model.md` (WSR-01..WSR-18),
`docs/prompt-manager-threat-model.md` (PMT-xx/PMR-xx) and `docs/structured-review-output-threat-model.md`
(SOT-xx/SOR-xx). It rewrites none of them. New finding IDs use the previously-unused prefixes
**WHT-xx** (threats) and **WHR-xx** (security requirements); the SAST round on this branch should use
`F-WH-xx` in `docs/security/feature-gitlab-webhook-diff-trigger-sast-report.md`.

Methodology: STRIDE per element/flow + OWASP Top 10 (2021) + CWE. Risk = qualitative Likelihood × Impact
(Critical/High/Medium/Low). Every requirement is MUST / SHOULD / ACCEPTED-RISK.

**Framing note that drives most of the ratings.** Two structural changes, not one:

1. **The Gateway gains its first inbound endpoint from an external system**, authenticated by a secret
   *the Gateway does not issue* and that lives in a GitLab project's hook configuration. Every existing
   inbound caller (CI, Worker, Admin) is an internal component holding a self-issued token; the webhook
   caller is GitLab — and, on the wire, anyone who can produce the right header.
2. **The Gateway becomes the author of the diff, not its recipient.** Previously the CI runner produced
   `git diff` output and the Gateway treated it as opaque input. Now the Gateway *reconstructs* unified
   diff text from GitLab's structured JSON — and that reconstructed text is what `DiffChunker` parses
   into the **v3 per-file coverage list** that the whole Structured Review Output guarantee rests on
   (`CLAUDE.md`, `README.md` §4.5/§6.1c). A file that is silently truncated, or a `diff --git` header
   forged out of an attacker-chosen filename, does not produce an error — it produces a *green review of
   code nobody looked at*. That is the same failure class as PMT-06 ("a security control that silently
   degrades to no control while reporting success") and it is why WHT-08 and WHT-09 are the two findings
   to block on.

Hard constraints respected: no new infrastructure (no Redis/queue/WAF), zero new Flyway migrations,
`ReviewService.createReview` contract unchanged, Worker untouched, single Gateway instance (so in-memory
rate limits and single-flight guards are correct and sufficient).

---

## 1. Decomposition — new elements, boundaries, flows

### New components (planned)

| Element | Role |
|---|---|
| `WebhookController` | `POST {gateway.webhook.path}`; parses a `merge_request` event, extracts *only* `(projectId, mrIid)`, delegates |
| `GitLabWebhookSecretFilter` (`config/`) | Validates `X-Gitlab-Token` against `gateway.webhook.secret-token` |
| `WebhookReviewTriggerService` | Orchestrator, shared by webhook and sweep: fetch → verify → assemble → `ReviewService.createReview` |
| `DiffAssembler` | Pure transformer: GitLab per-file JSON → unified-diff text with **synthesized** `diff --git` / `---` / `+++` / mode lines |
| `DiffIntegrityVerifier` | Fail-closed checks: whole-MR `overflow`, `compare_timeout`, per-hunk self-consistency, empty-diff-on-modified-file |
| `ReviewerSweepService` + `ScheduledJobs` tick | Hourly backstop against GitLab silently auto-disabling the hook |
| `gitLabDiffRestClient` + `gateway.gitlab.diffToken` | Third GitLab credential, `read_api`, structural copy of `gitLabPromptRestClient` |
| `GatewayProperties.Webhook` | `enabled` (kill-switch, default `false`), `secret-token`, `bot-username`, `path` |
| `DiffFetchUnavailableException` / `DiffIntegrityException` | Transient (502) vs. deterministic (422) |

### New trust boundaries

| # | Boundary | Channel | Trust posture |
|---|---|---|---|
| **WHTB-HOOK** | GitLab (or anything that can reach the port) → Gateway | `POST {path}` + `X-Gitlab-Token` | **New, inbound, external.** The body is *entirely* attacker-authored once the secret is known or the endpoint is reachable. Nothing in the body may be treated as a fact — see WHT-02. |
| **WHTB-DIFF** | Gateway → GitLab REST, `read_api` token | HTTPS | Outbound like PMTB-CORP/PMTB-PROJ, but the **target is selected by webhook data**, not by deploy-time config. PMT-24's "config is trusted because it is deploy-gated" reasoning explicitly does **not** carry over. |
| **WHTB-ASSEMBLE** | GitLab per-file JSON → assembled diff text | in-process | **The critical one.** Repo-controlled strings (`new_path`, `old_path`, hunk text, `a_mode`) become *structural syntax* of a format the Gateway then parses back. Delimiter-forging surface, exactly F-DC-02's class. |
| **WHTB-NOTES** | Gateway ← MR notes (anti-duplicate read) → MR discussion (diagnostic write) | HTTPS, write token | Reads **attacker-writable** data to make a control-flow decision, and writes Gateway-internal state into a UI readable by every project member. |
| **WHTB-SWEEP** | GitLab MR list (bot as reviewer) → self-driven work | HTTPS, scheduler | An unbounded, org-wide work source whose size is set by *anyone who can add the bot as a reviewer*. |

### Data flow (extends the DFD in `docs/threat-model.md` §1)

```
   WHTB-HOOK
 ┌──────────┐  (W1) POST {path}  X-Gitlab-Token: <secret>
 │  GitLab  │ ──────────────────────────────────────────►┌──────────────────────────────┐
 │  (or any │        body = {project.id, object_attributes.iid, ...}                     │
 │  client) │ ◄──────────────────────────────────────────│ GitLabWebhookSecretFilter    │
 └──────────┘  (W2) coarse 200/202 (never an oracle)     │ WebhookController            │
                                                          └──────────────┬───────────────┘
   (Wsweep) hourly: GET /merge_requests?reviewer_username  ──►            │ (projectId, mrIid) ONLY
            [ReviewerSweepService]                                        ▼
                                                          ┌──────────────────────────────┐
                                                          │ WebhookReviewTriggerService  │
   WHTB-DIFF  (W3) GET /projects/{id}/merge_requests/{iid} │  ── reviewers[] re-checked   │
              (W4) GET /repository/compare?from=&to=       │     from THIS response, not  │
              (W5) GET /merge_requests/{iid}/changes       │     from the webhook body    │
                   (deprecated; `overflow` only)           └──────────────┬───────────────┘
                        ▲                                                 │ per-file JSON
                        │ gateway.gitlab.diffToken (read_api)             ▼
                        │                                  ┌──────────────────────────────┐
                     GitLab ◄──────────────────────────────│ DiffIntegrityVerifier        │ fail-closed
                        │                                  │ DiffAssembler   (WHTB-ASSEMBLE)│
   WHTB-NOTES (W6) GET  /merge_requests/{iid}/notes        └──────────────┬───────────────┘
              (W7) POST /merge_requests/{iid}/discussions                 │ assembled unified diff
                   (diagnostic, WRITE token)                             ▼
                                                   ReviewService.createReview(...)  ── UNCHANGED ──►
                                                   dedup → PromptManager → DiffChunker → v3 coverage list
                                                   → review_jobs → Worker → structured result → publish
```

**The chain that matters end to end:** a filename or a hunk body in someone's MR → GitLab JSON → text the
Gateway synthesizes → `DiffChunker.scanByDelimiter(diff, "diff --git ", …)` (`DiffChunker.java:387,563`) →
`ChunkPlan.filePaths()` → the v3 coverage list rendered into the prompt and enforced on receipt. Anything
that can move a byte in that chain can move what the coverage guarantee actually guarantees.

---

## 2. Assets

| # | Asset | C | I | A | Where | Notes |
|---|---|:-:|:-:|:-:|---|---|
| **WHA1** | `gateway.gitlab.diffToken` (`read_api`, org-wide read) | **H** | H | — | config/env, `gitLabDiffRestClient` | Leak = read every project the token can see. Same impact class as PMA3/PMT-09; third credential to rotate. |
| **WHA2** | `gateway.webhook.secret-token` | **H** | **H** | — | config/env **and GitLab hook settings** | The only thing standing between the internet and "make the Gateway fetch and review project X". Not self-issued; shared with an external system. |
| **WHA3** | **Fidelity of the assembled diff** | — | **H** | — | `DiffAssembler` output → `review_inputs.diff` | *The* new integrity asset. Its corruption is silent and defeats the v3 coverage guarantee. |
| **WHA4** | Trigger authorization decision ("is the bot a reviewer?") | — | **H** | — | `WebhookReviewTriggerService` | Decides which projects' source code the Gateway pulls in and which MRs consume LLM capacity. |
| **WHA5** | Gateway availability (SPOF) | — | — | **H** | single instance | Now driven by an external, arbitrarily-frequent trigger + a synchronous multi-call GitLab fetch on the request path (PMT-12 recurrence). |
| **WHA6** | LLM backend capacity (Worker-minutes) | — | — | **H** | `review_jobs` | Trigger population widens from "CI_TOKEN holders" to "anyone who can add a reviewer to an MR the bot can see". |
| **WHA7** | Source code of newly-reachable projects | **H** | — | — | `review_inputs.diff` | Diffs of projects that never enrolled in CI-side review now land in the Gateway DB (SR-18/SR-22 scope grows). |
| **WHA8** | MR discussion channel (diagnostic comments) | M | M | M | `GitLabClient.postDiscussion` (write token) | Attacker-triggerable output into a shared UI; also a spam/oracle surface. |
| **WHA9** | Operator visibility of "this MR was never reviewed" | — | **H** | — | logs + `/metrics` only (by design, no `review_events` row) | The deterministic-failure path leaves **no row in the source of truth** — see WHT-21. |

---

## 3. STRIDE threats — WHT-01..WHT-27

"New" = introduced by this feature. "Amp" = pre-existing residual whose impact this feature amplifies.

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | Status |
|----|--------|-------------|-----------|----------|:---:|--------|
| **WHT-01** | Spoofing | CWE-798, CWE-306, CWE-208 / A07 | `GitLabWebhookSecretFilter` | A single static shared secret, no rotation, no signature, no replay window, sent as a plaintext header on every delivery. Unlike the three self-issued bearer tokens, this one is **also stored in GitLab** (project hook settings) and is therefore exposed to a second system's access-control and backup surface, and to whoever configures the hook. Knowing it = full control of the trigger. A non-constant-time compare re-opens SR-02 in a *new* filter. | **High** | **Needs mitigation** (New) |
| **WHT-02** | Spoofing / Elevation | CWE-807, CWE-290, CWE-345 / A01 | `WebhookController` reviewer filter | **The load-bearing one for authorization.** If "bot ∈ reviewers" is read from the webhook body (`reviewers[]`, `object_attributes.*`, `changes.reviewers`), the authorization decision is made on attacker-authored JSON: one forged delivery naming any `project.id` makes the Gateway fetch, store and review a project the bot was never added to. Matching by **username string** (`ai-review-bot`) rather than the numeric user id compounds it: GitLab usernames are renameable and the freed name is re-claimable, so a display-name/username swap silently re-points the gate. | **High** | **Needs mitigation** (New) |
| **WHT-03** | Tampering (replay) / DoS | CWE-294, CWE-405 / A04 | webhook → fetch path | A captured (or simply repeated) delivery is replayed N times. The existing dedup key cannot short-circuit it: `head_sha` is only known *after* W3/W4, so **every** replay pays a full MR fetch + a potentially multi-MB `/compare` + assembly + verification before dedup runs. ~200 bytes in ⇒ megabytes of authenticated upstream traffic and CPU out. Classic amplification against a SPOF. | Medium | **Needs mitigation** (New) |
| **WHT-04** | Tampering | CWE-362 / A04 | webhook + sweep concurrency | GitLab's own delivery retry, a manual "Test", and the hourly sweep can process the same `(project, MR)` simultaneously. Correctness is saved by the unique-violation path already in `createReview`, but only after both paths have done all the expensive work; a duplicated `postDiscussion` on the failure path is *not* covered by any unique constraint. | Medium | **Needs mitigation** (New) |
| **WHT-05** | SSRF / Tampering | CWE-918, CWE-88, CWE-20 / A10 | `GitLabClient` new methods | PMR-13 discipline (templated path segments) was defense-in-depth for Prompt Manager because its values came from deploy config (PMT-24). Here `projectId`/`iid`/`baseSha`/`headSha` come from a webhook body and from GitLab responses — so the same discipline is now **load-bearing**. `/repository/compare?from={base}&to={head}&unidiff=true` additionally puts values in the **query string**: a concatenated `from=` lets an attacker inject `&straight=true`, `&unidiff=false`, or extra pagination params — *silently changing which diff is returned*. That is a query-parameter-injection with an integrity impact, not merely an SSRF. | **High** | **Needs mitigation** (New; SR-10/PMR-13 extension) |
| **WHT-06** | Elevation / Info disclosure | CWE-250, CWE-269, CWE-441 / A01 | `diffToken` scope + "reviewer is the only allowlist" | `read_api` is org-wide read (repos, MRs, issues, snippets) for everything its owner can see. The design's only gate is "the bot is a reviewer on this MR" — i.e. **any user with Developer on any project the bot can see can unilaterally enroll that project**, pulling its proprietary diffs into the Gateway DB (WHA7) and onto every Worker that claims the job (T-05 residual). Combined with WHT-02 the gate disappears entirely and the token reads anything. | **High** | **Needs mitigation** (New; Amp of PMT-09/T-11) |
| **WHT-07** | Info disclosure | CWE-204, CWE-209 / A01, A05 | webhook response codes, error bodies | Distinguishable responses (404 project vs 403 no-access vs 422 integrity vs 502 fetch) turn one forged delivery into a **cross-project existence/reachability oracle** for the whole instance — the PMT-08 oracle, now callable by anyone holding the webhook secret rather than the CI token. | Medium | **Needs mitigation** (New; Amp of PMT-08) |
| **WHT-08** | Tampering | CWE-393, CWE-354, CWE-636 / A08 | `DiffIntegrityVerifier` × v3 coverage list | **Silent truncation defeats the coverage guarantee.** GitLab 16.3.9 exposes *no* per-file truncation signal (empirically confirmed). If a file's trailing hunks are dropped on a clean boundary, hunk self-consistency passes, the file is still in the coverage list, the model dutifully reports "no findings" for the part it saw, and the strict receipt-side coverage check passes. The end state is a **green security review of code that was never sent to the model** — indistinguishable, from every dashboard, from a real clean review. Whole-MR `overflow`/`compare_timeout` are the only current backstops and neither is per-file. | **High** (Critical in effect for the control this system *is*) | **Needs mitigation + blocking empirical probe** (New) |
| **WHT-09** | Tampering (delimiter forging) | CWE-74, CWE-116, CWE-93 / A03 | `DiffAssembler` header synthesis | The Gateway now *builds* `diff --git a/{old_path} b/{new_path}` from repo-controlled strings. Git permits any byte except NUL and `/` in a path component — **including LF**. A file named `ok.java␊diff --git a/safe.java b/safe.java␊@@ -1 +1 @@` forges a section boundary in the assembled text: `DiffChunker` sees two sections, the coverage list names a file that does not exist, and the attacker's real hunk body is attributed to a different, innocent-looking path. This is F-DC-02 / PMR-02's lesson arriving through a new door — and here it directly rewrites the coverage list the v3 guarantee is built on. `a_mode`/`b_mode` rendered into `old mode`/`new mode` lines are the same class. | **High** | **Needs mitigation** (New) |
| **WHT-10** | Tampering | CWE-20, CWE-116 / A03 | hunk body pass-through | GitLab's `diff` field is raw hunk text. A well-formed unified diff prefixes every content line with `' '`/`'+'`/`'-'` (so a source line reading `diff --git …` is harmless), but nothing in the design *verifies* that invariant. A truncation that ends mid-line, an unexpected `unidiff=true` no-op on an older instance, or a future concatenation bug produces an unprefixed line at column 0 — which is both a forged delimiter (WHT-09) and an undetectable content corruption. | Medium | **Needs mitigation** (New) |
| **WHT-11** | Tampering (fail-open) | CWE-636, CWE-754 / A04 | hunk self-consistency arithmetic | The check is only as good as its edge cases: omitted counts (`@@ -1 +1 @@` ⇒ 1), new-file `@@ -0,0 +1,n @@`, `\ No newline at end of file` (counts toward neither side), context lines counting toward **both**, binary/mode-only files having no hunks at all. A verifier that *skips verification* when it cannot parse a header — the natural way to write this — is a fail-open branch reachable by malformed input. | Medium | **Needs mitigation** (New) |
| **WHT-12** | Tampering (fail-open) | CWE-636 / A04 | empty-diff exemptions | The plan exempts binary files and mode-only changes from "empty diff = truncation". But the empirically enumerated per-file field list is `a_mode, b_mode, deleted_file, diff, new_file, new_path, old_path, renamed_file` — there is **no `binary` flag**, so "it's binary" must be *inferred*, and an inferred exemption is precisely what an attacker steers into. Worse: an exempted file still enters the v3 coverage list, so the model is asked to review a file whose content it never received and will emit a fabricated "no findings" entry that passes the coverage check. | Medium | **Needs mitigation** (New) |
| **WHT-13** | Tampering | CWE-393 / A04 | pagination | `/repository/compare` returns one response; `/merge_requests/:iid/diffs` is paginated. Any use of the paginated endpoint that does not follow `X-Next-Page` to exhaustion drops whole files with a 200 OK — the same silent-loss end state as WHT-08, from a much more ordinary bug. | Medium | **Needs mitigation** (New) |
| **WHT-14** | DoS | CWE-770, CWE-400 / A04 | inbound webhook body | `RequestBodySizeLimitFilter` caps `/reviews`, `/jobs/{id}/result` and `/jobs/{id}/fail` — **and nothing else** (`RequestBodySizeLimitFilter.java:48-50`). A new POST endpoint that is not added to that filter has *no* body cap, on the SPOF Gateway, reachable by whoever holds the webhook secret. | Medium | **Needs mitigation** (New; SR-11 gap) |
| **WHT-15** | DoS | CWE-789, CWE-400 / A04 | `/repository/compare` response read | **The whole SR-11 edge defense is bypassed by design on this path**: the megabytes no longer arrive in the inbound request, they arrive in an *outbound fetch response*. A large MR (or a hostile/compromised GitLab, or a redirect) returns an arbitrarily large JSON; binding it with Jackson buffers it whole. This is F-DC-01 → PMT-13 → here: the same "cap evaluated after buffering" mistake, third feature running. | **High** | **Needs mitigation** (New) |
| **WHT-16** | DoS / Abuse of function | CWE-770, CWE-799 / A04 | trigger population | Every push to an enrolled MR is a new `head_sha`, so dedup never helps; add/remove-reviewer cycles re-trigger at will. The set of people who can burn LLM capacity goes from "holders of `CI_TOKEN`" to "every developer with an MR the bot can see". No per-project or global creation limit exists in the design. | Medium | **Needs mitigation** (New; Amp of T-16) |
| **WHT-17** | DoS | CWE-770, CWE-1050 / A04 | `ReviewerSweepService` | `GET /merge_requests?reviewer_username=…&state=opened` is **org-wide and unbounded**. Stale MRs accumulate (people add the bot and abandon the MR); every hourly tick then performs N × (2–4 GitLab calls + full diff fetch + assembly) synchronously in a scheduler thread, and each integrity failure can post an MR comment. One `@Scheduled` tick can become an hour-long, self-inflicted GitLab hammering plus a comment storm. Overlapping ticks (Spring's default single-threaded scheduler serializes, but a long tick starves the other four jobs in `ScheduledJobs`) is a second-order effect on heartbeat sweeps and backend probes. | Medium | **Needs mitigation** (New) |
| **WHT-18** | DoS | CWE-770 / A04 | GitLab rate limits | Read storm (WHT-03/17) is bounded to `diffToken` — the credential split correctly protects publishing (PMT-21's lesson applied). But the **diagnostic comment path uses the write token**, so a repeated integrity failure across many MRs *can* starve `GitLabPublisher`'s budget and trigger GitLab's abuse ban on the publishing identity. | Medium | **Needs mitigation** (New) |
| **WHT-19** | Info disclosure | CWE-209, CWE-497 / A05 | diagnostic MR comment | The comment is composed from internal failure state and posted into a UI readable by every project member — and it is **attacker-triggerable on demand** (craft an MR that always fails integrity). Anything interpolated (exception class/message, `RestClientException` text carrying the request URI + query params, file paths, file counts, endpoint names, GitLab status codes) becomes a free internal-behavior oracle. SR-17/PMR-26 already forbid exactly this for HTTP bodies; the MR comment is a *wider* audience than the HTTP caller. | Medium | **Needs mitigation** (New) |
| **WHT-20** | Tampering | CWE-807, CWE-345 / A01 | notes-based anti-duplicate logic | The "have I already commented on this head_sha?" decision reads **MR notes — data any project member can write and delete**. Pre-posting a note that matches the detection heuristic permanently suppresses the only MR-visible signal that reviews are silently being skipped; deleting the bot's note forces repeated comments (spam). Control flow driven by attacker-writable external state. | Medium | **Needs mitigation** (New) |
| **WHT-21** | Repudiation | CWE-778, CWE-223 / A09 | deterministic-failure path (no `review_events` row) | By design, an integrity failure produces **no row anywhere in PostgreSQL** — log line + MR comment only. That contradicts "PostgreSQL is the single source of truth" for a security-relevant outcome, and after log rotation the question "was MR 143 at sha X ever reviewed, or silently skipped?" becomes unanswerable. Combined with WHT-20 (the MR-side signal is suppressible), a determined actor can make the skip invisible on both sides. | Medium | **Needs mitigation** (New) |
| **WHT-22** | Info disclosure | CWE-532, CWE-522 / A09 | logging / `toString()` / startup validation | Three new leak channels: the `X-Gitlab-Token` header (must never be logged, incl. by any future request-logging filter), `Webhook.secretToken` and `GitLab.diffToken` in `toString()`/`configprops`/exception text, and GitLab response bodies (raw diff JSON = proprietary source) reaching a log line via a naive `log.debug("response={}")` during the empirical-discovery phase the plan explicitly schedules (`WebhookController` as a raw-body logging stub — a debugging step that writes secrets and source code to disk if not scoped and removed). | Medium | **Needs mitigation** (New) |
| **WHT-23** | Elevation (fail-open) | CWE-1188, CWE-863, CWE-289 / A01, A05 | `SecurityConfig` + new filter ordering | `SecurityConfig` currently ends `.anyRequest().denyAll()` — a good default. The proposed shape (`permitAll` on the webhook path + a *separate* secret-checking filter) moves authorization out of that chain, so any ordering/registration/path-matching mistake fails **open** instead of closed. The path-matching risk is not hypothetical: WOR-09 fixed exactly this in `RequestBodySizeLimitFilter`, where `getRequestURI()` returns the *un-decoded* path while Spring MVC routes on the decoded one (`/webhook` vs `/%77ebhook`). | **High** | **Needs mitigation** (New) |
| **WHT-24** | Security misconfiguration | CWE-636, CWE-1188 / A05 | `gateway.webhook.enabled` kill-switch | A half-disabled state (controller bean present but filter/matcher not registered, or sweep still ticking while the endpoint is off) is a fail-open configuration. The flag must be a single decision point governing endpoint registration, filter, and scheduler. | Low | **Needs mitigation** (New, cheap) |
| **WHT-25** | Tampering / DoS | CWE-20 / A03, A04 | bypass of edge bean-validation | The CI path validates at the DTO edge (`CreateReviewRequest`: `@Positive` ids, `@Pattern` on `promptVersion` — the F-SRO-09 fix). The webhook path builds `CreateReviewCommand` **in a service**, bypassing all of it. `reviews.head_sha` is `VARCHAR(64)` and `project_id`/`merge_request_id` are `BIGINT`: an over-long sha or an out-of-range id from a forged payload becomes a persistence-time 500 (or a constraint violation surfaced as a fetch failure) instead of a clean rejection. The webhook path's `promptVersion` source is also unspecified in the plan. | Medium | **Needs mitigation** (New) |
| **WHT-26** | Tampering (TOCTOU) | CWE-367 / A04 | W3 → W4 → create | A push (or force-push) between the `diff_refs` read and the `/compare` call yields a diff that does not correspond to the recorded `head_sha`. Comments are then published against line numbers of a different revision — wrong-looking review output attributed to a sha that never had it. Force-push additionally makes `base_sha` unreachable, producing a *deterministic* failure that must not be retried forever. | Low–Medium | **Needs mitigation** (New, cheap) |
| **WHT-27** | Tampering (prompt injection) | CWE-1427 / LLM01 | reachable-project expansion | T-06/PMT-01 prompt-injection-via-diff is unchanged in mechanism, but the *population* of content reaching the model grows from "projects wired into CI" to "any MR where someone added the bot". The output-channel controls (SR-08/SR-09, `CommentRenderer`) still hold; the exposure surface widens. | Low | **Accepted** (Amp of T-06), scoped by WHR-09 |

**Tally:** Critical = 0, **High = 8** (WHT-01, 02, 05, 06, 08, 09, 15, 23), Medium = 16
(WHT-03, 04, 07, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20, 21, 22, 25), Low = 3 (WHT-24, 26, 27 — WHT-26 is
Low–Medium, counted low). Total **27**. WHT-08 is rated High on the CVSS-style scale but is *the* release-blocker in effect: for a
system whose product is a security review, "silently reviews less code than it reports" is the failure
mode with no external symptom at all.

---

## 4. Deep dives

### 4.1 Direct answers to the six questions the plan left to AppSec

**(1) Is a static `X-Gitlab-Token` shared secret, unrotated, sufficient?**
**Not at the same risk level as the three existing tokens — it needs two additions, not full redesign.**
The existing bearer tokens are self-issued and held only by internal components; this one is *co-owned
by GitLab*, travels on every delivery from a system whose access control is not ours, and guards an
endpoint whose body then selects an outbound authenticated fetch target. GitLab does not offer HMAC
signing for webhooks (only this shared secret), so a signature scheme is not available without a
proxy — which the no-new-infrastructure constraint forbids. Therefore:
- **MUST** — constant-time compare via the *shared helper extracted from* `TokenAuthenticationFilter`
  (SR-02 must not fork), ≥32 chars enforced at startup. Note the deliberate contrast with the PMR-15
  amendment: `GITLAB_PROMPT_TOKEN`/`diffToken` are **GitLab-issued** fixed-format credentials so the
  32-char floor is wrong for them; `webhook.secret-token` is **operator-chosen**, so SR-01's floor is
  exactly right for it. Do not copy the wrong rule across.
- **MUST** — accept a *set* of valid secrets (SR-03's shape) so rotation is add → re-point GitLab →
  remove, with no delivery gap. This is the cheap thing that makes rotation actually happen.
- **SHOULD** — an optional source-address allowlist (`gateway.webhook.allowed-source-cidrs`, empty =
  disabled) evaluated on the **direct socket address**, and on `X-Forwarded-For` *only* when a trusted
  proxy is explicitly configured (an unconditional XFF read is a header-spoofing bypass, i.e. worse than
  no allowlist). Defense in depth, not a replacement for the secret.
- **ACCEPTED-RISK** — no per-delivery signature/nonce from GitLab; compensated by WHR-05 (replay
  handling) and by treating the body as non-authoritative (WHR-03).

**(2) SSRF — do the new values stay templated path segments?**
Yes, and the requirement is now *stronger* than PMR-13 because the inputs are no longer deploy-config.
Two concrete additions over the Prompt Manager rules: (a) `/repository/compare`'s SHAs are **query
parameters**, which PMR-13's "templated path segment" wording does not cover — they MUST go through
`UriBuilder.queryParam(...)`, never concatenation, and be pinned to `^[0-9a-f]{7,64}$` first; (b)
`projectId`/`mrIid` MUST be JSON-bound as `Long` (not `String`) and validated positive, so a
`"project":{"id":"7/../../"}` payload cannot reach a URI at all. See WHR-06.

**(3) Is "bot is a Reviewer in GitLab" an adequate allowlist for a `read_api` token?**
**No — it is an adequate *trigger*, not an adequate *authorization boundary*.** It delegates the decision
"which repositories may this Gateway read and store" to every Developer in the organization. Add a
Gateway-side project gate mirroring the `GatewayProperties.Prompt.Project.overrides` pattern
(deploy-time `@ConfigurationProperties` only, with the PMT-24 javadoc warning verbatim): non-empty
`gateway.webhook.allowed-project-ids` ⇒ strict allowlist; empty ⇒ allow-all **with a loud startup WARN
and a documented acceptance**. Independently, `diffToken` MUST be a **group access token scoped to the
group under review**, `read_api`, with an expiry — never a personal/admin token (that is the difference
between "worst case: the reviewed group leaks" and "worst case: the instance leaks").

**(4) Is a diagnostic MR comment an acceptable disclosure surface?**
**Yes, but only as a fixed, constant-text template with a closed reason vocabulary.** It is the right
place for the signal (the person who can fix an oversized-file MR is the MR author), and it is strictly
better than failing silently. The discipline is SR-17/PMR-26, one audience wider: the comment may contain
only (a) a Gateway-constant sentence, (b) one reason code from a closed enum
(`DIFF_TOO_LARGE_OR_TRUNCATED` | `DIFF_UNAVAILABLE` | `DIFF_UNSUPPORTED_CONTENT`), (c) the `head_sha`
(already public in that MR), (d) a constant "what to do" line. **Never**: exception text or class names,
GitLab status codes, endpoint names/URIs, file paths, file/hunk counts, byte sizes, token or config
values, Gateway internal identifiers. Because the text is 100 % Gateway-authored, no output sanitization
is needed — which is itself the reason to keep it 100 % Gateway-authored.

**(5) Does the existing dedup key cover webhook redelivery?**
**For correctness, yes — for cost, no.** `(project_id, merge_request_id, head_sha)` plus the
unique-violation branch in `createReview` already makes duplicate creation impossible, including under
concurrency; nothing new is needed there and nothing should be reinvented. The gap is that dedup sits
*after* the expensive part: `head_sha` is only known once W3/W4 have run, so every redelivery pays the
full fetch (WHT-03). Fix it with a **cheap pre-fetch fast path**, not with new state: read the payload's
`object_attributes.last_commit.id` as an *untrusted hint only*, and if
`existsByProjectIdAndMergeRequestIdAndHeadSha(...)` says a Review already exists for it, return the coarse
200 without any GitLab call. The hint is never persisted and never becomes the Review's `head_sha` — that
still comes exclusively from server-fetched `diff_refs` (WHR-03).

**(6) Payload authenticity beyond the shared secret / replay.**
Resolved by making the payload structurally non-authoritative rather than by authenticating it harder:
the body contributes **only** `(projectId, mrIid)` — two integers used to *ask GitLab a question*. Every
fact the system acts on (reviewer set, `base_sha`, `head_sha`, `diff_refs`, MR state) comes from the
Gateway's own authenticated read. A perfectly forged replay then costs at most one rate-limited fetch and
converges on the same answer GitLab would give. Add a bounded in-memory LRU of recently-seen
`X-Gitlab-Event-UUID` values (single instance, no new infra, `SHOULD`) to collapse rapid exact
redeliveries.

### 4.2 WHT-08 / 09 / 10 / 11 / 12 / 13 — diff integrity, the part to get right

This is the feature's actual risk. The controls, in the order they must run:

1. **Reject before assembling, never repair.** Any failed check ⇒ `DiffIntegrityException` ⇒ no Review
   created. A "sanitize and continue" path is forbidden here: a silently renamed path or a dropped file
   is *exactly* the failure the whole verifier exists to prevent (this differs deliberately from
   `ChunkContextRenderer`'s sanitize-and-continue posture, which operates on already-trusted-shape input).
2. **Path validation before header synthesis (WHT-09).** For every `old_path`/`new_path`: reject the MR
   if the path contains CR, LF, NUL, any Unicode Cc/Cf/Zl/Zp, a leading `-`, the literal `diff --git `,
   or a leading `--- `/`+++ `; cap at the existing path-length bound. Reuse `TextSanitizer.sanitizePath`
   as a **detector** (`sanitized.equals(raw)` ⇒ pass, otherwise reject) rather than as a transformer, and
   run `StructuredPathValidator.isEligible` for `v3` reviews so an ineligible path is rejected here,
   coherently with `ReviewService.validateStructuredOutputEligibility`, rather than three layers later.
   `a_mode`/`b_mode` must match `^[0-7]{6}$` before being rendered into `old mode`/`new mode`.
3. **Line-prefix invariant inside every hunk (WHT-10).** Every line of a hunk body must start with
   `' '`, `'+'`, `'-'` or `'\'` (the `\ No newline at end of file` marker), or be the next `@@` header.
   Any other line ⇒ reject. This single rule simultaneously kills column-0 delimiter forging, catches
   mid-line truncation, and catches a silently-ignored `unidiff=true`.
4. **Hunk self-consistency, fail-closed on parse failure (WHT-11).** Parse `@@ -a[,b] +c[,d] @@` with
   omitted-count = 1; count context lines toward both sides; `\ No newline…` toward neither; an
   unparsable header or a count mismatch ⇒ reject. Never `continue`/skip.
5. **Whole-MR signals.** `overflow` from the deprecated `/changes` and `compare_timeout` from
   `/repository/compare` ⇒ reject. A **404 on `/changes` specifically** (endpoint removed in a future
   GitLab) must produce its own distinct log message and be treated as *integrity-unverifiable* ⇒ reject,
   not as a generic network blip — the plan is right that this is handled at runtime, but the failure
   direction must be closed, not open.
6. **The WHT-08 residual — RESOLVED by the §8 probe (run against the real instance, artifacts since
   deleted).** GitLab does **not** silently drop trailing hunks — it returns a fully empty `diff` string
   for an oversized file, identically for a new 408 KB file and a heavily-modified 408 KB file (`a_mode ==
   b_mode`), on all three endpoints. Neither candidate signal helps: `changes_count` is a file *count*
   (`"1"`, no `+` — confirmed it does not react to one oversized file at all, only to file-count limits),
   and `overflow`/`compare_timeout` were both `false` throughout. What actually closes the gap: the
   modified-file case is already caught by WHR-15's existing rule (confirmed by direct test); the
   new/deleted-file case needed a real fix, now WHR-15b — a `HEAD /repository/files/{path}?ref={sha}`
   call read for its `X-Gitlab-Size` header (confirmed present and accurate: `408000` for the fixture,
   zero-byte response body) whenever `new_file`/`deleted_file` is true and `diff` is empty. This is
   *cheaper* than the raw-blob-comparison fallback the council flagged as a possible requirement — a size
   check, not a content fetch — and it is now a MUST (WHR-15b), not a contingency.
7. **Coverage-list coherence (WHT-12).** Any file the verifier exempts (binary, mode-only) MUST be
   excluded from the assembled diff's coverage-bearing sections, or the v3 coverage list will contain a
   file the model never saw. The only sound exemptions are: `diff` containing GitLab's literal
   `Binary files … differ` marker, and `a_mode != b_mode` with `old_path == new_path` and an empty diff.
   An empty `diff` with equal modes and a non-new/deleted/renamed file is a **hard reject** — that is the
   truncation case the exemption list exists to *not* swallow.
8. **Bounded reads throughout (WHT-15).** `BoundedInputStream` at
   `gateway.gitlab.diff.max-response-bytes + 1` around the `/compare` response, `Content-Length` early
   reject, and a cap on the assembled character count *before* it reaches `createReview`.

### 4.3 WHT-23 — make the inbound boundary fail closed

Do **not** ship `permitAll` + a side filter. The endpoint should reuse the mechanism that is already
proven in this codebase:

- `GitLabWebhookSecretFilter` validates `X-Gitlab-Token` and, on success, sets an `Authentication` with
  `ROLE_WEBHOOK` (mirroring `TokenAuthenticationFilter`, which likewise never rejects by itself).
- `SecurityConfig` maps the webhook path with `.hasRole("WEBHOOK")`, so the existing
  `.anyRequest().denyAll()` remains the backstop and *any* filter bug degrades to 401/403, never to an
  open endpoint. `ROLE_WEBHOOK` must be granted nothing else (SR-16's one-role-per-path rule).
- Path matching in the filter uses the decoded/normalized path exactly as `RequestBodySizeLimitFilter`
  does post-WOR-09 — or, better, no path matching at all in the filter (match on the authenticated role
  in the security chain instead), which removes the bug class entirely.
- With `gateway.webhook.enabled=false`, neither the matcher, the filter, the controller, nor the sweep is
  registered — one condition, four consequences, asserted by one test (WHT-24).

### 4.4 WHT-19 / WHT-20 / WHT-21 — the failure-signal surface

The design's instinct (tell the human in the MR) is right; the mistake to avoid is letting GitLab become
the *authority* on whether the signal was delivered. Split the two roles:

- **Authoritative signal (Gateway-side, always):** one structured ERROR log line with a fixed field set
  (`event=diff_integrity_failed project_id=… mr_iid=… head_sha=… reason=<enum>`) plus a `/metrics`
  counter keyed by reason. This never depends on GitLab state and cannot be suppressed by a project
  member.
- **Courtesy signal (GitLab-side, best-effort):** the constant-template comment from §4.1(4). The
  anti-duplicate lookup MUST filter notes by **`author.id == bot user id`** (server-side field) *and* a
  Gateway-constant marker string, never by body text alone; if the notes read fails or is ambiguous,
  choose **do not post** (avoiding spam) — safe precisely because the authoritative signal already fired.
- **WHT-21 residual:** with no `review_events` row, the DB retains nothing. Creating a synthetic
  `FAILED` Review purely for audit is rejected here (it would need a non-null diff, would occupy the
  dedup key, and would show up in `/metrics` as a real failed review — worse distortion than the gap it
  fixes). Recorded as **ACCEPTED-RISK for v1** on the condition that the structured log line and the
  metric counter both exist and are documented in `DEPLOYMENT.md` as the thing to alert on. Revisit if a
  migration ever becomes acceptable on this feature.

---

## 5. Security requirements — WHR-01..WHR-30

Testable assertions for the backend developer; AppSec re-verifies each in the SAST round on this branch.

### Inbound boundary (WHTB-HOOK)

- **WHR-01 (MUST, WHT-01/WHT-23).** `GitLabWebhookSecretFilter` compares `X-Gitlab-Token` in constant
  time using the **same helper as `TokenAuthenticationFilter`** (extract it; do not fork SR-02's SHA-256 +
  `MessageDigest.isEqual` logic into a second implementation). On success it sets an `Authentication`
  with `ROLE_WEBHOOK`; it never writes a response itself. *Test:* the extracted helper is used by both
  filters; a wrong/absent token yields 401 from the existing entry point, not from the filter.
- **WHR-02 (MUST, WHT-23/WHT-24).** The webhook path is authorized in `SecurityConfig` via
  `.hasRole("WEBHOOK")`, ahead of the surviving `.anyRequest().denyAll()`; `permitAll` is never used for
  it. With `gateway.webhook.enabled=false` the matcher, the filter, the controller mapping and the sweep
  tick are all absent. *Test:* with the filter deliberately unregistered the endpoint returns 401/403,
  not 200; with the flag off the path returns 401/403/404 and the sweep never runs.
- **WHR-03 (MUST, WHT-02).** The webhook body contributes **only** `projectId` and `mergeRequestIid`.
  The reviewer check, `base_sha`, `head_sha` and MR state come exclusively from the Gateway's own
  `GET /projects/{id}/merge_requests/{iid}` response. The bot is matched by **numeric user id**
  (`gateway.webhook.bot-user-id`), with the username kept only for logging. *Test:* a payload asserting
  `reviewers: [ai-review-bot]` for an MR whose server-fetched `reviewers` is empty creates no Review and
  performs no `/compare` call; a payload carrying a bogus `last_commit.id`/`sha` never reaches
  `review_inputs`.
- **WHR-04 (MUST, WHT-01).** `gateway.webhook.secret-token` is env-only, validated at startup as
  non-blank and **≥32 characters** (operator-chosen ⇒ SR-01's floor applies; explicitly *not* the
  PMR-15 GitLab-issued-token exemption), and accepted as a comma-separated **set** for zero-downtime
  rotation (SR-03's shape). *Test:* a 16-char secret refuses startup; two configured secrets both
  authenticate.
- **WHR-05 (SHOULD, WHT-03).** Cheap pre-fetch fast path: if the payload's `last_commit.id` (untrusted
  hint, never persisted) already has a Review via
  `existsByProjectIdAndMergeRequestIdAndHeadSha`, return the coarse success with **zero** GitLab calls.
  Plus a bounded in-memory LRU of recent `X-Gitlab-Event-UUID` values. *Test:* the same delivery replayed
  twice issues the full fetch at most once.
- **WHR-06 (MUST, WHT-05).** Every new GitLab URI uses templated path segments for ids and
  `UriBuilder.queryParam` for `from`/`to`/`unidiff`; no string concatenation anywhere in the path, host
  or query. `projectId`/`mrIid` are JSON-bound as `Long` and validated positive; both SHAs match
  `^[0-9a-f]{7,64}$` before reaching any URI. `gitLabDiffRestClient` sets
  `followRedirects(HttpClient.Redirect.NEVER)` explicitly and its own connect/read timeouts, and uses
  `gateway.gitlab.base-url` as its only host. *Test:* a `from` value of `abc&straight=true` is rejected
  by the SHA pattern; a 302 from `/compare` is not followed; Semgrep shows no concatenated URI building
  in the new client methods.
- **WHR-07 (MUST, WHT-07/WHT-19).** The webhook endpoint returns exactly two outcomes to its caller:
  a coarse success for *everything it accepted* (created, deduplicated, bot-not-a-reviewer, not an
  interesting event) and a coarse failure otherwise. It never distinguishes "project not found" from
  "no access" from "MR not found" from "integrity failed", and never echoes `projectId`, `mrIid`, a
  GitLab status code, or any GitLab response text. *Test:* four different failure causes produce
  byte-identical response bodies and status codes.
- **WHR-08 (MUST, WHT-14).** The webhook path is added to `RequestBodySizeLimitFilter` with its own
  `gateway.webhook.max-request-body-bytes` (a webhook payload is small — tens of KB is generous), matched
  on the **decoded** path per WOR-09. *Test:* an oversized body gets 413 before any parsing; a
  percent-encoded variant of the path does not bypass the cap.

### Authorization & credential blast radius (WHTB-DIFF)

- **WHR-09 (MUST, WHT-06).** A deploy-time `gateway.webhook.allowed-project-ids` gate exists and is
  enforced **before** any GitLab call: non-empty ⇒ strict allowlist (a non-listed project is a coarse
  no-op plus one INFO log line); empty ⇒ allow-all with a startup WARN naming the accepted risk. Bound
  exclusively via `@ConfigurationProperties` (no runtime mutation, no `Yaml.load()` — PMR-30), with the
  PMT-24 javadoc warning restated on the new class. *Test:* a webhook for a non-listed project performs
  zero GitLab calls; the WARN appears when the list is empty.
- **WHR-10 (MUST, WHT-06/WHT-22).** `gateway.gitlab.diffToken` is a distinct credential on a distinct
  `gitLabDiffRestClient` bean; the write token is never sent on a diff read and vice versa. Presence
  (not length — PMR-15's amendment applies: GitLab-issued) is enforced at startup, `toString()` masks it,
  and `DEPLOYMENT.md` mandates a **group access token, `read_api` only, scoped to the group under review,
  with an expiry and a rotation runbook**. *Test:* the diff client's headers carry only the diff token;
  `GatewayProperties.GitLab#toString` masks all three tokens; `SensitiveDtoToStringMaskingTest` extended.
- **WHR-11 (MUST, WHT-25).** The webhook path applies the same input constraints as the CI edge before
  calling `createReview`: `projectId`/`mergeRequestIid` positive, `headSha`/`baseSha` non-blank and
  ≤64 chars (matching `reviews.head_sha VARCHAR(64)`), `promptVersion` from
  `gateway.webhook.prompt-version` validated at startup against `^[A-Za-z0-9._-]{1,32}$` **and** against
  `gateway.review.allowed-prompt-versions`. *Test:* a 200-char sha is rejected deterministically, not as
  a 500; startup fails if the configured webhook prompt version is not allowlisted.

### Diff integrity (WHTB-ASSEMBLE) — the blocking set

- **WHR-12 (MUST, WHT-09).** Before header synthesis, every `old_path`/`new_path` is *validated, not
  repaired*: reject the whole MR if a path contains CR/LF/NUL, any Cc/Cf/Zl/Zp code point, a leading
  `-`, the literal `diff --git `, or a leading `--- `/`+++ `; equivalently
  `TextSanitizer.sanitizePath(p, max).equals(p)` must hold. For `v3` reviews `StructuredPathValidator`
  must also pass. `a_mode`/`b_mode` match `^[0-7]{6}$`. *Test:* a file named
  `"ok.java\ndiff --git a/safe.java b/safe.java"` is rejected; `DiffChunker.split` on the assembled text
  of every accepted fixture yields exactly the file set GitLab reported (assert the coverage list,
  not just the section count).
- **WHR-13 (MUST, WHT-10).** Every line inside a hunk starts with `' '`, `'+'`, `'-'` or `'\'`, or is an
  `@@` header; anything else rejects the MR. *Test:* a hunk body with a column-0 `diff --git ` line is
  rejected; a truncated-mid-line body is rejected.
- **WHR-14 (MUST, WHT-11).** Hunk self-consistency is computed exactly (omitted count = 1, context lines
  count on both sides, `\ No newline at end of file` counts on neither) and an **unparsable header
  rejects the MR** — there is no code path that skips verification for a file it could not parse.
  *Test:* table-driven cases for `@@ -0,0 +1,5 @@`, `@@ -1 +1 @@`, `\ No newline`, a deliberately
  under-counted hunk, and a garbage header — all with the expected accept/reject outcome.
- **WHR-15 (MUST, WHT-12).** The only exemptions from "empty diff on a modified file ⇒ reject" are
  (a) GitLab's literal `Binary files … differ` marker in `diff`, anchored to the **entire** diff body
  (`diff.startsWith("Binary files ") && diff.stripTrailing().endsWith(" differ") && !diff.contains("\n@@")`
  — F-WH-01 fixed an unanchored two-substring `contains` scan of the whole hunk body, which let any
  ordinary file whose diff happened to contain both words silently drop itself from the coverage-bearing
  set with no error, no log, no metric); (b) `a_mode != b_mode` with `old_path == new_path`; and
  (c) `renamed_file == true` with `old_path != new_path`, `a_mode == b_mode` and `diff == ""` — a pure
  rename with unchanged content, which GitLab represents identically to an empty-diff truncation and
  which the original exemption list omitted (F-WH-04, since it hard-rejected the *entire* MR with a
  misleading "too large or truncated" comment for an ordinary refactor). Exemption (c) is verified, not
  merely inferred, the same way WHR-15b verifies new/deleted files: `headFileSize(old_path, base_sha) ==
  headFileSize(new_path, head_sha)` — a mismatch (or either read failing, which `headFileSize` already
  treats fail-closed) rejects as truncated rather than being silently accepted. Any exempted file
  (a/b/c) is **excluded from the coverage-bearing content** handed to `createReview`. An empty `diff`
  with equal modes and none of (a)/(b)/(c) rejects. *Test:* a modified file with `diff: ""` and equal
  modes rejects; a mode-only change is accepted and does not appear in the v3 coverage list; a file whose
  diff contains "differ"-adjacent ordinary text but also has real hunks (F-WH-01's reproduction fixture)
  remains coverage-bearing; a pure rename with matching old/new sizes is accepted and excluded from
  coverage, one with mismatched sizes rejects. **Empirically confirmed (§8 probe):** this rule already
  fires correctly as designed for a heavily modified 408 KB file (`a_mode == b_mode`, `diff: ""`) — no
  change needed for the modified-file case.
- **WHR-15b (MUST, WHT-08/WHT-12 — added after the §8 probe, closes a real gap the original plan missed).**
  `new_file`/`deleted_file` are **not** unconditionally exempt from the empty-diff check. The probe proved
  GitLab returns `diff: ""` identically for a genuinely-empty new file *and* for a >200 KB new file whose
  patch was silently dropped — the two are indistinguishable from `/repository/compare`,
  `/merge_requests/:iid/changes`, or paginated `/merge_requests/:iid/diffs` alone (all three return the
  same empty string; `overflow`, `compare_timeout`, and `changes_count` are all unaffected — confirmed
  empirically, not assumed). When `new_file == true` (or `deleted_file == true`) **and** `diff == ""`,
  issue one `HEAD /projects/{id}/repository/files/{path}?ref={sha}` (`head_sha` for a new file, `base_sha`
  for a deleted one) and read the `X-Gitlab-Size` response header — confirmed present and accurate in the
  probe (`408000` for the real 408 KB fixture), with **zero response body**, so this is a cheap check, not
  a full blob fetch. `X-Gitlab-Size > 0` ⇒ reject (truncated); `== 0` ⇒ accept (genuinely empty file).
  This reuses the exact request shape `GitLabClientImpl.fetchRawFile` already issues (GET today; this is
  the HEAD sibling of the same endpoint), no new client pattern. *Test:* a new file with `diff: ""` and a
  stubbed `X-Gitlab-Size: 408000` rejects; a new file with `diff: ""` and `X-Gitlab-Size: 0` accepts and is
  excluded from coverage-bearing content per WHR-15's exclusion rule.
- **WHR-16 (MUST, WHT-08).** Whole-MR signals are fail-closed: `overflow == true` ⇒ reject;
  `compare_timeout == true` ⇒ reject; a **404 from the deprecated `/changes` endpoint** ⇒ reject with its
  own distinct, greppable log message (integrity unverifiable ≠ transient network error). *Test:* each
  signal independently produces `DIFF_INTEGRITY_CHECK_FAILED` with no Review row created.
- **WHR-17 (MUST, WHT-08) — RESOLVED by the §8 probe, requirement finalized.** The clean-boundary-
  truncation residual is **not** closed by `changes_count` (confirmed: it is a file-*count* field —
  `"1"` for the 408 KB fixture, no `+` suffix; it does not react to a single oversized file at all) nor by
  any per-file flag (none exists on this GitLab version, confirmed empirically across all three diff
  endpoints). GitLab does not silently drop *trailing hunks* as originally hypothesized — for both a new
  and a heavily-modified oversized file it returns a **fully empty `diff` string**, indistinguishable from
  a legitimately empty file by content alone. The requirement is therefore: WHR-15 (modified files — already
  correct as designed, confirmed) **plus** WHR-15b (new/deleted files — the HEAD/`X-Gitlab-Size` check,
  newly added). No further raw-blob-content verification (fetching and diffing actual file bytes) is
  required — a size-only HEAD check fully resolves the gap for the cases GitLab actually produces. *Test:*
  the probe's real oversized-file fixtures (both new-file and modified-file, saved as JSON test resources)
  are asserted to reject via WHR-15/WHR-15b respectively.
- **WHR-18 (MUST, WHT-13) — amended after F-WH-07.** Any paginated GitLab read follows `X-Next-Page` to
  exhaustion under a bounded page cap. For a **diff-bearing** read this is a hard **reject**, never a
  truncated success — but no diff-bearing content is actually paginated in this feature (`/repository/
  compare` returns one body; the paginated `/merge_requests/:iid/diffs` is deliberately not used), so
  this case does not currently arise. For a **best-effort, non-diff-bearing** read (MR notes, the
  reviewer MR-list), hitting the cap must instead be **bounded and observable**: a WARN + the read's own
  failure semantics (`listRecentNotes` treats cap-exhaustion identically to any other read failure —
  `Optional.empty()`, "ambiguous ⇒ do not post", F-WH-03; `listOpenMergeRequestsForReviewer` logs a WARN
  naming the possibly-incomplete candidate set, since the sweep is itself a best-effort backstop with no
  stronger guarantee to begin with). *Test:* a stubbed two-page response yields both pages; a diff-bearing
  stub exceeding the cap rejects (does not currently arise in this feature); a notes stub exceeding the
  cap is treated as ambiguous; an MR-list stub exceeding the cap logs the truncation WARN and still
  returns the partial, bounded result.
- **WHR-19 (MUST, WHT-15) — amended after F-WH-09.** Endpoint-agnostic, not an enumerated list (the
  original wording named `/compare`/`/changes`/`/notes`/MR-list and still missed `fetchMergeRequest`,
  which shipped as `.retrieve().body(...)` until the QA round caught and fixed it): **every** response
  read on `gitLabDiffRestClient` — without exception, present and future — is read through
  `BoundedInputStream` at `gateway.gitlab.diff.max-response-bytes + 1` with a `Content-Length` early
  reject; a new method on that client that uses `.retrieve().body(...)` instead of `exchange(...)` +
  `readBoundedBody` is a defect by construction, not merely an omission from a list. The assembled diff's
  character count is capped before `createReview`. *Test (F-DC-01 style):* a 100 MB stubbed response is
  rejected in bounded time with bounded peak allocation, for every `gitLabDiffRestClient` method.
- **WHR-20 (MUST, WHT-26).** `base_sha`/`head_sha` are taken from a **single** `diff_refs` read and passed
  unchanged to `/compare` and to `createReview`; they are never re-read or recomputed mid-flow. A
  `/compare` failure caused by an unreachable sha (force-push) is classified **deterministic** (no
  retry), not transient. *Test:* the sha recorded on the Review equals the one used for the fetch; a
  404-on-compare does not loop.
- **WHR-21 (MUST, WHT-08/WHT-09).** `DiffAssembler` output is asserted against `DiffChunker`'s actual
  parser in tests, not against a human reading of the format: for every fixture (new / deleted / renamed
  / mode-only / binary / multi-hunk / no-trailing-newline), `diffChunker.split(assembled, …)` must return
  `pathsTrusted == true` and a file-path set exactly equal to the input file set. *Test:* that equality,
  as a table-driven test, is the assembler's primary acceptance criterion.

### Availability & cost

- **WHR-22 (MUST, WHT-16/WHT-17).** Webhook-triggered creation is rate-limited in memory (single
  instance ⇒ correct): a per-project and a global bucket
  (`gateway.webhook.max-reviews-per-project-per-hour`, `…-per-hour`), plus the existing SR-20 queue-depth
  shedding. Exceeding a limit is a coarse no-op with one WARN + metric, never an error to GitLab.
  *Test:* a burst beyond the limit creates the configured number of Reviews and no more.
- **WHR-23 (MUST, WHT-17).** The sweep is bounded per tick: a max page count, a max number of MRs
  processed (`gateway.webhook.sweep.max-mrs-per-tick`), an "updated within N days" filter, a
  cheap `existsByProjectIdAndMergeRequestIdAndHeadSha` check **before** any diff fetch, and a guard so a
  tick can never overlap itself. *Test:* with 500 stubbed MRs one tick performs at most the configured
  number of fetches and returns; the other four `ScheduledJobs` ticks still run on time.
- **WHR-24 (MUST, WHT-18).** GitLab `429` is honoured (`Retry-After`) with bounded retries on the diff
  path; the diagnostic-comment path is capped globally per tick so a read-side or integrity-failure storm
  can never exhaust the **write** token's budget and stall `GitLabPublisher`. *Test:* a 429 sequence
  produces a bounded request count; N failing MRs in one tick post at most the capped number of comments.
- **WHR-25 (SHOULD, WHT-03/WHA5).** Webhook-triggered fetch+assembly runs under a bounded concurrency
  permit with a total wall-clock deadline (the PMR-19 pattern), so a slow GitLab can never consume the
  Tomcat pool and starve `/jobs/claim` and `/jobs/*/heartbeat` — the PMT-12 failure, whose blast radius is
  worse here because the trigger rate is not ours to control. *Test:* N concurrent deliveries against a
  hung GitLab stub leave `/health` and `/jobs/claim` responsive.
- **WHR-26 (SHOULD, WHT-04).** A single-flight guard keyed on `(projectId, mrIid)` prevents the webhook
  and the sweep from doing the same expensive work simultaneously. *Test:* concurrent webhook + sweep for
  one MR issues one fetch set and posts at most one diagnostic comment.

### Signals, disclosure, logging

- **WHR-27 (MUST, WHT-19).** The diagnostic MR comment is a **compile-time-constant template** plus a
  closed reason enum plus `head_sha`. No exception text, class name, status code, endpoint, path, count,
  size, config value or internal identifier ever appears in it. *Test:* the rendered comment for each
  reason is asserted byte-for-byte; a test forces a `RestClientException` with a URI-bearing message and
  asserts none of it reaches the comment.
- **WHR-28 (MUST, WHT-20/WHT-21).** The authoritative failure signal is Gateway-side and unconditional:
  one structured ERROR log line (`event`, `project_id`, `mr_iid`, `head_sha`, `reason`) plus a `/metrics`
  counter per reason. The MR comment is best-effort and its anti-duplicate lookup filters notes by
  `author.id == gateway.webhook.bot-user-id` **and** a Gateway-constant marker; an unreadable/ambiguous
  notes response ⇒ do not post. *Test:* a project-member note mimicking the marker does not suppress the
  log line or the metric; a bot-authored marker note does suppress a second comment.
- **WHR-29 (MUST, WHT-22).** `X-Gitlab-Token` is never logged (assert against the header name in log
  config and in any filter); `Webhook.secretToken` and `GitLab.diffToken` have masked `toString()` and are
  absent from actuator output; **no GitLab response body is ever logged at any level in shipped code**.
  The raw-body-logging discovery stub the plan schedules as implementation step 1 must live in a commit
  that is reverted before the branch is proposed for merge, and must redact the token header even while it
  exists. *Test:* extend `SensitiveDtoToStringMaskingTest`; a grep-style test asserts no
  `log.*(response|body|payload)` of a GitLab response in `src/main`.
- **WHR-30 (SHOULD, WHT-16/WHA7).** `DEPLOYMENT.md` documents the widened data-collection surface: any
  MR where the bot is a reviewer results in that project's diff being stored in the Gateway DB, so SR-18
  (at-rest) and SR-22 (retention) now cover projects that never opted in via CI. *Verify:* documented,
  and the retention job's scope confirmed unchanged-but-sufficient.

---

## 6. Architecture-level corrections to apply before dev starts

1. **The webhook body is a hint, not a fact — WHT-02/WHR-03.** Only `(projectId, mrIid)` survive the
   boundary; the reviewer check and both SHAs come from a server-fetched MR. Bot matched by **user id**.
2. **Authorization stays inside `authorizeHttpRequests` — WHT-23/WHR-02.** `ROLE_WEBHOOK` +
   `.hasRole("WEBHOOK")`, never `permitAll` + side filter, so `.anyRequest().denyAll()` remains the
   backstop and every filter bug fails closed.
3. **A Gateway-side project allowlist is added — WHT-06/WHR-09.** "The bot is a reviewer" is the trigger;
   it is not the authorization boundary for an org-wide `read_api` token.
4. **`diffToken` is scoped to a group, not the instance — WHR-10.** Worst case becomes "the reviewed
   group leaks", not "the instance leaks".
5. **Integrity verification is reject-only, never repair — §4.2/WHR-12..21.** Path validation *precedes*
   header synthesis; a failed check kills the MR's review rather than producing a plausible-looking diff.
6. **The assembler's acceptance criterion is `DiffChunker`'s parser — WHR-21.** The coverage list derived
   from the assembled text must equal GitLab's file list, asserted per fixture. A format that "looks
   right" is not evidence (the same discipline the grammar-budget fix established for JSON Schema).
7. **Bounded everywhere the CI path used to bound for us — WHR-08/WHR-19/WHR-22/WHR-23/WHR-25.** SR-11's
   inbound cap does not protect an outbound fetch; the trigger rate is no longer controlled by our own CI.
8. **The failure signal is Gateway-authoritative, GitLab-courtesy — §4.4/WHR-27/WHR-28.** No control-flow
   or visibility decision depends on data project members can write or delete.

---

## 7. Release gate

**Blocking MUSTs:** WHR-01, 02, 03, 04, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 15b, 16, 17, 18, 19, 20, 21,
22, 23, 24, 27, 28, 29.
**Tracked SHOULDs:** WHR-05, 25, 26, 30.
**Accepted residuals:** WHT-21 (no DB row for the deterministic-failure path — accepted for v1 *only*
with WHR-28's log + metric in place and documented as the alerting hook); WHT-27 (prompt-injection
exposure widens with the project population — mechanism unchanged, output channel still governed by
SR-08/SR-09); no per-delivery signature from GitLab (WHT-01, compensated by WHR-03/WHR-05).
**WHT-08's residual is no longer conditional** — the §8 probe ran, and found GitLab does not drop trailing
hunks silently; it returns a fully empty diff with no signal, closed by WHR-15 (modified files, confirmed
correct as designed) plus the newly-added WHR-15b (new/deleted files, `HEAD`/`X-Gitlab-Size`). The
evidence is recorded in §8 above and in the plan file; no further architecture-doc write-up is needed
before this gate is considered satisfied for WHT-08.

**Non-regression set to re-verify in the SAST round:** SR-02 (constant-time compare — one shared helper,
two filters), SR-10/PMR-13 (templated URIs, now extended to query params — WHR-06), SR-11 (edge body cap;
the new endpoint must be inside it — WHR-08), SR-16 (one role per path; `ROLE_WEBHOOK` grants nothing
else), SR-17/PMR-26 (coarse error bodies, now extended to MR comments — WHR-07/WHR-27), SR-12/PMR-25
(masked `toString()`, no secrets or GitLab bodies in logs — WHR-29), PMR-16/F-PM-10
(`followRedirects(NEVER)` on the third GitLab client), PMR-17/F-DC-01 (streaming bound, not
buffer-then-check — WHR-19), PMR-19 (bounded concurrency so a GitLab outage cannot starve `/jobs/claim`),
SOR-16/17/65 (structured-path eligibility must be evaluated coherently with WHR-12, not contradicted by
it), CSR-11/CSR-12 (`DiffChunker`'s `diff --git` detection and chunk-aware prompt versions are unchanged
by the assembler — WHR-21).

**CI gate:** no new tooling — SR-23's existing gate covers this branch. Add two Semgrep rules while the
feature is in flight: (a) flag any string concatenation building a URI or query string in the new GitLab
client methods, (b) flag `.body(String.class)`/`.body(<POJO>.class)` on the diff-fetch calls (must go
through the `exchange(...)` + `BoundedInputStream` form).

---

## 8. Blocking empirical prerequisites — ALL THREE RESOLVED

All three were run against the real instance in a dedicated throwaway project (`webhook-probe-test`,
created, probed, and deleted in the same session; the one instance-level setting temporarily changed to
allow the probe's local-network receiver, `allow_local_requests_from_web_hooks_and_services`, was reverted
to its original `false` immediately after). Findings below are ground truth, not inference from docs.

1. **Exact webhook JSON shape for reviewer changes — RESOLVED.** Captured from a real delivery (a real
   `PUT` clearing the bot from `reviewer_ids`, received by a throwaway TCP listener on the same network as
   this GitLab instance): `X-Gitlab-Event: Merge Request Hook`; `object_kind`/`event_type: "merge_request"`;
   `object_attributes.action: "update"`; current reviewers at **`object_attributes.reviewer_ids`** (id
   array); what changed at **`changes.reviewers.previous`/`.current`** (object arrays with `id`/`username`).
   WHR-03's implementation can now be written against this real shape, not a guess. **Operational
   side-finding, not a threat but worth a runbook line:** the captured delivery carried no `X-Gitlab-Token`
   header even though the hook was created with one — a `PUT` to the hook that didn't resupply `token`
   appears to drop it. Any future webhook-config-update code (e.g. a secret-rotation script) MUST resend
   `token` on every `PUT`, not just on fields being changed.
2. **How GitLab 16.3.9 actually truncates an oversized file's diff — RESOLVED.** See WHR-15/WHR-15b/WHR-17
   above for the full finding and the fix it produced (empty `diff`, no signal, closed by a `HEAD`
   `X-Gitlab-Size` check for the new/deleted-file case only). `DiffIntegrityVerifier` can now be considered
   fully specified.
3. **Confirm `reviewers[]` is present on `GET /projects/{id}/merge_requests/{iid}` — RESOLVED**, and had in
   fact already been confirmed read-only during the design phase, before this threat model was written
   (visible directly in the plan's own empirical record).

---

Relevant files for the developer picking this up: `docs/threat-model.md` (SR-01/02/10/11/16/17/18/20/22),
`docs/prompt-manager-threat-model.md` (PMR-13/15/16/17/19/25/26/30 — the closest precedent for every
outbound-GitLab requirement here), `docs/structured-review-output-threat-model.md` (SOR-16/17/65, the
coverage-list contract WHT-08/09 threaten),
`docs/security/feature-diff-chunking-sast-report.md` (F-DC-01/02/06/07),
`docs/security/feature-worker-observability-and-claim-latency-sast-report.md` (WOR-09, the decoded-path
lesson for WHR-02/WHR-08),
`src/main/java/com/review/gateway/config/SecurityConfig.java` +
`src/main/java/com/review/gateway/config/TokenAuthenticationFilter.java` (the filter/role pattern to
reuse, and the constant-time helper to extract),
`src/main/java/com/review/gateway/config/RequestBodySizeLimitFilter.java:48-50` (the cap list the webhook
path must join),
`src/main/java/com/review/gateway/config/RestClientConfig.java` (`gitLabPromptRestClient` — the bean to
copy structurally),
`src/main/java/com/review/gateway/service/GitLabClientImpl.java` (templated-URI and `BoundedInputStream`
discipline to extend to query parameters),
`src/main/java/com/review/gateway/service/DiffChunker.java:387,563` (the `"diff --git "` literal the
assembler must satisfy and WHR-21 asserts against),
`src/main/java/com/review/gateway/service/TextSanitizer.java` +
`src/main/java/com/review/gateway/service/StructuredPathValidator.java` (path validation for WHR-12), and
`src/main/java/com/review/gateway/service/ReviewService.java:143-199` (the unchanged entry point both
trigger paths converge on).
