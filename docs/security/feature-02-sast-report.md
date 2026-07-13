# AppSec SAST Report — feature/02-core-services (stage-3 service layer)

Scope: `src/main/java/com/review/gateway/service/*.java` + `service/dto/`, `exception/`,
`config/GatewayProperties.java`, and the repository additions in commit `8292a1f`. Controllers,
HTTP security config, `TokenAuthenticationFilter`, and the real `RestClient`/`GitLabClient`/
`BackendProber` implementations are **feature 03 and explicitly out of scope**. Method: manual expert
SAST against the SR-01..SR-24 checklist (`docs/threat-model.md`) + offline `mvn dependency:tree` CVE
review. No network-facing app yet → no DAST this round. Branch HEAD `3020eb6`, `mvn test` 214/214 green
(not re-run destructively; no code modified).

Verdict: **FAIL** — one in-scope MUST control (SR-21 raw-response size cap) is defined in config but
**not wired into any code path**, and the timeout/requeue control (QA-known defect #1) is inert due to a
read-only-transaction bug, defeating the SR-08/SR-16/T-08/T-23 availability guarantees. Both are
must-fix-before-merge. The rest of the layer is solid: SR-04 ownership is correctly enforced on
heartbeat/result, SR-13 (bound params) is clean, SR-08/SR-09 comment sanitation is present and correct
for comment text, SR-14 audit/log scrubbing holds, and the dependency baseline is unchanged and clean.

Severity counts: Critical 0 · High 1 · Medium 1 · Low 3 · Info 2. Plus 2 QA-known defects assessed for
security impact (1 High-availability, 1 Medium).
Must-fix before merge: **F02-01 (High)** and **KD-1 (High, QA-owned)**. All others deferrable to
feature 03 with tracking.

---

## Findings

| # | Severity | CWE / OWASP | Where | Description | Remediation | SR |
|---|----------|-------------|-------|-------------|-------------|----|
| F02-01 | **High** | CWE-770 / CWE-400, A04:2021 | `ResultProcessor.java:86-102` (`storeRawResult`); property `GatewayProperties.java:150-151` | **SR-21 raw-response cap is NOT enforced.** `Publish.maxRawResponseLength` (200 000) exists but has **zero usages** anywhere in `src/main`/`src/test` (grep-verified). `storeRawResult` persists `command.rawResponse()` straight into `review_results.raw_response` (unbounded `TEXT`) with no length check, then hands the same unbounded string to `CommentParser.parse` (`indexOf`/`lastIndexOf`/`substring` + `ObjectMapper.readTree` over the whole blob). A compromised worker (or a prompt-injected model, T-19) submits a multi-hundred-MB response → storage amplification + heap/CPU pressure on the single Gateway (SPOF). | Enforce the cap **before persist**: in `submitResult`/`ResultProcessor` reject or hard-truncate when `rawResponse.length() > publish.maxRawResponseLength` (reject with a distinct outcome so the controller returns 413/422 in feature 03; or truncate-and-flag if partial results are acceptable). Add a unit test that an over-cap `rawResponse` is not stored raw. | SR-21, T-19 |
| F02-02 | Medium | CWE-362 / CWE-384, A04:2021 | `ResultProcessor.java:117-132` (`persistCommentsAndComplete`); `V1__initial_schema.sql:130` (non-unique index on `review_comments.review_id`); `Review` has no `@Version` | **Concurrent duplicate result submission → duplicate published comments.** `review_results.review_id` is `UNIQUE` (raw result is idempotent), but `review_comments` has only a **non-unique** index and `Review` has **no optimistic-lock version**. Two overlapping `submitResult` calls for the same job (same owner) both pass the `RUNNING` guard (READ_COMMITTED, no row lock on the review in the complete step), both insert the parsed comments and both `RUNNING→COMPLETED`. Result: duplicate `review_comments` rows → each published to GitLab as a separate discussion (duplicate MR comments). The RUNNING-guard neutralises the common *sequential* retry/replay, but not a truly concurrent double-submit — reachable by a malicious worker-token holder (T-13). | Serialize the completion: `SELECT ... FOR UPDATE` on the review row (or add `@Version` to `Review`) inside `persistCommentsAndComplete`, or add a dedup guard on comment insert. The feature-03 claim/heartbeat **lease token (SR-06)** would also close this — track jointly. | SR-06, T-13 |
| F02-03 | Low | CWE-532 / CWE-215, A09:2021 | `CommentParser.java:111-114` | **SR-14 debug-log leak of raw-response fragment.** On a malformed JSON slice the code logs `malformed.toString()` at DEBUG. Jackson's `JsonParseException.toString()` includes a source excerpt (`INCLUDE_SOURCE_IN_LOCATION` is default-on in Jackson 2.x), so a slice of `raw_response` — which may quote proprietary source (A7) — lands in file logs / aggregation, contrary to "no raw_response in logs". DEBUG-level and bounded, hence Low. | Log only `malformed.getClass().getSimpleName()` (+ line/col), or construct the `ObjectMapper` with `JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION` disabled. Same for `ResultProcessor.java:74` `parseError.toString()`. | SR-14, T-09 |
| F02-04 | Low | CWE-79 / CWE-770, A03:2021 | `CommentParser.java:105,182` (`sanitize`) | **`filePath`/`lineNumber` bypass the sanitation pipeline** (this is the SR-09 length-cap gap = QA-known defect #2, plus a latent SR-08 gap). `sanitize()` escapes/caps only `text`; `candidate.filePath()` is passed through raw — not length-capped (→ `DataIntegrityViolationException` when it exceeds `file_path VARCHAR(1024)`, see KD-2) and **not HTML-escaped / mention-neutralised**. Dormant today because `GitLabPublisher` publishes only the comment *body*, but the unescaped `filePath` is persisted and will be an injection sink the moment file/line are published in feature 03. | In `CommentParser`, cap `filePath` to ≤1024 chars (drop/truncate) **and** route it through the same HTML-escape/neutralisation as `text` before building `ParsedComment`. Fixes KD-2 and pre-empts the latent XSS/markdown-injection. | SR-08, SR-09, T-06, T-19 |
| F02-05 | Low | CWE-209 / CWE-639, A01:2021 | `QueueManager.java:175-177`; `SubmitResultOutcome.ownershipMismatch(ReviewStatus)` | **Review status disclosed to a non-owner.** On an ownership mismatch `submitResult` returns `ownershipMismatch(review.getStatus())` — a worker-token holder guessing a sequential `jobId` learns another team's review status without owning the job. SR-04 core is intact (nothing is mutated), so impact is limited to minor info disclosure (IDOR-adjacent, shared-token model already ACCEPTED-RISK in the threat model). | Do not echo `review.getStatus()` on a mismatch; return an opaque 403/404 with no state. Log the mismatch (already done at WARN) for T-26. | SR-04, T-04, T-21 |
| F02-06 | Info | CWE-362, A04:2021 | `BackendDispatcher.java:45-50` + `QueueManager.java:74-98` | **Capacity-check TOCTOU (benign).** `countRunningJobsForBackend` is read before the `FOR UPDATE SKIP LOCKED` claim and the backend row is not locked, so N concurrent claims for one backend can each pass the capacity gate and transiently over-commit by up to N. No security impact at 1–10 backends / 20–30 MRs-day; correctness/fairness only. | Accept, or `SELECT ... FOR UPDATE` the backend row during claim if strict capacity is ever required. | — |
| F02-07 | Info | CWE-79 / CWE-74, A03:2021 | `CommentParser.java:173-210` (`sanitize`) | **SR-08 residual (matches T-06 ACCEPTED-RISK).** HTML-escape defangs raw HTML/`<script>`, and `@`-mentions + leading-`/` quick-actions are neutralised — but GitLab **markdown** still renders (e.g. `[x](http://evil)` phishing links, images) and GitLab cross-references `#123`/`!45`/`%ms` are **not** neutralised (only `@`). | Optional hardening per the threat model SHOULD: wrap each published comment in a fenced code block (or add the "AI-generated" banner) and extend neutralisation to `#`/`!`/`%`. | SR-08, T-06 |

---

## Assessed security impact of the two QA-known defects (not re-reported as new findings)

- **KD-1 — TimeoutManager `readOnly=true` bug → inert requeue/timeout control. Security severity: High
  (availability). MUST-FIX before merge.** `sweepStaleHeartbeats` and `enforceMaxDuration`
  (`TimeoutManager.java:43,62`) are `@Transactional(readOnly = true)`; `RetryManager.requeueOrFail`
  is `@Transactional` (propagation REQUIRED) so it **joins the read-only transaction** and its
  `reviewRepository.save(...)` UPDATE fails on the PostgreSQL read-only connection. Net effect: **stale
  `RUNNING` jobs are never requeued or failed.** A crashed/hung/malicious worker's job stays `RUNNING`
  indefinitely, and because backend load = count of `RUNNING` jobs (`BackendDispatcher`), every zombie
  job permanently consumes a capacity slot → capacity exhaustion → `/jobs/claim` stops handing out work
  → full pipeline DoS. This directly nullifies the T-08/T-23 availability mitigation the retry/timeout
  control exists to provide, and there is no self-healing path. Fix (QA/dev-owned): drop `readOnly=true`
  from the two sweep methods (they orchestrate writes), or make them selection-only and let
  `requeueOrFail` own the write transaction as `REQUIRES_NEW`. Add a Zonky integration test that a
  stale-heartbeat review actually returns to `QUEUED`.

- **KD-2 — CommentParser missing `filePath` length cap. Security severity: Medium (availability,
  DoS-adjacent).** Covered mechanically by **F02-04**. When the model emits a `filePath` > 1024 chars,
  `persistCommentsAndComplete`'s comment insert throws `DataIntegrityViolationException` **outside** the
  try/catch (only `commentParser.parse()` is guarded, `ResultProcessor.java:70-82`), so the
  `RUNNING→COMPLETED` transaction rolls back and the review is stuck `RUNNING`; it then burns all
  `max-attempts` retries (each re-claiming a backend slot) before finally `FAILED`. Reliably triggerable
  via prompt injection in the diff (T-06→T-19). Fix is the F02-04 cap.

---

## Positive verifications (checked and passed)

- **SR-04 — job ownership on heartbeat/result: PASS.** `QueueManager.heartbeat` and `submitResult`
  both load the `review_jobs` row and reject via `isOwner` (`workerId.equals(job.getWorkerId())`)
  **before any mutation**; a mismatch changes nothing and is surfaced distinctly from not-found/RUNNING.
  Both also enforce the `status == RUNNING` idempotency guard. (Residuals: F02-05 status leak; the
  SR-06 lease-token hardening and token→identity binding are correctly deferred to feature 03 — note
  that today's ownership rests on a self-declared, likely-guessable `workerId` under a single shared
  `WORKER_TOKEN`, so SR-06 remains a real feature-03 requirement, not optional polish.)
- **SR-13 — bound parameters in all SQL: PASS.** The two new native queries (`averageQueueWaitMillis`,
  `averageRunDurationMillis`) are **static, parameterless** SQL with no concatenation; `countByEventType`
  and `findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusIn` are Spring Data derived queries
  (parameterised by construction). No string-built SQL anywhere in the feature-02 diff. A `backendId`/
  `status` injection payload cannot alter query structure.
- **SR-08 — comment-text output sanitation: PASS (for comment body).** `CommentParser.sanitize` strips
  quick-action lines, caps length, neutralises `@`-mentions (ZWSP), then `HtmlUtils.htmlEscape` — correct
  order, applied to both JSON-derived and fallback comments. `<script>`/leading-`/close`/`@all` render
  inert. Residuals tracked as F02-04 (filePath) / F02-07 (markdown, `#!%`).
- **SR-09 — count/length caps: PASS (for text).** `maxCommentCount` drops excess, `maxCommentLength`
  truncates with a marker; empty/blank comments are dropped, empty result falls back to a safe INFO
  placeholder. `filePath` cap is the one gap (F02-04/KD-2).
- **SR-14 — no diff/tokens/secrets in events or logs: PASS-with-note.** `EventService.scrub` masks
  bearer/token/password/secret/apikey patterns and hard-caps `details` at 500 chars; **every** call site
  passes only short structured strings (`"attempt=2"`, `"execution started"`, `"comments=N"`,
  `"parse error: <ClassName>"`, requeue reasons) — no diff, `raw_response`, or token values reach
  `review_events`. Main-flow logs carry only ids/status. The lone exception is the DEBUG raw-response
  fragment leak in F02-03.
- **SR-10 — SSRF: N/A this layer.** No URL is constructed from `backends.url` in scope; `BackendProber`
  is an interface and `NoOpBackendProber` performs no I/O. The SSRF sink (allowlist, loopback/link-local/
  metadata rejection, redirect-disable, short timeouts) is correctly deferred to the feature-03 HTTP
  prober — remains an open SR-10 requirement there.
- **Idempotency of raw-result store: PASS.** `existsByReviewId` check + `review_results.review_id UNIQUE`
  + caught `DataIntegrityViolationException` make a replayed/racing raw-result store a safe no-op.
  (Comment-level idempotency is the F02-02 gap.)
- **State-machine integrity: PASS.** All transitions go through `StateMachine` against an explicit legal
  table; terminal states have no outgoing edges; `cancel` rejects non-cancellable statuses; publish
  finalisation re-checks `COMPLETED` + zero-unpublished under `REQUIRES_NEW` before `→PUBLISHED`. No
  state-bypass path found (no direct `setStatus` outside `StateMachine` in the service layer).
- **Unsafe deserialization: PASS.** `CommentParser` uses `ObjectMapper.readTree` (tree model) on the
  untrusted `rawResponse`; no polymorphic/default typing, no `readValue` into gadget-reachable types →
  no deserialization RCE surface.

## Dependency analysis (`mvn -o dependency:tree`, resolved versions)

Baseline unchanged from the feature-01 CLOSED-VERIFIED set; **no new or regressed versions**:
`tomcat-embed-core:10.1.55`, `spring-core/web/webmvc:6.2.19`, `spring-security-*:6.5.11`,
`jackson-databind`+`jackson-core:2.21.4`, `postgresql:42.7.11`, `hibernate-core:6.6.53.Final`,
`logback-core:1.5.34`, `snakeyaml:2.4`, `flyway-core:11.7.2` — all clear of known CVEs at 2026-07.
`jackson-databind` is now actively exercised (CommentParser `readTree` over attacker-controlled input),
but tree-model parsing with default typing off carries no deserialization CVE. **PASS.**

## Must-fix before merge vs. deferrable

- **MUST-FIX before merge:** **F02-01** (SR-21 cap — wire it, it is already configured) and **KD-1**
  (TimeoutManager read-only bug — QA/dev-owned; a broken requeue control is a security-relevant
  availability failure). **KD-2/F02-04** filePath cap is strongly recommended in the same pass (cheap,
  closes a Medium DoS).
- **Deferrable to feature 03 (with tracking):** F02-02 (close via SR-06 lease token or review row-lock),
  F02-03 (log hygiene), F02-05 (status leak), F02-07 (markdown/`#!%` hardening), F02-06 (accepted).
  Feature-03 must still deliver the deferred MUST controls SR-01/02/06/07/10/11/15/16/17/18 at the HTTP/
  security layer.

## Status of remediation

Open (this round): F02-01 (High, MUST), F02-02 (Medium), F02-03/04/05 (Low), F02-06/07 (Info); KD-1
(High, MUST — QA-owned), KD-2 (Medium — = F02-04). Closed: none (review round).

---

# Verification round — remediation commit `e400b4f` (2026-07-13)

Method: read the full `e400b4f` diff **and** the current sources (claims not trusted), re-ran the whole
suite from the local toolchain, confirmed each regression test genuinely exercises the fixed path (not a
tautology), empirically reproduced one edge case in a throwaway JVM, and checked for fix-induced
regressions. HEAD `e400b4f`, working tree clean, no production code/tests modified by me.

**Suite: `mvn test` → `Tests run: 227, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS** (aggregated
across all surefire reports; the `ux_reviews_dedup_active` / `read-only` log lines are intended
negative-path assertions, not failures). Matches the expected 227/227.

## Per-finding verdicts

| # | Sev | Prior status | Verdict | Evidence / reasoning |
|---|-----|--------------|---------|----------------------|
| KD-1 | High (avail.) | MUST | **CLOSED-VERIFIED** | `TimeoutManager.sweepStaleHeartbeats()`/`enforceMaxDuration()` no longer `@Transactional(readOnly=true)` (now plain `@Transactional`). `TimeoutManagerSpringProxyIntegrationTest` runs both as **real `@Import`-ed, AOP-proxied** beans against embedded Postgres — the only wiring that exercises the propagation defect — and now asserts the stale review actually moves `RUNNING→QUEUED` (+`RETRY` event, `swept==1`), and a new test asserts an attempts-exhausted review moves `RUNNING→FAILED` (+`FAILED` event) via the max-duration backstop. Genuine reproduce→fix→verify. No longer throws `cannot execute INSERT in a read-only transaction`. |
| F02-01 | High | MUST | **CLOSED-VERIFIED** | `ResultProcessor.capRawResponseIfNeeded` truncates to `publish.maxRawResponseLength` **before** `storeRawResult` and **before** `commentParser.parse` (the truncated `effectiveCommand` feeds both). `ResultProcessorTest.oversizedRawResponseIsTruncatedBeforePersistAndParsing` proves stored `raw_response ≤ cap`, contains the marker, and the full payload run does **not** survive; `rawResponseWithinTheCapIsStoredUnchanged` proves the no-truncation path. SR-14 preserved: only lengths (`N->limit`), never content, reach the audit note. |
| F02-03 | Low | tracked | **CLOSED-VERIFIED** | Both sinks now log `getClass().getSimpleName()` only — `CommentParser:113` and `ResultProcessor:93` — never the Jackson `toString()` source excerpt. Verified in current source. |
| F02-05 | Low | tracked | **CLOSED-VERIFIED** | `SubmitResultOutcome.ownershipMismatch()` drops the `ReviewStatus` arg (now `null`), and `QueueManager.submitResult` calls the no-arg form. A non-owner response is now indistinguishable from `NOT_FOUND`; no cross-tenant status leak. (Covered by compilation + inspection; no dedicated assertion, acceptable for a signature change.) |
| F02-04 / KD-2 | Low / Med | recommended | **CLOSED-WITH-RESIDUAL → see F02-08** | The length-cap + `lineNumber` normalisation are correctly added (`sanitizeFilePath`, `normalizeLineNumber`) and well-tested (oversized→`[truncated]`, exact-1024 kept, mentions/HTML escaped, newlines collapsed, blank/absent→null, non-positive/out-of-int-range line→null). **However the fix ordering is unsound for a fixed-width column — a new reachable residual (F02-08) reopens the same persistence crash.** |
| F02-08 | **Medium** | *new (introduced by the F02-04 fix)* | **OPEN — blocks merge** | See below. |
| F02-02 | Medium | deferred | **DEFERRED (tracked, feature 03)** | Duplicate-comment race under concurrent double-submit. Agreed deferral; close via the SR-06 claim/heartbeat lease token or a `SELECT … FOR UPDATE`/`@Version` on the review in `persistCommentsAndComplete`. |
| F02-06 | Info | deferred | **DEFERRED (accepted)** | Capacity-check TOCTOU; benign at 1–10 backends. |
| F02-07 | Info | deferred | **DEFERRED (accepted, = T-06 residual)** | Markdown links + `#`/`!`/`%` refs not neutralised (only `@`); comment body is HTML-escaped. Optional code-fence hardening in feature 03. |

## F02-08 (Medium, NEW) — HTML-escape after the length-cap re-inflates `filePath` past `VARCHAR(1024)`

- **Where:** `CommentParser.sanitizeFilePath` (`CommentParser.java:205-216`) → `capLength(singleLine, 1024)`
  **then** `neutralizeMentions` **then** `HtmlUtils.htmlEscape`. The cap is applied to the *pre-escape*
  string, but the value actually stored into `review_comments.file_path VARCHAR(1024)` is the *post-escape*
  string, which can be several times longer.
- **CWE-770 / CWE-20, A04:2021. Exploit path (T-06→T-19):** a prompt-injected diff makes the model emit a
  `"file"` value dominated by HTML-special characters. `HtmlUtils.htmlEscape` expands `"`→`&quot;` (×6),
  `&`→`&amp;` (×5), `<`/`>`→`&lt;`/`&gt;` (×4). **Empirically confirmed** in a throwaway JVM against the
  project classpath: a filePath of 1024 `"` passes the 1024-cap unchanged, then escapes to **6144 chars**;
  ~205 `&` chars alone exceed 1024. The oversized value overflows `file_path VARCHAR(1024)` →
  `DataIntegrityViolationException` thrown from `persistCommentsAndComplete`, which is **uncaught** (only
  the `commentParser.parse` phase is wrapped in try/catch, not the persist phase) → the Review is left
  `RUNNING` and the Worker's `POST /jobs/{id}/result` returns a 500. This is the **exact defect class
  KD-2/F02-04 was meant to remove** — the remediation's stated invariant ("filePath capped to the column
  width") does not hold for this reachable input.
- **Impact — Medium (down-rated from KD-2's original because KD-1 now backstops it):** with the KD-1 fix in
  place, the stuck-`RUNNING` review is now swept by heartbeat/max-duration and retried, so it is no longer
  wedged *forever* — it burns up to `max-attempts` (3) re-claims + backend capacity, then lands `FAILED`.
  Per-review, self-limiting, but attacker-triggerable and a live 500 on the worker result path. The
  existing tests miss it because every test filePath uses only `a` and `/` (neither is HTML-escaped).
- **Fix (small):** escape **before** capping, or cap the **final** escaped string to 1024 (i.e. move
  `capLength(..., FILE_PATH_MAX_LENGTH)` to be the last step, after `htmlEscape`), so the stored value is
  guaranteed ≤ column width. Add a test with a `"`/`&`-heavy filePath asserting the stored length ≤ 1024
  and the review reaches `COMPLETED`. (Note: the identical escape-after-cap ordering on the *comment text*
  is harmless — `review_comments.comment` is unbounded `TEXT`.)

## Fix-induced regression check

- **No dependency change** in `e400b4f` (no `pom.xml` touch) — baseline stays the clean feature-01 set.
- **KD-1 side effect:** the two sweeps are now a single writable transaction spanning the candidate loop
  (each `RetryManager.requeueOrFail` joins via `REQUIRED`). A poison row would roll back the whole pass,
  but every `requeueOrFail` is idempotent + status-guarded, so the next scheduled tick re-attempts safely —
  no security regression, minor robustness note only.
- **F02-01/F02-03/F02-05:** inspected; no new sink, no broadened surface. Truncation audit note carries
  only integer lengths (SR-14 intact). `ObjectMapper` still tree-model only (no deserialization surface).
- **F02-08 is the only fix-induced regression** and is called out above.

## Deferred-to-feature-03 tracking (must not be lost)

- **F02-02** (concurrent duplicate-submit → duplicate published comments) — close via SR-06 lease token
  or review row-lock/`@Version`.
- **HTTP/security-layer MUST controls** still owed at feature 03:
  **SR-01** (token env-load + length floor), **SR-02** (constant-time compare), **SR-06** (claim lease
  token), **SR-07** (per-backend token → identity binding), **SR-10** (backend-URL SSRF allowlist +
  loopback/link-local/metadata rejection, redirect-disable, timeouts), **SR-11** (edge body-cap),
  **SR-15** (HTTPS enforcement + `sslmode`), **SR-16** (per-endpoint single-role matrix), **SR-17**
  (generic error body + actuator `health`-only), **SR-18** (auth-failure logging + identity on
  security events).

## Verification verdict

**NOT APPROVED FOR MERGE — one blocker: F02-08 (Medium).**

The two designated hard blockers are genuinely and verifiably fixed: **KD-1 (High) and F02-01 (High) are
CLOSED-VERIFIED** with real, non-tautological tests, and **F02-03 / F02-05 are CLOSED-VERIFIED**. The
suite is 227/227 green. But the F02-04/KD-2 remediation applied its length cap on the wrong side of
`htmlEscape`, so for a reachable, attacker-influenceable input the stored `filePath` still overflows
`VARCHAR(1024)` and re-triggers the very persistence-crash DoS the fix targeted (empirically reproduced).
It is a one-line reorder to close. Recommend: land the F02-08 reorder (+ one quote-heavy test), then this
is APPROVE-on-re-verify. If the coordinator judges F02-08 acceptable to defer given the KD-1 backstop
now bounds it to a self-limiting per-review FAILED, downgrade to **APPROVED-WITH-TRACKED-FOLLOWUP** — but
my recommendation is to fix it now, since it is trivial and reopens a just-gated defect class.

---

# Verification round 2 — F02-08 remediation commit `2bbf736` (2026-07-13)

Method: read the full `2bbf736` diff **and** the current `CommentParser` source (claims not trusted),
confirmed the reordering in **both** sanitation pipelines, checked specifically that the length cap is
the genuine final step with no post-cap character insertion, confirmed the new tests exercise the
escaped-inflation path, and re-ran the whole suite. HEAD `2bbf736`, tree clean, no code/tests modified.

**Suite: `mvn test` → `Tests run: 231, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.**

## F02-08 — **CLOSED-VERIFIED**

- **Both pipelines reordered to `neutralizeMentions → htmlEscape → capLength` (cap LAST).** Verified in
  current source: `sanitize` (`CommentParser.java:196-198`) caps `escaped` and returns `capped`;
  `sanitizeFilePath` (`CommentParser.java:228-230`) caps `escaped` and returns it directly. The cap is
  the **final** operation in each method — nothing after it can re-inflate the value.
- **No new inflation path.** I confirmed the only post-escape steps are the cap itself. `capLength`
  returns either the input unchanged (when `≤ max`) or `substring(0, max - suffix) + "... [truncated]"`,
  whose length is exactly `max` for any `max ≥ 15` (`FILE_PATH_MAX_LENGTH=1024` and the default
  `maxCommentLength=4000` both far exceed the 15-char suffix). So the persisted `file_path` is now
  provably `≤ 1024`.
- **No injection reintroduced by capping the escaped value.** Truncating an already-escaped string can
  only cut *inside* an HTML entity (e.g. `&quot;` → `&quo`), yielding shorter **inert** text; it can
  never resurrect a raw `<`/`>`/`"`/`&`, because those were already replaced with entities *before* the
  cut. HTML-safety (SR-08) is preserved.
- **Tests genuinely exercise the escaped-inflation path** (not tautologies):
  - `CommentParserTest.filePathThatIsAllQuoteCharactersIsCappedAfterEscapingNotBeforeIt` (1024×`"` → 6144
    post-escape) and `...AllAmpersandCharactersIsCappedAfterEscapingNotBeforeIt` — assert `filePath ≤ 1024`
    + `[truncated]`.
  - `commentTextCapAppliesToTheEscapedValueNotThePreEscapeValue` — covers the comment-text pipeline
    (`maxCommentLength=50`, 100×`"`).
  - `ResultProcessorOversizedFilePathTest.filePathOfAllQuoteCharactersIsCappedAfterEscapingAndReviewCompletesNormally`
    — **end-to-end Zonky/real-Postgres**: my exact reproduce case (1024×`"` filePath) now `process()`
    does not throw, review reaches `COMPLETED`, stored `file_path ≤ 1024`. This is the DB-overflow path
    that previously wedged the review; it is now covered.
- **No regression.** No `pom.xml` change; only `CommentParser` (2 method reorders + javadoc) and tests.
  The comment-text change is behaviour-preserving for normal input (cap still applies), and moving the
  cap after escaping strictly tightens the SR-09 guarantee.

## Final per-finding status

| # | Sev | Final verdict |
|---|-----|---------------|
| KD-1 | High (avail.) | **CLOSED-VERIFIED** |
| F02-01 (SR-21) | High | **CLOSED-VERIFIED** |
| F02-03 | Low | **CLOSED-VERIFIED** |
| F02-04 / KD-2 | Low / Med | **CLOSED-VERIFIED** (length + line normalisation; the escape-inflation residual it left is now closed via F02-08) |
| F02-05 | Low | **CLOSED-VERIFIED** |
| F02-08 | Medium | **CLOSED-VERIFIED** |
| F02-02 | Medium | **DEFERRED — feature 03** (SR-06 lease token or review row-lock/`@Version`) |
| F02-06 | Info | **DEFERRED (accepted)** |
| F02-07 | Info | **DEFERRED (accepted, T-06 residual)** |

Still owed at feature 03 (HTTP/security layer MUST controls, unchanged): **SR-01, SR-02, SR-06, SR-07,
SR-10, SR-11, SR-15, SR-16, SR-17, SR-18**, plus the deferred **F02-02**.

## FINAL VERDICT: **APPROVED FOR MERGE**

All must-fix findings for the feature-02 service layer are closed and independently verified against the
current sources and a green 231/231 suite: KD-1 and F02-01 (the two High blockers), F02-03/04/05, and
the fix-induced F02-08 residual. Every "closed" claim was reproduced from code + tests, not trusted. The
remaining open items are the agreed feature-03 deferrals (F02-02 and the HTTP-layer SR controls), which
are correctly out of scope for this stage and tracked above. No new security regressions were introduced
by any remediation commit. The feature-02 service layer is cleared to merge.
