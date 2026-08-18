# Worker Observability & Claim Latency — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**. No code for this feature exists on `fix/worker-observability-and-claim-latency`. This model threat-models the approved-in-draft `docs/worker-observability-and-claim-latency-architecture.md` (`WOC-01..WOC-44`): Worker in-progress logging (Part 1), fail-slow backend demotion (Part 2), and the new Worker-facing `POST /jobs/{id}/fail` + `review_jobs.not_before` (Part 3).

It **extends** `docs/threat-model.md` (`SR-01..SR-24`), `docs/worker-threat-model.md` (`WSR-01..WSR-18`), the diff-chunking controls in `docs/implementation-architecture.md` §14 (`CSR-08/09/14/17/18/19`) and `docs/prompt-manager-threat-model.md` (`PMR-01..PMR-30`). It rewrites none of them. Requirement prefix per the architecture doc's §0: threats **`WOT-nn`**, requirements **`WOR-nn`**.

Methodology: STRIDE per element/flow + OWASP Top 10 (2021) + CWE. Risk = qualitative Likelihood × Impact (Critical/High/Medium/Low). Every requirement is tagged MUST / SHOULD / ACCEPTED-RISK.

### Why this gets its own file rather than an edit to `worker-threat-model.md`

Deliberate call, per CLAUDE.md ("a feature gets its own `docs/<feature>-threat-model.md` if it's substantial"). Three reasons:

1. It adds the **first new Worker-facing endpoint since the original build-out**, and with it the first path by which *untrusted, Worker-supplied text* reaches `review_events.details`, `review_jobs.last_error` and the Gateway INFO log. Every `details` string written today is a Gateway-side constant. That is a genuinely new input channel and deserves a numbered, citable set of controls.
2. It is **cross-module** (Gateway *and* Worker). `worker-threat-model.md` is scoped to the Worker process and its two boundaries; `threat-model.md` is scoped to the Gateway as of V1. Parts 2 and 3 change Gateway queue/dispatch behavior, so neither file is the right single home and splitting the model across both would fragment the release gate.
3. It **changes two safety-relevant control loops** — backend outage detection and the retry/attempt budget — in ways that interact (see WOT-01). That interaction is the most important finding here and needs one place to be argued end-to-end.

### Framing note that drives the ratings

The architecture doc's own risk table asserts that "Parts 2 and 3 cover each other": Part 2 lets a dead backend stay `ACTIVE` for up to `failure-grace`, and Part 3 makes each resulting job failure cheap to recover from. **The opposite is true with the proposed defaults.** Part 3 removes the accidental ~210 s backoff *and* replaces it with a 30 s one, so a job's entire three-attempt budget is consumable in ~60 s — comfortably *inside* Part 2's 180 s demotion grace window. Today, single-probe demotion parks jobs in `QUEUED` (where, per WOC-44, they burn no attempts) within one probe interval; after this change they instead burn all three attempts against a backend that is known-dead but not yet demoted, and `ChunkCoordinator` then cascades the permanent `FAILED` to every sibling chunk (`ChunkCoordinator.java:226-237`). A 60-second `llama-server` restart on a launchd-managed Mac mini would go from "survivable" to "every in-flight Review permanently FAILED". That is WOT-01, it is a **blocking** finding, and the fix is a startup validation plus one default change — cheap, but it must land in the same branch.

---

## 1. Decomposition — new elements, boundaries, flows

### New / changed elements

| Element | Change | Module |
|---|---|---|
| `POST /jobs/{id}/fail` + `JobController.reportFailure` | **New** Worker-facing endpoint | Gateway |
| `FailJobRequest(workerId, reason, detail)` | **New** DTO carrying untrusted text | Gateway |
| `QueueManager.reportFailure` | **New**, non-`@Transactional` orchestrator (WOC-26) | Gateway |
| `RetryManager.requeueOrFail(jobId, reason, expectedWorkerId)` + `RequeueOutcome` | Owner-aware entry point (WOC-27/28) | Gateway |
| `review_jobs.last_error` | Starts being **written** for the first time (WOC-29) | Gateway |
| `review_jobs.not_before` + claim-query predicate | **New** column; changes the hottest query (WOC-40/41) | Gateway |
| `backends.probe_failed_since` | **New** column; restart-safe failure streak (WOC-10) | Gateway |
| `BackendHealthChecker.probeAll` | Re-shaped into A/B/C phases, HTTP I/O outside any transaction (WOC-14) | Gateway |
| `BackendDispatcher.resolveClaimableBackend` | *(unchanged in the doc — this model requires a change, WOR-10)* | Gateway |
| `RequestBodySizeLimitFilter` | New `/jobs/{id}/fail` pattern (WOC-33) | Gateway |
| `GatewayClient.reportFailure` | **New** outbound call, single best-effort attempt (WOC-35) | Worker |
| `JobFailureReason` enum on `LlamaException`/`AbandonJobException` | **New** classification (WOC-22) | Worker |
| New INFO log lines | `GatewayClient.claim`, `HeartbeatScheduler.tick`, `WorkerLoop.runInference`, idle summary (WOC-01..04) | Worker |

### Trust boundaries (delta on `docs/worker-threat-model.md` §2 and `docs/threat-model.md` §1)

| # | Boundary | Channel | Trust posture |
|---|---|---|---|
| **WOTB-FAIL** | Worker → Gateway, `POST /jobs/{id}/fail` | HTTPS, `Authorization: Bearer WORKER_TOKEN` | **New.** Everything in the body is untrusted: `workerId` is self-asserted (T-03/T-16), `reason` is a client-chosen string, `detail` is free text. The token is *shared across the whole Worker fleet*, so "authenticated" means "someone in the fleet, or whoever stole the token", not "the job's owner". |
| **WOTB-AUDIT** | `detail`/`reason` → `review_events.details`, `review_jobs.last_error`, Gateway log | in-process | **New.** First time attacker-influenced text is persisted into the audit trail. `EventService.scrub` (SR-12) currently only redacts token-shaped substrings and caps at 500 chars — it does **not** strip control characters, because until now it never had to. |
| **WOTB-DISPATCH** | `backends.probe_failed_since` → claim eligibility | DB column read by the health checker; **not** read by `BackendDispatcher` as designed | **New/asymmetric.** The demotion decision and the dispatch decision now consult *different* state at *different* times. WOC-13's "dispatch-neutral by construction" claim holds only at the instant of evaluation (WOT-07). |
| **WOTB-CLOCK** | `not_before` written from JVM `Instant.now()`, compared against DB `now()` | app ↔ Postgres | **New.** The architecture doc's own §2.3 already suspects container clock skew between `gateway-1` and `worker*-1`. A queue-gating predicate must not straddle two clocks. |

### Flow additions

```
 Worker: LlamaException / AbandonJobException
        │  (WOC-34: after heartbeatScheduler.stop(), before the next claim)
        ▼
 POST /jobs/{id}/fail {workerId, reason, detail}      ──► WOTB-FAIL
        │
        ▼
 JobController ─► QueueManager.reportFailure (no @Transactional)
        │            │ unlocked pre-check: exists? owned?
        │            ▼
        │       RetryManager.requeueOrFail(jobId, reason, expectedWorkerId)
        │            │ TX REQUIRES_NEW, SET LOCAL lock_timeout '3s'
        │            │ SELECT ... FOR UPDATE  →  owner re-check (WOC-27)
        │            │ attempts < max ? QUEUED (+ not_before) : FAILED
        │            │ last_error := sanitized                     ──► WOTB-AUDIT
        │            │ RETRY / FAILED event, details := "worker-reported: …"
        │            ▼ COMMIT
        │       ChunkCoordinator.recomputeAndApply(reviewId)
        │            └─ FAILED ⇒ parent Review FAILED ⇒ **all sibling chunks CANCELLED**
        ▼
   200 {"accepted": true}   (identical for applied and no-op)

 Scheduler tick ─► BackendHealthChecker (A: read | B: HTTP, no tx | C: write)
        │            probe_failed_since / last_seen / ACTIVE↔SUSPECT     ──► WOTB-DISPATCH
        ▼
   BackendDispatcher.resolveClaimableBackend  ── reads backends.status ONLY
```

The chain that matters: **one HTTP call → one attempt consumed → three calls → Review permanently FAILED → every sibling chunk CANCELLED.** That is the shortest destructive path in the system and it did not exist before this branch.

---

## 2. Assets (delta)

| # | Asset | C | I | A | Where | Notes |
|---|---|:-:|:-:|:-:|---|---|
| **WOA1** | The attempt budget / a Review's completability | — | **H** | **H** | `review_jobs.attempts` + `RetryManager` | Newly cheap to exhaust (3 HTTP calls, ~60 s). Exhaustion is *not* recoverable: `FAILED` is terminal and the dedup key only allows a fresh Review once the predecessor is `FAILED/CANCELLED/OBSOLETE` — i.e. the MR gets no AI review until CI re-runs. |
| **WOA2** | Audit-trail integrity (`review_events.details`, `review_jobs.last_error`) | L | **H** | — | Postgres | The forensic record of *who* caused a RETRY/FAILED. First time it carries caller-influenced text (WOTB-AUDIT). |
| **WOA3** | Backend outage detection (`backends.status`, `probe_failed_since`, the WOC-18 stall WARN) | L | **H** | M | Postgres + logs | This whole feature exists because a 13-minute outage was invisible. The controls that make it visible must themselves be un-suppressible by an untrusted caller. |
| **WOA4** | Gateway/Worker log stream integrity | L | **H** | M | log files / aggregation | Part 1's entire value proposition is "the operator can trust the log". Log injection defeats it precisely where it is now load-bearing. |
| **WOA5** | Queue liveness (`not_before`, claim-query predicate) | — | M | **H** | `review_jobs` | Nothing sweeps `QUEUED` jobs. A `not_before` far in the future is an untimed, unalarmed, permanent stall. |
| WA1/WA2/WA3 (inherited) | Worker token / diff / `rawResponse` | H | H | — | Worker JVM heap | Unchanged — but `detail` is a **new egress channel** out of the Worker for WA2/WA3 fragments (WOT-03). |

---

## 3. STRIDE threats — WOT-01..WOT-17

"New" = introduced by this branch. "Amp" = pre-existing residual whose likelihood or impact this branch amplifies. "Found" = pre-existing defect found while reviewing this branch, in code this branch touches.

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | Status |
|----|--------|-------------|-----------|----------|:---:|--------|
| **WOT-01** | DoS / Tampering | CWE-770, CWE-696 (wrong order of ops) / A04 | `RetryManager` × `BackendHealthChecker` | **Parts 2 and 3 compound instead of covering each other.** `failure-grace` (180 s) is longer than the time needed to burn the whole attempt budget after this change (`2 × requeue-delay + fail latency` ≈ 60 s at the proposed 30 s default). A backend that dies — or an `llama-server` restart of ≥60 s, which is routine on launchd — keeps receiving claims for up to 180 s, each of which fails in ~0.1 s and consumes one attempt. Attempt 3 ⇒ `FAILED` ⇒ `ChunkCoordinator` cascades `CANCELLED` to every sibling chunk ⇒ the whole Review is permanently dead. **Today the same event is survivable**, precisely because single-probe demotion parks the job in `QUEUED` within ≤60 s and a `QUEUED` job burns no attempts (WOC-44). This is an availability *regression* dressed as an availability improvement. | **High** | **BLOCKING** (New) |
| **WOT-02** | DoS / Elevation | CWE-284, CWE-639 (IDOR) / A01, A04 | `POST /jobs/{id}/fail` | Ownership is `workerId`-equality only, `workerId` is self-declared under a **fleet-shared** `WORKER_TOKEN`, and `jobId` is a sequential `bigserial`. Any token holder that guesses `(jobId, workerId)` — both low-entropy; `workerId` is a stable operator-chosen string like `worker-1`, and it is printed in the Gateway's own INFO claim log — can permanently destroy any Review with 3 POSTs spread over ~60 s, no claim required. The equivalent attack today (claim-and-go-silent, or forged `/result`) takes ~10.5 min and, in the `/result` case, needs a plausible LLM payload. Motive is concrete: suppressing the AI security review on one's own MR. | **High** | **Needs mitigation** (Amp of T-04/T-16; bounded by WOR-01/03/04) |
| **WOT-03** | Info disclosure | CWE-209, CWE-532, CWE-312 / A09 | Worker `WorkerLoop` catch block → `detail` | The natural implementation of WOC-34 is `reportFailure(jobId, workerId, reason, e.getMessage())` from the existing `catch (AbandonJobException \| LlamaException e)` (`WorkerLoop.java:196-198`, which already logs exactly that). But `LlamaException("Could not parse llama-server response", e)` (`LlamaClient.java:154`) wraps a Jackson `JsonParseException` whose message **quotes the offending source text** — i.e. LLM output (WA3), which may quote proprietary source. `AbandonJobException` messages embed `promptVersion` (`PromptTemplateService.java:180,194,198`). Any of these become a persisted row in `review_jobs.last_error` and `review_events.details` — a column whose V1 comment literally reads "*NEVER secrets*" — plus Gateway log files and DB backups. Straight WSR-10 / SR-12 / T-09 regression, and the *default* way a developer will write it. | **High** | **Needs mitigation** (New) |
| **WOT-04** | Tampering / Repudiation | CWE-117, CWE-93 / A09 | Gateway logging of `reason`, `detail`, `workerId` | Three injection sinks. (a) WOC-23's "unknown `reason` ⇒ WARN" will naturally log the raw string — a 32-char field is ample for `\r\n` + a forged line. (b) `detail` is covered by WOC-25 *if and only if* sanitization happens at ingress rather than deep in `RetryManager`. (c) **`workerId` has no charset or length constraint anywhere** — `HeartbeatRequest`/`ClaimJobRequest`/`SubmitResultRequest` all declare only `@NotBlank` — and it is already logged at INFO on claim (`QueueManager.java:238`) and at WARN on every ownership mismatch (`:343`, `:376`), and now on `/fail` too (WOC-31). CRLF forges log lines; ANSI/bidi (Cf) corrupts terminal rendering. This lands exactly where Part 1 is trying to make the log the operator's trusted channel (WOA4). | Medium | **Needs mitigation** (New sink + Found pre-existing) |
| **WOT-05** | Tampering | CWE-117, CWE-116 / A09 | `EventService.scrub` | `review_events.details` has never carried caller-influenced text; `scrub()` therefore only masks token-shaped substrings and caps at 500 chars, and does **not** strip Cc/Cf. WOC-30 makes this the choke point for untrusted text for the first time. Defense-in-depth at the choke point is cheap and belongs here rather than being trusted to every future call site. | Medium | **Needs mitigation** (Amp) |
| **WOT-06** | DoS | CWE-770, CWE-400 / A04 | `RequestBodySizeLimitFilter` | Two issues. (a) If WOC-33 slips, `limitFor()` returns `null` for `/jobs/{id}/fail` and the endpoint inherits **no** body cap at all — unbounded JSON straight into Jackson on the single-instance SPOF Gateway. (b) **Found:** the filter matches `PathPattern`s against `request.getRequestURI()`, which per the Servlet spec is the **un-decoded** URI, while Spring MVC routes on the *decoded* path. `POST /jobs/1/%66ail` (and equally `/jobs/1/%72esult`, `/re%76iews`) reaches the handler but matches no pattern, so the SR-11 cap is bypassed on **all three** protected endpoints. Requires a valid CI/WORKER token, which is why this is Medium and not High. | Medium | **Needs mitigation** (New (a) + Found (b)) |
| **WOT-07** | DoS / Repudiation | CWE-367 (TOCTOU), CWE-390 / A04 | WOC-13 at-capacity deferral | The "dispatch-neutral by construction" argument is correct **only at the instant of evaluation**. The deferral decision is sticky until the next probe pass (≤60 s, or ~2 min if WOC-17's guard skips one). If the running job finishes 5 s after a deferred pass, the backend is `ACTIVE`, below capacity, and **claimable — while known-dead**, for up to a full probe interval, at exactly the moment a slot frees and a claim is most likely. Combined with WOT-01 that window is enough to burn a Review. Second-order: `backendId` is self-declared (T-03), so a rogue or merely misconfigured Worker that claims jobs "on" backend X and heartbeats them holds X at capacity indefinitely, **indefinitely deferring X's demotion** — suppressing both the `SUSPECT` signal and WOC-18's stall WARN, which only fires when *zero* backends are `ACTIVE`. The one alarm this feature adds is thereby silenceable by an untrusted caller. | Medium | **Needs mitigation** (New) |
| **WOT-08** | DoS | CWE-662, CWE-1339 / A04 | `not_before` (WOC-40/41) | `RetryManager` computes `not_before` from JVM `Instant.now()`; the claim predicate compares against Postgres `now()`. The architecture doc's own §2.3 flags suspected container clock skew between `gateway-1` and `worker*-1` as an unexplained 6.5-minute discrepancy — a queue gate must not straddle two clocks. Independently: **nothing sweeps `QUEUED` jobs.** `findJobIdsWithStaleHeartbeat`/`findJobIdsExceedingMaxDuration` both filter `status = 'RUNNING'`. A `not_before` set far in the future by skew, a misconfigured `requeue-delay`, or a future bug parks a Review in `QUEUED` **forever**, with no timeout, no event and no alert — a strictly worse failure mode than the 13-minute stall this branch exists to fix. | Medium | **Needs mitigation** (New) |
| **WOT-09** | Repudiation | CWE-778, CWE-223 / A09 | rejected / no-op `/fail` reports | A `403` ownership mismatch or a no-op report writes **no** `review_events` row — only a log line. So the WOT-02 probing campaign (guessing `workerId` across sequential `jobId`s) leaves no durable trace in the audit store, and `GET /metrics` has no counter for it. Note the *counter*-argument that decides the fix: auditing rejected reports **in the DB** would hand any worker-token holder an authenticated, unbounded `INSERT` primitive on `review_events` — a DB-growth DoS strictly worse than the repudiation risk. The correct control is a metric + WARN, not a row. (This also settles §8.3 — see §4.7.) | Medium | **Needs mitigation** (New) |
| **WOT-10** | Info disclosure | CWE-532 / A09 | new INFO lines (WOC-01..04) | WOC-03 logs `systemMessages=…`. `PMR-25`'s masking lives on `JobPayload.toString()`/`ClaimedJob.toString()`; passing the **raw `List<String>`** to an slf4j `{}` placeholder bypasses it entirely and dumps every prompt section — including, via PMT-08, another project's architecture docs — into the Worker log. Same trap for `prompt.messages()`. WOC-01/02/04's stated fields (ids, counts, sizes, `elapsedSec`, `model`, `maxTokens`, `backend`) are clean as specified. | Medium (if slipped) / Low | **Needs mitigation** (New, cheap) |
| **WOT-11** | Tampering | CWE-294 (replay), CWE-362 / A07 | stale duplicate report vs. a fresh attempt | §8.4's residual. The window is closed only while `requeue-delay` exceeds the Worker's own request timeout (`network.gateway-timeout-sec` = **10 s**) — a duplicate cannot be more than one timeout late. WOC-42 states the bound as `>= network.poll-interval-ms` (**3 s**), which is the wrong quantity and would leave the window open at, say, `requeue-delay: 5s`; and the documented `requeue-delay: 0` escape hatch reopens it completely. Ownership is checked under lock (WOC-27) but ownership does **not** distinguish attempts, so the same worker re-claiming the same job is exactly the vulnerable shape. | Low (with WOR-16) / Medium (without) | **Constrained + accepted** (Amp of SR-06) |
| **WOT-12** | Repudiation | CWE-116 / A09 | `details` string as the audit discriminator (WOC-30) | §8.3 makes a free-text prefix the *only* thing distinguishing "the sweep did this" from "a worker asked for this", in a column that now also carries caller-influenced text. With `detail` sanitized and always appended last the prefix is not forgeable — but that is an emergent property of the concatenation order, not a stated invariant, and `RetryManager` appends its own `" (attempt X/Y)"` suffix on top. One refactor away from a forgeable audit discriminator. | Low | **Needs mitigation** (New, cheap) |
| **WOT-13** | DoS | CWE-400, CWE-833 / A04 | `BackendHealthChecker` phases (WOC-14/16/17) | Raising the read timeout to 10 s makes a pass cost up to `N × 13 s`. Correct only because WOC-14 moves I/O out of the transaction and WOC-17 prevents overlap — both are load-bearing, not optional. Note `SimpleAsyncTaskScheduler` with virtual threads genuinely does not serialize `fixedRate` runs, so the doc's reasoning is right. Residual: `toBodilessEntity()` on a hostile/compromised backend has no explicit response-size bound (T-19 asks for one); redirects are already `NEVER` (SR-10, verified in `RestClientConfig`). | Low | **Verify / SHOULD** (Amp) |
| **WOT-14** | Elevation of privilege | CWE-285 / A01 | `SecurityConfig` `/jobs/**` | `POST /jobs/{id}/fail` inherits `hasRole("WORKER")` correctly *by construction* — which is exactly why nobody will write a test for it. A future reordering of the `authorizeHttpRequests` chain, or a path outside `/jobs/**`, silently exposes it. Also: no CI or ADMIN token should reach it (SR-16's "exactly one role per path"). | Low | **Needs mitigation** (New, test-only) |
| **WOT-15** | DoS | CWE-770 / A04 | report spam | Assessed and **found not to warrant a dedicated limit.** Each report costs one unlocked `SELECT`, one short `REQUIRES_NEW` transaction bounded by `SET LOCAL lock_timeout = '3s'`, and — on the no-op path — zero writes. `POST /jobs/{id}/heartbeat` is strictly more expensive under the same token (it writes a `HEARTBEAT` event row per call for an owned RUNNING job, i.e. it *is* an unbounded-INSERT primitive today). So `/fail` adds no new DoS class; the real gap is SR-20 (per-token rate limiting), still an unimplemented tracked SHOULD. | Low | **Accepted** (rolled into SR-20) |
| **WOT-16** | Availability | CWE-1188 / A05 | `V4` migration | Two additive nullable columns with no default are metadata-only on PostgreSQL 11+, but `ALTER TABLE` still takes `ACCESS EXCLUSIVE` on `review_jobs` — the queue table — and will queue behind any lock held on it. Rollback tolerance is correctly asserted and is genuinely one-directional-safe (an older JAR ignores `not_before` ⇒ immediate claim ⇒ today's behavior; ignores `probe_failed_since` ⇒ single-probe demotion). `ddl-auto: validate` means the entity fields must match exactly or the app will not boot. Nothing resembling V2's double-execution hazard. | Low | **Deployment note** (New) |
| **WOT-17** | Info disclosure | CWE-209 / A09 | `review_jobs.last_error` exposure | `last_error` starts being written (WOC-29) with Worker-supplied text. It is currently absent from `ReviewStatusResponse` (which documents itself as deliberately excluding payload — SR-14/T-21). If a future convenience change surfaces it to CI callers, worker-supplied text reflects to a different trust domain — the F-DC-06 class. | Low | **Needs mitigation** (New, preventive) |

**Tally:** Critical = 0, **High = 3** (WOT-01, WOT-02, WOT-03), Medium = 7, Low = 7. Total **17**.

---

## 4. Deep dives — the seven questions from architecture §11, answered

### 4.1 The endpoint: authz, ownership-under-lock, 403/404 parity, body cap, rate limit (§11.1)

**Authz — correct, needs a test, nothing more.** `/jobs/**` → `hasRole("WORKER")` covers the new path with no config change, and `TokenAuthenticationFilter` + SR-16's one-role-per-path property hold. The only requirement is a negative test so this stays true (WOR-18).

**Ownership under lock (WOC-27) — confirmed necessary, and the doc's analysis of the race is right.** Today `RetryManager` does not look at `workerId` at all, so an unlocked pre-check would let the sweep-requeue/re-claim interleaving requeue a *healthy* attempt belonging to a different worker. The proposed shape (owner-aware overload; existing two-arg method delegating with `null` = "no expectation") is the correct minimal change and keeps `TimeoutManager`'s call sites untouched. Two additions this model requires:

- The **unlocked pre-check must not be the authorization decision.** It exists only to return `404`/`403` cheaply; the locked re-check is the control. If the two disagree, the locked result wins and the response must be derived from the locked result (WOR-19).
- `OWNERSHIP_MISMATCH` from the *locked* path must be logged at WARN **and counted** (WOR-03) — this is the detection signal for WOT-02.

**403/404 parity — keep it, and understand what it does and does not buy.** The doc says `403`/`404` are "opaque and mutually indistinguishable". They are *not* indistinguishable — they are different status codes, and a job that exists but is unclaimed (`worker_id IS NULL`) yields `403` while an unknown id yields `404`, so a token holder can enumerate which `jobId`s exist. That is exactly true of `heartbeat` and `submitResult` today and is a known residual of T-04/SR-06. What the parity *does* buy, and what must be preserved, is that **no response ever echoes `reviewId`, a Review status, or any state** — and `/fail` is deliberately stricter than `/result` here (WOC-28: `reviewId` used internally only). Keep the parity; do not "fix" the enumeration oracle asymmetrically on one of three endpoints. Record it as inherited residual (WOR-20).

**Body cap (WOC-33) — MUST, and the filter itself needs a fix.** Without the new pattern the endpoint has *no* cap (`limitFor()` returns `null`). 4096 bytes is right (`@Size(max=32)` reason + `@Size(max=500)` detail ⇒ worst case ~2 KB of UTF-8 plus JSON escaping). Separately, while adding the pattern, the raw-vs-decoded URI mismatch (WOT-06b) must be fixed, or the new cap is bypassable the same way the existing two are (WOR-08/09).

**Rate limit — not warranted for this endpoint specifically.** See WOT-15: `/jobs/{id}/heartbeat` is a strictly cheaper-to-abuse and more write-heavy primitive under the same token, so a `/fail`-only limiter would be security theatre. What *is* required is that the no-op path stay cheap: when the unlocked pre-check already shows the job is not `RUNNING`, return `200` **without** opening the `REQUIRES_NEW` transaction (WOR-15). The general control remains SR-20, still unimplemented and now carrying one more endpoint's worth of justification.

### 4.2 Untrusted `reason`/`detail` — sanitization, no control flow, log injection (§11.2)

**WOC-23 (whitelist-parse `reason`, unknown ⇒ `UNKNOWN` + WARN, never `Enum.valueOf`, never `400`) — endorsed as written.** It is the right forward-compat *and* the right security posture: a `400` would let reason-code drift break a fleet, and `Enum.valueOf` on caller-supplied text is a trivial `IllegalArgumentException`-to-500. One addition: the WARN must not print the raw unknown value (WOR-06).

**WOC-24 (no control flow depends on `reason`/`detail`) — endorsed, and it is the single most important invariant in Part 3.** It is what keeps "retry logic lives only in the Gateway" true now that the Worker speaks about failures. Note the consequence for §4.3 below: because WOC-24 forbids reason-dependent behavior, the WOT-01 fix must be derived from Gateway-side state only. It is (WOR-01/02).

**WOC-25 (sanitize + truncate server-side) — endorsed, with three tightenings.** `TextSanitizer.sanitizePath(detail, 200)` is the right primitive: it strips all Cc (including `\r`/`\n`/`\t`), all Cf (bidi/Trojan-Source), Zl/Zp and `<`/`>`, caps length, and returns `null` when nothing publishable remains. The `sanitizeSingleLine(String,int)` delegating alias is a good readability change and adds no second implementation of the F-DC-02 lesson. Tightenings: sanitize at **ingress** so the raw string never propagates (WOR-06); harden `EventService.scrub` at the choke point too (WOR-07); and — the finding the doc does not anticipate — **constrain what the Worker is allowed to put in `detail` in the first place** (WOR-05), because sanitization stops log injection but does **not** stop WOT-03's confidentiality leak: a Jackson parse-error message quoting LLM output survives Cc/Cf stripping perfectly intact.

**`workerId` (WOT-04c) is the gap nobody has closed.** It is `@NotBlank` only, on all four Worker-facing DTOs, and it is logged and persisted. A `workerId` longer than `VARCHAR(64)`/`@Column(length=64)` additionally turns `POST /jobs/claim` into a 500. Fix once, at the DTO level, for all four endpoints (WOR-06).

### 4.3 WOC-13's "dispatch-neutral" claim (§11.3) — **pushed back on, with a bounded correction**

The claim is *"the deferral is dispatch-neutral by construction and cannot mask an outage in any way that affects scheduling."* **Confirmed for the instant of evaluation; rejected as a temporal property.**

`BackendDispatcher.resolveClaimableBackend` re-evaluates `countRunningJobsForBackend >= capacity` **at claim time**, from live data. The deferral, by contrast, is decided once per probe pass and persists until the next one. The two therefore diverge the moment a running job finishes:

```
t=0    probe fails; backend at capacity (1/1) ⇒ deferred, stays ACTIVE, probe_failed_since preserved
t=5s   the running job COMPLETEs ⇒ 0/1 running
t=5s.. BackendDispatcher: status ACTIVE ✔, capacity free ✔  ⇒ CLAIMABLE, backend is dead
t≤60s  next probe pass finally demotes it
```

Up to one full probe interval of claims handed to a known-dead backend, arriving exactly when a slot frees — i.e. the highest-probability claim moment. On its own that is Low; combined with WOT-01 it is the fastest route to burning a Review's whole attempt budget.

**Correction required (WOR-10), and it is small:** make dispatch consult the same state the demotion decision uses. `BackendDispatcher.resolveClaimableBackend` declines a backend whose `probe_failed_since` is non-null and older than `failure-grace`, **regardless of persisted status**. The backend row is already loaded, so this is one field comparison. With it, the deferral becomes genuinely dispatch-neutral *by construction over time*: dispatch behaves exactly as it would have if the status flip had happened, while the status itself stays un-flapped for operator-facing purposes. It also removes the need to argue about the deferral's duration at all.

**Second, separate problem (WOR-11/12): the deferral is suppressible by an untrusted caller.** `backendId` is self-declared (T-03, accepted residual), so a Worker can pin jobs onto any backend name and heartbeat them; that holds the backend at capacity, which defers demotion indefinitely, which keeps it `ACTIVE`, which prevents WOC-18's "0 ACTIVE backends" stall WARN from ever firing. The one new alarm this branch adds is silenceable. Two cheap fixes: extend WOC-18's condition from "no `ACTIVE` backend" to "no backend that is `ACTIVE` **and** not in a past-grace failure streak", and promote WOC-19 (`probeFailedSince` on the ADMIN-only `GET /backends`) from SHOULD to MUST so a deferred-failing backend is visible without reading the DB.

With WOR-10 + WOR-11 + WOR-12 in place, **WOC-13 as designed is otherwise sound** and the at-capacity narrowing (over the rejected unconditional form in §8.2c) is the right call.

### 4.4 The deferred `claimToken` (§8.4, §11.4) — **not escalated into this branch; formally recorded as SR-06**

This is not a new decision. It is **SR-06**, already a tracked `SHOULD` in `docs/threat-model.md` ("*Return an opaque claim/lease token from `/jobs/claim` … so guessing the sequential `jobId` is insufficient*"), open since the original build. The architecture doc's framing — "a candidate standalone hardening item" — understates its status: it is an accepted, already-numbered residual, and this branch is the third endpoint to inherit it.

**Decision: do not escalate.** Reasons: (i) it is a breaking, cross-module contract change across all three Worker-facing endpoints plus `ClaimJobResponse` and the Worker's per-job state, which is disproportionate to a bugfix branch and would delay a fix for a live production stall; (ii) the marginal exposure `/fail` adds over the existing `/result` is real but not a new *class* — `/result` already lets a `(jobId, workerId)` guesser inject an entire fabricated LLM response, which is strictly worse for integrity than failing a job; (iii) implementing it half-way (on `/fail` only) would create an inconsistent contract and a false sense of closure.

**But the residual must be tightened, not just restated**, because this branch makes its *impact* worse (WOT-02: 10.5 min → 60 s to destroy a Review). Three compensating controls, all cheap, all in-branch:

1. **WOR-16 — fix the stated bound.** The window in which a stale duplicate can land on a fresh attempt is closed by `requeue-delay > worker request timeout (10 s)`, **not** by `requeue-delay >= poll-interval (3 s)` as WOC-42 states. Validate `requeue-delay == 0 || requeue-delay >= 15s` at startup, and document that `requeue-delay: 0` explicitly re-accepts this risk.
2. **WOR-03 — make guessing detectable.** Ownership mismatches across all three endpoints get a `/metrics` counter, not just a WARN. A `workerId`-guessing campaign is necessarily noisy; today nothing counts the noise.
3. **WOR-01/02 — bound the damage rate**, so even a successful guess cannot destroy a Review inside one operator reaction time.

Recommendation to the architect, for a follow-up branch: implement SR-06 as a single `review_jobs.claim_token UUID`, returned in `ClaimJobResponse`, required by `heartbeat`/`result`/`fail`, `NULL`-tolerant for one release so the fleet can roll. That closes T-04, T-13, WOT-02 and WOT-11 together and is the right shape — just not here.

### 4.5 New INFO logging (§11.5)

**WOC-01/02/04 are clean as specified** — ids, statuses, counts, `elapsedSec`, tick counts, and the Worker's own configured `backend` name. `workerId` is the Worker's own config, not attacker input, on the Worker side. Demoting the empty-claim log to DEBUG *reduces* exposure. WSR-15 reasoning (the new INFO sits inside the existing `try`, still under the catch-`Throwable` guard) is correct.

**WOC-03 has one trap worth a named requirement.** `"…systemMessages={}…"` with the raw `List<String>` renders every prompt section in full — bypassing PMR-25's masked `toString()`, which lives on the DTO, not the list. Same for `prompt.messages()` (`List<ChatMessage>`) and `prompt.model()`-adjacent objects. WOR-17 makes "counts and byte sizes only, never a collection of content" explicit and testable. `diffChars` as a count is fine and is exactly the WSR-10 pattern.

**One channel the doc does not list:** the Worker's *existing* `log.warn("Job abandoned (jobId={}): {}", jobId, e.getMessage())` already prints exception messages that can quote llama output (WOT-03). It is pre-existing and Worker-local (WSR-10 covers content, and this is arguably "an accidental log line"), but since WOC-22 is about to give those same exceptions a structured reason code, the same code change should switch that line to `reason` + exception *class* rather than message (WOR-05, second clause).

### 4.6 Migration safety and the claim-query predicate (§11.6)

**Migration: safe, no V2-class hazard, but it needs a short deployment note — for a different reason than V2's.**

- Both columns are nullable with no default ⇒ metadata-only on PostgreSQL 11+, no table rewrite, no backfill. `ddl-auto: validate` means the entities must match exactly (boot-blocking if not, which is the desired failure mode).
- `ALTER TABLE review_jobs` still takes `ACCESS EXCLUSIVE` on the queue table. In practice this runs at Gateway startup while the Gateway is the only writer and is down — but the Worker fleet is *not* down (by design: `RUNNING` jobs survive Gateway restarts), and the Gateway holds no long transactions by construction (CSR-19's 3 s `lock_timeout`). Low risk; still, set an explicit `lock_timeout` in the migration so a stuck `ALTER` fails fast instead of blocking every claim behind it.
- **Rollback tolerance is correctly claimed and is materially better than V2's**, and the reason should be stated in `DEPLOYMENT.md` rather than just asserted: an older JAR ignoring `not_before` claims immediately (today's behavior, safe), and ignoring `probe_failed_since` demotes on a single probe (today's behavior, safe). Neither can double-execute a job the way a V2 rollback could.

**The predicate change is the part that needs the note.** `AND (j.not_before IS NULL OR j.not_before <= now())` on `findNextQueuedJobIdForUpdate` is index-compatible (`ix_review_jobs_queue` is partial on `status='QUEUED'` and the predicate is a filter, not an ordering term) and at tens of rows the plan is irrelevant. The deployment-note-worthy content is **operational, not performance**: (a) `requeue-delay` is now a lower bound on recovery latency for *every* requeue including sweep-originated ones; (b) `requeue-delay: 0` is the documented revert; (c) **`not_before` is compared against the database clock but written from the application clock** (WOT-08) — either compute it in SQL (`now() + make_interval(...)`) or state the app/DB clock-sync requirement explicitly, given §2.3 already suspects skew in this very deployment; and (d) there is **no sweep for `QUEUED` jobs**, so a bad `not_before` is an untimed stall — hence WOR-13's cap and WOR-14's alarm.

### 4.7 `WORKER_FAILED` EventType (§8.3, §11.7) — **§8.3 upheld, for a stronger reason than the one given**

The architecture doc's rationale (no `ck_event_type` migration, no second row, no `StatisticsService` decision) is sound but is an argument from cost. There is a **security argument that points the same way and is decisive**:

The only thing a dedicated event would add over the `details` prefix is auditing of reports that are **rejected or no-ops** — precisely the case the doc offers to be overruled on ("*a rejected/duplicate report is arguably an untrusted-input security event*"). But writing a `review_events` row for a rejected report turns `POST /jobs/{id}/fail` into an **authenticated, unbounded `INSERT` primitive on the audit table for any worker-token holder**, with no rate limit (SR-20 unimplemented) and no natural bound — an attacker spams `403`s and grows the audit table without limit. That is a worse problem (DoS + audit-store pollution, degrading the very forensics it was meant to improve) than the repudiation gap it closes.

By contrast the **accepted** path is naturally bounded: after the first accepted report the job is `QUEUED` or `FAILED`, so every subsequent report is a no-op that writes nothing; the maximum is `max-attempts` `RETRY`/`FAILED` rows per job, exactly as today.

**Verdict:** keep the existing `RETRY`/`FAILED` rows with a `details` prefix (WOC-30), with two conditions:

- **WOR-04** — the prefix is a **Gateway-emitted constant from a fixed vocabulary**, placed **first**, with the sanitized `detail` always **last**, and the grammar documented on `RetryManager` so a future refactor cannot make the discriminator forgeable (WOT-12).
- **WOR-03** — rejected and no-op reports are observable via `/metrics` counters + WARN logs (bounded, in-memory, no unbounded DB writes), which gives WOT-09's detection without WOT-09's cure being worse than the disease.

---

## 5. Security requirements — WOR-01..WOR-20

Testable assertions for `backend-developer`; AppSec re-verifies each in the SAST round on this branch (`docs/security/feature-worker-observability-sast-report.md`, prefix `F-WO-`).

### Attempt budget and blast radius (the blocking set)

- **WOR-01 (MUST, WOT-01/WOT-02).** The attempt budget MUST NOT be exhaustible inside one backend-demotion grace window. Enforce at startup, same `@PostConstruct` fail-fast pattern as SR-15/PMR-14: `retry.requeue-delay × (retry.max-attempts − 1) >= backend.failure-grace`, **unless** both `requeue-delay` and `failure-grace` are `0` (the paired, explicit "revert to pre-branch behavior" escape hatch — neither may be zeroed alone). With `max-attempts: 3` and `failure-grace: 180s`, the shipped default for `gateway.retry.requeue-delay` MUST therefore be **`90s`, not `30s`**. *Test:* boot with `requeue-delay: 30s` + `failure-grace: 180s` ⇒ startup fails with a message naming both properties; boot with both `0` ⇒ starts. *Test:* with a backend stubbed permanently unreachable, a Review's three attempts span ≥ `failure-grace`, and the backend is demoted (or declined by WOR-10) before the third attempt is claimed.
- **WOR-02 (SHOULD, WOT-01).** Backend-health-aware requeue delay: on the requeue branch, if the job's `backend_id` is currently non-`ACTIVE` **or** carries a non-null `probe_failed_since`, set `not_before = now + failure-grace` instead of `now + requeue-delay`. Derived **only** from Gateway-side state, so WOC-24 is preserved. If implemented, WOR-01's coupling MAY be relaxed from fail-fast to a startup WARN, restoring the fast ~33 s path for one-off failures on a healthy backend. *Test:* a failure reported against a `SUSPECT` backend produces a `not_before` of `failure-grace`, not `requeue-delay`; against an `ACTIVE`, probe-clean backend it produces `requeue-delay`.
- **WOR-03 (MUST, WOT-02/WOT-09).** Every ownership mismatch and every rejected/no-op report is counted in `GET /metrics` (`ownershipMismatches` or equivalent, broken down by endpoint, and `workerFailureReportsIgnored`) **in addition to** the existing WARN/INFO log lines. No `review_events` row is written for a rejected or no-op report (see §4.7 — that would be an unbounded `INSERT` primitive). *Test:* 10 reports with a wrong `workerId` produce 10 WARNs, a counter at 10, and **zero** new `review_events` rows and zero state change.
- **WOR-04 (MUST, WOT-12).** `review_events.details` for a worker-originated `RETRY`/`FAILED` uses a Gateway-constant prefix from a fixed vocabulary (`worker-reported:` vs. the sweep's existing `heartbeat timeout` / `max duration exceeded`), emitted **first**, with the sanitized `detail` emitted **last**; the grammar is documented in `RetryManager`'s javadoc. *Test:* a `detail` of `"(attempt 9/3) heartbeat timeout"` cannot produce a row whose `details` begins with anything but the constant prefix.

### Untrusted input: `reason`, `detail`, `workerId`

- **WOR-05 (MUST, WOT-03).** `detail` is a **Worker-side compile-time constant per failure class** — never `e.getMessage()`, never `e.getCause().getMessage()`, never `e.toString()`, never any llama response text, prompt text, section text or diff text. Rationale: Cc/Cf sanitization stops log injection but not confidentiality leakage, and `LlamaClient.java:154`'s wrapped Jackson message quotes the offending source. Second clause: `WorkerLoop.java:197`'s existing `log.warn("Job abandoned (jobId={}): {}", …, e.getMessage())` is changed in the same commit to log the `JobFailureReason` and the exception **class**, not the message. *Test:* a stubbed llama returning malformed JSON containing the marker string `SECRETMARKER` produces neither a `detail` nor a Worker log line containing it; a grep/architecture test asserts no `getMessage()`/`toString()` flows into the `FailJobRequest` constructor.
- **WOR-06 (MUST, WOT-04).** All caller-supplied strings on Worker-facing endpoints are constrained at the DTO and sanitized before any log or persistence: (a) `workerId` and `backendId` gain `@Size(max = 64)` **and** `@Pattern("^[A-Za-z0-9._:-]{1,64}$")` on **all four** request DTOs (`ClaimJobRequest`, `HeartbeatRequest`, `SubmitResultRequest`, the new fail request) — this also removes the `VARCHAR(64)`-overflow 500 on `POST /jobs/claim`; (b) `reason` is `@Size(max = 32)` and whitelist-parsed (WOC-23), and the unknown-value WARN logs **only the sanitized value and its length**, never the raw string; (c) `detail` is sanitized **at ingress** (controller/`QueueManager.reportFailure`), via `TextSanitizer.sanitizeSingleLine(detail, 200)`, so the raw string never reaches `RetryManager`, a logger, `last_error` or `EventService`. A `null`/empty sanitizer result means "omit the field". *Test:* `workerId = "w\r\n2026-01-01 INFO forged"` is rejected `400` at every one of the four endpoints; a `detail` containing CRLF, `U+202E`, ANSI `ESC[` and 10 000 chars yields a single-line, ≤200-char stored value; the unknown-`reason` WARN cannot forge a log line.
- **WOR-07 (MUST, WOT-05).** `EventService.scrub` additionally strips Cc/Cf/Zl/Zp (delegating to `TextSanitizer`, not a second implementation) **before** its existing token-shape redaction and 500-char cap, so the SR-12 choke point is safe for the untrusted text WOC-30 now routes through it. Ordering (Cc/Cf first, per CSR-09) is preserved. *Test:* `eventService.record(..., "a\r\nFORGED")` stores a single-line value; the existing token-redaction tests still pass.

### Edge / body caps

- **WOR-08 (MUST, WOT-06a).** `RequestBodySizeLimitFilter` gains the `/jobs/{id}/fail` pattern bounded by `gateway.job.max-fail-body-bytes` (default `4096`). *Test:* a 5 KB body to `/jobs/1/fail` gets `413` before Jackson runs.
- **WOR-09 (MUST, WOT-06b).** The filter matches on the **decoded, normalized** request path (e.g. `UrlPathHelper`/`ServletRequestPathUtils`), not on raw `request.getRequestURI()`, so a percent-encoded path segment cannot bypass the cap. This closes the same bypass on the existing `/reviews` and `/jobs/{id}/result` caps. *Test:* `POST /jobs/1/%66ail`, `POST /jobs/1/%72esult` and `POST /re%76iews` with oversized bodies all get `413` (and, on the happy path, still route to the same handlers).

### Backend health / dispatch

- **WOR-10 (MUST, WOT-07).** `BackendDispatcher.resolveClaimableBackend` declines any backend whose `probe_failed_since` is non-null and older than `gateway.backend.failure-grace`, **regardless of persisted status** — one field comparison on a row already loaded. This is what makes WOC-13's deferral dispatch-neutral over time and not merely at the instant of evaluation. *Test:* a deferred, at-capacity, past-grace backend whose job then completes is **not** claimable in the interval before the next probe pass; a backend with a fresh (within-grace) streak still is.
- **WOR-11 (MUST, WOT-07).** WOC-18's stall WARN fires when jobs are `QUEUED` and there is no backend that is both `ACTIVE` **and** free of a past-grace failure streak — not merely "no `ACTIVE` backend". Otherwise a deferred-dead backend suppresses the one alarm this branch adds. *Test:* one `ACTIVE` backend with a past-grace `probe_failed_since` plus a `QUEUED` job ⇒ the WARN fires.
- **WOR-12 (MUST, WOT-07).** WOC-19 is promoted from SHOULD to MUST: `BackendSnapshot`/`GET /backends` (ADMIN-only) exposes `probeFailedSince`, and the per-pass INFO line distinguishes "within grace" from "deferred because at capacity". Operator visibility is the compensating control for a deferral an untrusted caller can prolong. *Test:* the admin view reports a non-null `probeFailedSince` for a failing-but-deferred backend.
- **WOR-13 (SHOULD, WOT-07).** The at-capacity deferral is capped by `gateway.backend.defer-demotion-max` (default = `gateway.job.max-duration`); past the cap, demotion proceeds regardless of capacity/heartbeat. *Test:* a backend held at capacity by continuously-heartbeated jobs is eventually demoted.

### `not_before` / queue liveness

- **WOR-14 (MUST, WOT-08).** `not_before` is computed against the **database** clock (`now() + interval`, or an equivalent single-clock formulation), never a mix of JVM `Instant.now()` and SQL `now()`. `gateway.retry.requeue-delay` is validated at startup as `0 <= requeue-delay <= 10m` (an upper cap, so a typo cannot strand a Review). *Test:* with the DB clock skewed +10 min relative to the JVM, the effective claim delay still equals `requeue-delay`; `requeue-delay: 2h` refuses startup.
- **WOR-15 (MUST, WOT-08/WOT-15).** (a) A `QUEUED` job whose `not_before` has been in the past for longer than `gateway.job.max-duration` is reported by a WARN on the existing scheduler tick (reusing the WOC-18 pass — no new scheduled job), so a stalled queue can never again be silent. (b) The `/fail` no-op path returns `200` **without** opening the `REQUIRES_NEW` transaction when the unlocked pre-check already shows the job is not `RUNNING`. *Test:* a `QUEUED` job stuck past the threshold produces the WARN; a report against a `COMPLETED` job opens no write transaction (verified by a transaction/connection assertion or a spy on `RetryManager`).
- **WOR-16 (MUST, WOT-11).** Startup validation is `requeue-delay == 0 || requeue-delay >= 15s` — bounded by the Worker's `network.gateway-timeout-sec` (10 s), **not** by `poll-interval-ms` (3 s) as WOC-42 states. `requeue-delay: 0` is documented in `DEPLOYMENT.md` as explicitly re-accepting the stale-duplicate-report risk (SR-06 residual). *Test:* `requeue-delay: 5s` refuses startup; `0` starts with a WARN naming the accepted risk.

### Logging and exposure

- **WOR-17 (MUST, WOT-10).** No new log statement passes a collection or content-bearing object to an slf4j placeholder. WOC-03 logs `systemMessages=<count>`/`systemMessageChars=<sum>`, never `List<String>`; the same applies to `prompt.messages()`. Extend the existing `SensitiveDtoToStringMaskingTest` / WSR-10 regression test to cover the four new INFO sites. *Test:* with a Prompt-Manager `REPO` review whose section content contains the marker `SECTIONMARKER`, no Worker log line at any level ≤ INFO contains it.
- **WOR-18 (MUST, WOT-14).** A role-matrix test asserts `POST /jobs/{id}/fail` returns `401` unauthenticated, `403` with a CI token, `403` with an ADMIN token, and is reachable only with the WORKER token — the same `@WebMvcTest` matrix SR-16 already mandates for the other paths. *Test:* the matrix test exists and covers the new path.
- **WOR-19 (MUST, WOT-02/§4.1).** The HTTP status is derived from the **locked** outcome, never from the unlocked pre-check; the pre-check exists only as a cheap short-circuit and its result is discarded when the locked path reaches a different conclusion. `RequeueOutcome.OWNERSHIP_MISMATCH` from the locked path logs WARN and increments WOR-03's counter. A `PessimisticLockingFailureException`/`QueryTimeoutException` on the job lock maps to `200 {"accepted": true}` (WOC-32), never `500`. *Test:* the WOC-27 race (pre-check owner ≠ locked owner) yields `403` **and** no state change; a forced lock timeout yields `200` and no state change.
- **WOR-20 (MUST, WOT-17).** `review_jobs.last_error` (now carrying Worker-supplied text) is never included in any CI- or Worker-facing response body — `ReviewStatusResponse` stays as documented. If it is ever surfaced to ADMIN, it is the already-sanitized value. *Test:* `GET /reviews/{id}` contains no error text field; an architecture/grep test asserts `getLastError()` has no call site in a client-facing DTO mapper.

### Accepted residuals

- **WOR-INH-1 (ACCEPTED-RISK, WOT-02/WOT-11) — SR-06 remains open.** Ownership on all three Worker-facing job endpoints stays `workerId`-equality under a fleet-shared token, with sequential `jobId`s. Not escalated into this branch (§4.4); compensated by WOR-01/02 (damage rate), WOR-03 (detection) and WOR-16 (window). Recommended as the *first* item of a follow-up hardening branch, implemented uniformly across `claim`/`heartbeat`/`result`/`fail`.
- **WOR-INH-2 (ACCEPTED-RISK, WOT-15) — SR-20 remains open.** No per-token rate limiting exists on any endpoint. `/fail` adds no new DoS class (it is cheaper to abuse `/jobs/{id}/heartbeat`), so no endpoint-specific limiter is required here, but the branch adds one more endpoint's worth of justification to SR-20.
- **WOR-INH-3 (ACCEPTED-RISK, §4.1).** `403` vs `404` on `/jobs/{id}/*` remains a job-id existence oracle for a worker-token holder. Preserved deliberately for parity with `heartbeat`/`submitResult`; the property that *is* enforced is that no response body ever echoes `reviewId` or a Review status. Closed properly only by SR-06.

---

## 6. Architecture-level corrections required BEFORE dev starts

These are changes to the approved-in-draft `docs/worker-observability-and-claim-latency-architecture.md`. Items 1–3 are blocking; 4–7 are corrections of stated values/claims that are cheap to apply now and expensive to retrofit.

1. **`gateway.retry.requeue-delay` default changes `30s` → `90s`, with a startup validation coupling it to `failure-grace` (WOR-01).** The doc's headline "~33 s instead of ~390 s" becomes "~93 s instead of ~390 s" — still a 4× improvement, in the safe direction. Without this, Parts 2 and 3 combine to turn every ≥60 s backend outage into permanently `FAILED` Reviews with sibling cascade, where today they are survivable. Optionally recover the fast path via WOR-02.
2. **WOC-13's "dispatch-neutral by construction" is corrected to hold over time, not just at the instant of evaluation (WOR-10).** `BackendDispatcher` must consult `probe_failed_since` past `failure-grace`. WOC-18's stall condition and WOC-19's admin field are upgraded accordingly (WOR-11/12).
3. **`detail` is a Worker-side constant per failure class, never an exception message (WOR-05).** WOC-22/25 as written implies the opposite reading and would ship a confidentiality leak of LLM output into the Gateway's audit trail.
4. **WOC-42's bound is wrong: `requeue-delay` must be compared against the Worker's request timeout (10 s), not its poll interval (3 s) (WOR-16).** The escape hatch `requeue-delay: 0` must be documented as re-accepting the SR-06 stale-duplicate residual, and may only be used together with `failure-grace: 0`.
5. **`not_before` is computed on one clock — the database's (WOR-14)** — given §2.3 already suspects container clock skew in this deployment, and `requeue-delay` gets an upper cap so a typo cannot strand a Review. Add the `QUEUED`-stall WARN (WOR-15a): nothing sweeps `QUEUED`, and this branch is the first to make a `QUEUED` job non-claimable.
6. **§11.7 answered: `WORKER_FAILED` is NOT added (§4.7).** §8.3's decision stands, upheld on a security argument rather than a cost one — auditing rejected reports in `review_events` would create an authenticated unbounded-`INSERT` primitive. Instead: fixed-vocabulary Gateway-constant prefix placed first with `detail` last (WOR-04), plus `/metrics` counters for rejected/no-op reports (WOR-03).
7. **While touching `RequestBodySizeLimitFilter`, fix its raw-URI matching (WOR-09)** — the new cap would otherwise be bypassable by percent-encoding exactly like the existing two. And close the `workerId`/`backendId` validation gap on all four Worker-facing DTOs (WOR-06), which also removes a pre-existing `500` on `POST /jobs/claim`.

---

## 7. Release gate

**Blocking MUSTs:** WOR-01, WOR-03, WOR-04, WOR-05, WOR-06, WOR-07, WOR-08, WOR-09, WOR-10, WOR-11, WOR-12, WOR-14, WOR-15, WOR-16, WOR-17, WOR-18, WOR-19, WOR-20.

**Tracked SHOULDs:** WOR-02, WOR-13.

**Accepted residuals:** WOR-INH-1 (SR-06 / claim token), WOR-INH-2 (SR-20 / rate limiting), WOR-INH-3 (403-vs-404 existence oracle).

**Non-regression set to re-verify in the SAST round:** SR-04 (ownership on every `/jobs/{id}/*` call — now three endpoints), SR-11 (edge body cap, *strengthened* by WOR-09), SR-12/T-09 (no secrets/diff/LLM content in logs or `review_events` — the primary risk of this branch), SR-16 (one role per path, incl. the new one), SR-17 (no internal detail in error bodies — the `MethodArgumentNotValidException` handler returns field name + constraint message, not the rejected value; keep it that way), SR-21 (`/result` cap untouched), CSR-17/CSR-18/CSR-19 (lock ordering and the 3 s `lock_timeout` — `reportFailure` must add **no** new lock and no new ordering, per WOC-26/32), CSR-09/F-DC-02 (`TextSanitizer` gains a delegating alias only — no weakening of `sanitizePath`), PMR-25/F-DC-07 (masked `toString()` on content-carrying DTOs — WOR-17 extends it to log call sites), WSR-10/WSR-15/WSR-18 (Worker logging discipline, heartbeat crash guard, CRLF stripping).

**CI gate:** the existing SR-23 gate covers this branch; no new tooling. Two Semgrep rules worth adding while the feature is in flight: (a) flag any slf4j call whose argument is a `Collection`/`List` in the `worker` module (WOR-17), and (b) flag `getMessage()`/`toString()` reaching a `*Request` DTO constructor in `WorkerLoop`/`GatewayClient` (WOR-05).

---

Relevant files for the developer picking this up: `docs/worker-observability-and-claim-latency-architecture.md` (the design), `docs/threat-model.md` (SR-04/SR-06/SR-11/SR-12/SR-16/SR-20, T-04), `docs/worker-threat-model.md` (WSR-10/WSR-15/WSR-18), `src/main/java/com/review/gateway/service/RetryManager.java` (WOC-27/28/29, WOR-01/02/04), `src/main/java/com/review/gateway/service/QueueManager.java:326-396` (the `heartbeat`/`submitResult` ownership + opacity pattern to mirror), `src/main/java/com/review/gateway/service/BackendHealthChecker.java` + `BackendDispatcher.java` (WOR-10/11/12), `src/main/java/com/review/gateway/service/EventService.java` (WOR-07), `src/main/java/com/review/gateway/service/TextSanitizer.java` (WOR-06), `src/main/java/com/review/gateway/config/RequestBodySizeLimitFilter.java` + `WebConfig.java` (WOR-08/09), `src/main/java/com/review/gateway/dto/ClaimJobRequest.java` / `HeartbeatRequest.java` / `SubmitResultRequest.java` (WOR-06), `src/main/java/com/review/gateway/service/ChunkCoordinator.java:220-240` (the sibling cascade that makes WOT-01/WOT-02 expensive), `worker/src/main/java/com/review/worker/core/WorkerLoop.java:181-202` (WOC-34, WOR-05), and `worker/src/main/java/com/review/worker/llama/LlamaClient.java:146-190` (the exception messages that must not become `detail`).
