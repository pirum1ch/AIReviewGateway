# Architecture: Worker Observability & Claim Latency

Status: **DRAFT** — hand-off to `appsec-engineer` for the pre-implementation threat-model round (extends `docs/worker-threat-model.md` and `docs/threat-model.md`). Branch: `fix/worker-observability-and-claim-latency` (cut from `feature/prompt-manager` @ `85129a3`).

Requirement prefix used in this document: **`WOC-nn`**. Appsec will layer its own `WOT-`/`WOR-` ids on top, same convention as `PMT-`/`PMR-` for the Prompt Manager.

---

## 0. Scope, non-goals, and why this needs the full pipeline

Three production symptoms observed on a real 3-chunk review that took ~26 minutes wall-clock while actual LLM inference was a few minutes total. All three are addressed here:

1. **Worker log gives no signal that a job is in progress** (observability only, Worker module).
2. **Backends flap `ACTIVE → SUSPECT` while alive, blocking new claims for minutes** (Gateway module).
3. **Worker-side job failures are never reported to the Gateway**, so a dead job waits out the passive heartbeat-timeout sweep (Gateway + Worker; **extends the Worker↔Gateway API contract**).

Item 3 adds a Worker-facing endpoint, which is why this bugfix goes through the mandated `architect → appsec → backend-developer → qa-engineer → appsec(SAST) → backend-developer(fix) → appsec(final)` pipeline rather than being a drive-by patch.

**Explicit non-goals** (do not implement as part of this branch):

- Changing `llama.max-tokens` / model-tuning to avoid empty completions. That is a config/tuning question for the operator (`worker/src/main/resources/application.yml`, `LLAMA_MAX_TOKENS`, default 4096; a reasoning model can burn the whole budget inside `<think>…</think>` and emit empty `content`). Called out in §9 as an operational recommendation, not a code change.
- Any new infrastructure (Redis/Kafka/Prometheus/…): forbidden by CLAUDE.md, and nothing here needs it.
- Reworking the retry policy itself. `RetryManager` stays the *only* place a requeue-vs-fail decision is made, and that decision stays purely `attempts`-based.
- Setting `review_jobs.finished_at` on the `RUNNING → FAILED` path in `RetryManager` (today only `ResultProcessor` sets it, so `averageRunDurationMillis` ignores swept failures). Real, but a separate metrics-consistency issue — noted here so it is not mistaken for a regression introduced by this branch.
- Per-claim ownership nonce (`claimToken`). See §8.4 — a pre-existing, cross-endpoint weakness, flagged for appsec, deliberately not fixed here.

**No new dependencies.** Everything below is Spring Boot + PostgreSQL + slf4j already on the classpath. No `pom.xml` change in either module.

---

## 1. Summary

- **Part 1 (Worker logs):** reuse the existing heartbeat cadence (`heartbeat.interval-sec`, default 60s) to emit one INFO "job in progress" line per tick; demote the empty-claim poll log from INFO to DEBUG; add an INFO at inference start and a rate-limited idle summary. No new thread, no new scheduler, no new config required for the core of it.
- **Part 2 (Backend health):** make demotion *fail-slow / recover-fast*. `ACTIVE → SUSPECT` only after a backend has failed probes **continuously** for `gateway.backend.failure-grace` (default 180s), tracked in a new restart-safe column `backends.probe_failed_since`; a backend that is at capacity with a fresh job heartbeat is never demoted (dispatch-neutral deferral); probe HTTP I/O moves **out of** the `@Transactional` block so the read timeout can safely go 5s → 10s; `last_seen` becomes "last *successful* contact"; a WARN fires when jobs are QUEUED and no backend is ACTIVE.
- **Part 3 (Failure reporting):** new Worker-facing endpoint **`POST /jobs/{id}/fail`**, sent synchronously by the Worker before it claims the next job. It carries no result and no retry decision — it is a *fact report* that lets the Gateway run the existing `RetryManager.requeueOrFail` immediately instead of waiting out `heartbeat.timeout` (180s) + `scheduler.heartbeat-check-interval` (≤30s). Delivery is **best-effort and never required for correctness**: the passive sweep remains the backstop. A new `review_jobs.not_before` column + `gateway.retry.requeue-delay` (default 30s) prevents the fast-fail storm that removing the accidental 3.5-minute backoff would otherwise create.

One Flyway migration (`V4`), two additive nullable columns, one new endpoint, no new `EventType` value, no change to the `review_events` CHECK constraint.

---

## 2. Root causes, confirmed against the current code

### 2.1 Finding 1 — no in-progress signal in the Worker log

- `worker/src/main/java/com/review/worker/core/HeartbeatScheduler.java:74-85` — the `ACCEPTED` + `shouldContinue == true` path (the normal case, once a minute, for up to `request-timeout-sec` = 1800s) logs **nothing**.
- `worker/src/main/java/com/review/worker/gateway/GatewayClient.java:72` — the only heartbeat log is DEBUG, off in prod.
- `worker/src/main/java/com/review/worker/gateway/GatewayClient.java:50` — `log.info("Claimed job (jobId={})", response == null ? "none" : …)` fires on **every** poll, including the 204/empty case, i.e. every `network.poll-interval-ms` (default 3000ms) for an idle Worker.

Net effect confirmed: the busy Worker is silent while the idle sibling emits `jobId=none` twice a second-ish. Exactly inverted from what an operator needs.

### 2.2 Finding 2 — backend flapping

- `src/main/java/com/review/gateway/service/BackendHealthChecker.java:39-61` — `probeAll()` is `@Transactional`, iterates `ACTIVE` + `SUSPECT` backends, and flips `ACTIVE → SUSPECT` on a **single** failed probe (`:52-56`), `SUSPECT → ACTIVE` on the **first** success (`:48-51`). `setLastSeen(now)` is applied **unconditionally**, success or failure (`:57`) — so `last_seen` currently does not mean "last successful contact".
- `src/main/java/com/review/gateway/service/BackendProberImpl.java:36-49` — `GET {url}/health` via `backendProbeRestClient`; **any** `RestClientException` (connect refused, read timeout, *and any 4xx/5xx status*, since `.retrieve().toBodilessEntity()` throws on non-2xx) counts as unhealthy.
- `src/main/java/com/review/gateway/service/BackendDispatcher.java:58-61` — a non-`ACTIVE` backend yields `Optional.empty()` → `QueueManager.claim` → `204`. Confirmed: this blocks **new claims only**; an already-`RUNNING` job on that backend is untouched (nothing in `QueueManager.heartbeat`/`submitResult`/`ResultProcessor` consults `backends.status`).
- `src/main/resources/application.yml:79` — `gateway.backend.read-timeout: 5s`; `:66` — `backend-health-interval: 60s`.

Two independent mechanisms can produce the observed 13-minute lockout, and the design must survive both:

- **Busy-slot 503 / delayed `/health`.** The user's hypothesis: with `--parallel 1`, a `llama-server` mid-generation may not answer `/health` within 5s (or, on older builds, answers `503 no slot available`). Either way → `RestClientException` → single-probe demotion of a backend that is alive and working.
- **Model-load 503.** `llama-server` answers `/health` with `503` while loading the model — which on a Mac mini with a large model can last minutes. During that window `SUSPECT` is *correct* (the backend genuinely cannot serve), and the fix must not paper over it: it must only delay demotion, never suppress it indefinitely.

Because the first pattern happens *only while a job is running on that backend*, and the second happens *only while no job is running*, the two mitigation rules below (grace window + at-capacity deferral) are complementary and neither masks the other's case.

### 2.3 Finding 3 — worker-side failures are silent

- `worker/src/main/java/com/review/worker/core/WorkerLoop.java:196-198` — `catch (AbandonJobException | LlamaException e) { log.warn(...); metrics.incrementJobsFailed(); }` and then straight back to `claim()`. The Gateway is never told.
- Throw sites: `worker/src/main/java/com/review/worker/llama/LlamaClient.java:180,185` (`no choices` / `no message content`), `:146` (non-2xx), `:154` (parse failure), `:169` (oversize), `WorkerLoop.java:258,266,273` (timeout / execution failure / interrupt), plus `PromptTemplateService.resolve` (`AbandonJobException`, thrown **before** `heartbeatScheduler.start()`).
- Recovery path today: `TimeoutManager.sweepStaleHeartbeats()` (`src/main/java/com/review/gateway/service/TimeoutManager.java:47-58`) → `RetryManager.requeueOrFail` (`:67-74`). Theoretical latency from abandonment: up to `heartbeat.timeout` (180s, measured from the *last* heartbeat, not from the abandonment) + up to `scheduler.heartbeat-check-interval` (30s) ≈ **210s**, then the requeued job still waits for a poll (3s) and an `ACTIVE` backend (Finding 2 compounds here).

**Open verification item for QA:** the user's `review_events` dump shows `RUNNING → RETRY` ~6.5 minutes after the last `HEARTBEAT` row, which exceeds the 210s upper bound above. After this branch, worker-reported failures make that path moot, but a genuine >210s sweep latency would indicate something else (container clock skew between `gateway-1` and `worker*-1` when comparing log vs. DB timestamps, an overlapping/delayed `@Scheduled` tick, or the `RETRY` row belonging to a later attempt). QA should measure sweep latency directly (§10, T-3.9) rather than assume it is explained.

The note about the Jinja `"No messages provided"` line in the `llama-server` debug log is accepted as-is: it is llama-server's own startup chat-template introspection, and `PromptTemplateService.buildMessages()` always emits at least one `user` message. Not a cause; no design response needed.

---

## 3. Part 1 — Worker in-progress visibility

All changes are inside the `worker/` module. Logging discipline from `WSR-10` is unchanged and binding: **ids, statuses, counts and sizes only — never diff content, prompt content, raw LLM response, or the bearer token.**

| Id | Requirement |
|---|---|
| **WOC-01** | `GatewayClient.claim` (`GatewayClient.java:50`) MUST log at INFO **only** when a job was actually claimed (`response != null`), including `jobId` and `reviewId`. The empty (204) case MUST drop to DEBUG. |
| **WOC-02** | `HeartbeatScheduler.tick` MUST log at INFO on every successful tick whose outcome is `ACCEPTED` + `shouldContinue == true`: `"Job in progress (jobId={}, workerId={}, elapsedSec={}, heartbeats={})"`. `elapsedSec` and the tick counter are process-local logging state created inside `start(...)` (alongside the existing `AtomicInteger consecutiveFailures`), so the public signature of `start`/`stop` does not change and no caller is touched. |
| **WOC-03** | `WorkerLoop.runInference` MUST log at INFO immediately before `llamaClient.startChatCompletion(...)`: `"Starting inference (jobId={}, reviewId={}, diffChars={}, systemMessages={}, model={}, maxTokens={})"` — sizes/counts only (WSR-10). This closes the gap between "Job claimed" and the first heartbeat tick (up to 60s of silence today). |
| **WOC-04** | `WorkerLoop` MUST emit a rate-limited idle summary so a genuinely idle Worker still proves liveness without spamming: at most one INFO per `worker.log.idle-summary-interval-sec` (default `300`; `0` disables), `"Idle: no job available in the last {} poll(s) (backend={})"`. The counter resets on a successful claim. Implemented with a plain field in `WorkerLoop` (single-threaded loop, no synchronization needed) — no new scheduler. |
| **WOC-05** | The existing `HeartbeatScheduler` cadence MUST NOT change (`heartbeat.interval-sec`, default 60s). No new thread, executor, or `@Scheduled` is introduced anywhere in the Worker. Log volume while busy: exactly 1 line/minute/worker. |
| **WOC-06** | `GatewayClient.heartbeat`'s existing DEBUG line (`GatewayClient.java:72`) stays DEBUG — the INFO now lives in exactly one place (`HeartbeatScheduler`) so a raised log level cannot produce two lines per tick. |

WSR-15 is unaffected: the new INFO call sits **inside** the existing `try` block, so a logging failure is still caught by the catch-`Throwable` guard and can never de-schedule the heartbeat.

---

## 4. Part 2 — Backend demotion that tolerates a busy backend

**Chosen approach: (a) + (c) + a bounded (b), plus the transaction-scope fix that makes (b) safe.** Rationale for the combination is in §8.2.

### 4.1 Behavior

```
                 probe result for one backend
                            │
          ┌─────────────────┴──────────────────┐
       success                              failure
          │                                    │
  probe_failed_since := NULL      at capacity AND a RUNNING job
  last_seen := now()              heartbeat fresher than
  if SUSPECT -> ACTIVE            gateway.heartbeat.timeout?
  (recover-fast, unchanged)          │              │
                                    yes             no
                                     │              │
                          keep status, DO NOT   probe_failed_since ??= now()
                          clear probe_failed_   if ACTIVE and
                          since (deferral,       now - probe_failed_since
                          dispatch-neutral)      >= failure-grace -> SUSPECT
```

| Id | Requirement |
|---|---|
| **WOC-10** | New column `backends.probe_failed_since TIMESTAMPTZ NULL` (V4, §6). Set to `now()` on the *first* failed probe of a streak; cleared on any successful probe. PostgreSQL stays the single source of truth — the failure streak survives a Gateway restart, so no in-memory counter (which CLAUDE.md forbids for reconstructible state). |
| **WOC-11** | `ACTIVE → SUSPECT` MUST require a **continuous** failure streak of at least `gateway.backend.failure-grace` (default `180s`). A failure that does not yet meet the grace window logs at INFO (`"Backend '{}' failed health probe ({} elapsed of {} grace); still ACTIVE"`), not WARN. Time-based rather than count-based so the behavior does not silently change if `backend-health-interval` is retuned and so scheduler jitter cannot demote early. |
| **WOC-12** | `SUSPECT → ACTIVE` stays **single-success** (recover-fast). Any successful probe also clears `probe_failed_since`. |
| **WOC-13** | A failed probe MUST NOT demote a backend while `countRunningJobsForBackend(id) >= capacity` **and** at least one of those RUNNING jobs has `heartbeat_at > now() - gateway.heartbeat.timeout`. In that state the backend is already unclaimable via `BackendDispatcher` (at capacity), so the deferral is **dispatch-neutral by construction** and cannot mask an outage in any way that affects scheduling. `probe_failed_since` MUST be preserved (neither cleared nor restarted) across a deferral, so the moment the job ends — or its heartbeat goes stale — the very next pass demotes immediately if the grace window has already elapsed. Toggle: `gateway.backend.defer-demotion-while-busy` (default `true`). |
| **WOC-14** | `BackendHealthChecker.probeAll()` MUST stop performing HTTP I/O inside a transaction. Three phases: **A** short read-only transaction loading candidate `(id, name, url, status, capacity, probe_failed_since)`; **B** probing over HTTP with **no** transaction and **no** Hikari connection held; **C** a short transaction per pass that re-reads each backend by id and applies the status/`probe_failed_since`/`last_seen` write, guarded on the status it re-reads. Phases MUST be driven by `TransactionTemplate`, **not** by self-invoking a `@Transactional` method on `this` (Spring proxy bypass — the same class of trap already documented in `BackendDispatcher`'s javadoc). |
| **WOC-15** | `last_seen` MUST be updated only on a **successful** probe. Today it is written on every pass regardless (`BackendHealthChecker.java:57`), which makes `GET /backends` unable to answer "when did this backend last actually answer". |
| **WOC-16** | `gateway.backend.read-timeout` raised `5s → 10s`; `connect-timeout` stays `3s`. Safe only because of WOC-14 (no DB connection is held for the duration any more). Worst case per pass is now `N × 13s`, which is why WOC-17 exists. |
| **WOC-17** | `ScheduledJobs.probeBackends` MUST be guarded by a non-blocking re-entrancy flag (`AtomicBoolean`, `compareAndSet`); a tick that finds a pass already running logs WARN and returns. `SchedulingConfig` uses `SimpleAsyncTaskScheduler` with virtual threads, which does **not** serialize overlapping `fixedRate` runs — with a raised timeout and ≥6 backends a pass can exceed the 60s interval and two passes could otherwise race on the same rows. (Single Gateway instance, so a process-local flag is sufficient — no ShedLock, consistent with architecture §12. This is process-local re-entrancy control, not business state, so the "no in-memory state" rule is not violated.) |
| **WOC-18** | After phase C, if `count(review_jobs WHERE status='QUEUED') > 0` **and** no backend is `ACTIVE`, log **WARN**: `"Queue stalled: {} job(s) QUEUED but 0 ACTIVE backend(s) (suspect={}, maintenance={}, offline={})"`. Fires at most once per pass (60s). This is the single line whose absence made the user's 13-minute stall invisible on the Gateway side. Needs one new repository count (`ReviewJobRepository.countByStatus`/`countQueuedJobs()`). |
| **WOC-19** | (SHOULD) `BackendSnapshot`/`GET /backends` (ADMIN-only) gains `probeFailedSince` so an operator can see "failing for 2 of 3 minutes' grace" without reading the DB. Purely additive to an admin-scoped response. |
| **WOC-20** | Probe failure classification stays **transport-level only** (any `RestClientException` = failure). The design deliberately does **not** parse `/health` response bodies to distinguish `loading model` from `no slot available` — that is llama-server-version-specific and fragile. The grace window (WOC-11) and the at-capacity deferral (WOC-13) cover both cases without coupling the Gateway to a backend's body format. |
| **WOC-21** | `MAINTENANCE` and `OFFLINE` backends remain untouched by the checker (operator-owned), exactly as today. |

### 4.2 Effect on the observed incident

- *Mid-review flap at 21:09:47/21:10:47* (both backends demoted right after a job abandonment): with WOC-11 a single failed probe no longer demotes; three consecutive failures over ≥180s do. With WOC-13, a backend still generating stays `ACTIVE` regardless of probe latency.
- *13-minute stall at 20:45–20:58* (no ACTIVE backend at all, e.g. model loading): behavior is intentionally **unchanged in outcome** — a backend that cannot serve is still eventually `SUSPECT` — but WOC-18 now makes the reason visible in one WARN line per minute, and recovery is still single-success/fast.

---

## 5. Part 3 — Explicit worker-side failure reporting

### 5.1 Endpoint choice: new `POST /jobs/{id}/fail`, not a failure shape on `/jobs/{id}/result`

Evaluated against the existing code, reusing `/result` loses on five independent counts:

1. `SubmitResultRequest.rawResponse` is `@NotBlank` (`src/main/java/com/review/gateway/dto/SubmitResultRequest.java:14`) and `ResultProcessor.processJobPhase` **unconditionally** persists a `review_results` row before parsing (`ResultProcessor.java:131-135`). A failure has no raw response; making the field optional would either weaken req. 1.9's "raw response is mandatory, stored before parsing" invariant or force a synthetic row.
2. `docs/worker-architecture.md` D6 / `AbandonJobException`'s javadoc state explicitly that **no synthetic error result is ever submitted**. A failure-shaped `/result` reintroduces exactly that.
3. Terminal semantics differ. `/result` drives `RUNNING → COMPLETED|FAILED` through `ResultProcessor` + `ChunkCoordinator.completeChunkAndRecompute`. A failure report must drive `RetryManager.requeueOrFail`, whose outcome may be `RUNNING → QUEUED` (retry). Overloading one endpoint with two different terminal semantics would put a branch inside the most lock-order-sensitive path in the codebase (CSR-17/CSR-18).
4. Body-size policy differs by three orders of magnitude: `/result` is capped at `gateway.publish.max-request-body-bytes` = 500000; a failure report should be capped at a few KB.
5. Testability/blast radius: a new 40-line endpoint touching `RetryManager` cannot regress result processing; a new branch inside `ResultProcessor` can.

A separate endpoint mirrors the existing `heartbeat`/`result` pattern exactly (self-declared `workerId` in the body, ownership checked before any mutation, opaque 403/404) and adds no new concepts.

### 5.2 Contract

```
POST /jobs/{id}/fail
Authorization: Bearer <WORKER_TOKEN>          # already covered by SecurityConfig's "/jobs/**" -> hasRole("WORKER")
Content-Type: application/json

{
  "workerId": "worker-1",                     # @NotBlank  -- SR-04 ownership
  "reason":   "LLM_EMPTY_RESPONSE",           # @NotBlank @Size(max=32), whitelist-parsed, audit-only
  "detail":   "llama-server response choice had no message content"   # optional, @Size(max=500)
}
```

| Status | Body | When |
|---|---|---|
| `200` | `{"accepted": true}` | Report processed (job was RUNNING and owned) **or** idempotent no-op (job no longer RUNNING). Deliberately identical in both cases — the Worker has no use for the distinction and it echoes no state. |
| `403` | empty | `workerId` is not the job's current owner. |
| `404` | empty | Unknown `jobId`. |
| `400` | `ErrorResponse` | Bean-validation failure (missing `workerId`, oversized `detail`). |
| `413` | `{"error":"PAYLOAD_TOO_LARGE",…}` | Body over `gateway.job.max-fail-body-bytes` (see WOC-33). |

`403`/`404` stay opaque and mutually indistinguishable, exactly like `submitResult` (`SubmitResultOutcome`'s javadoc, F02-05/SR-04): a worker-token holder enumerating `jobId`s must not learn another team's `reviewId` or Review status. **No response field ever carries `reviewId` or a status** — that is a deliberate tightening relative to `/result`, which has to return them for contract reasons.

### 5.3 Reason codes (closed set, audit-only)

| Code | Worker-side trigger |
|---|---|
| `LLM_EMPTY_RESPONSE` | `LlamaClient.toResult` — no choices / no message content (`LlamaClient.java:180,185`). |
| `LLM_ERROR` | `LlamaClient.parseResponse` non-2xx or unparseable body (`:146,:154`); generic `LlamaException`. |
| `LLM_TIMEOUT` | `WorkerLoop.awaitLlamaResponse` `TimeoutException` (`WorkerLoop.java:258`). |
| `LLM_RESPONSE_TOO_LARGE` | `BoundedInputStream.ResponseTooLargeException` (`LlamaClient.java:169`). |
| `PROMPT_INVALID` | `AbandonJobException` from `PromptTemplateService.resolve` (unknown `promptVersion`, oversized diff, too many system messages). |
| `WORKER_ERROR` | anything else classified worker-side. |

| Id | Requirement |
|---|---|
| **WOC-22** | Classification happens in the Worker. Suggested mechanism: a `JobFailureReason` enum in `worker/.../error`, carried as a field on `LlamaException`/`AbandonJobException` with sensible defaults (`LLM_ERROR` / `PROMPT_INVALID`) so no throw site is forced to change. |
| **WOC-23** | The Gateway MUST treat `reason` as **untrusted input**: whitelist-parse against its own copy of the enum; an unknown value maps to `UNKNOWN` with a WARN — never a `400`, never `Enum.valueOf`. (Same forward-compat/robustness precedent as `Backend.promptMessageFormat`, whose javadoc spells out why.) This keeps independent Gateway/Worker deploys safe in both directions. |
| **WOC-24** | **No control flow may depend on `reason` or `detail`.** The requeue-vs-fail decision stays exactly `attempts >= max-attempts` inside `RetryManager`. Reason and detail are audit text only. This is what keeps "retry logic lives only in the Gateway" true even though the Worker now speaks about failures. |
| **WOC-25** | `detail` MUST be sanitized and truncated **server-side** before it touches a log, `review_events.details`, or `review_jobs.last_error`: `TextSanitizer` character-class stripping (Cc incl. `\n`/`\t`, Cf incl. bidi overrides, Zl/Zp, `<`/`>`) + hard cap at 200 chars. Reuse `TextSanitizer.sanitizePath(detail, 200)`; recommended readability improvement is a delegating alias `sanitizeSingleLine(String, int)` on the same class (no new logic, no second implementation of the F-DC-02 lesson). A `null`/empty result means "omit the field". This closes CRLF log-injection and control-character storage in one place. |

### 5.4 Gateway-side flow and the ownership-under-lock requirement

```
Worker                       JobController        QueueManager           RetryManager               ChunkCoordinator
  │ POST /jobs/{id}/fail          │                    │                      │                            │
  ├───────────────────────────────>                    │                      │                            │
  │                               ├── reportFailure ───>                      │                            │
  │                               │                    │ (unlocked pre-check: exists? owned?)               │
  │                               │                    ├── requeueOrFail(jobId, reason, expectedWorkerId) ─>│
  │                               │                    │                      │ TX#1 REQUIRES_NEW:         │
  │                               │                    │                      │  SET LOCAL lock_timeout 3s │
  │                               │                    │                      │  SELECT … FOR UPDATE       │
  │                               │                    │                      │  owner mismatch -> no-op   │
  │                               │                    │                      │  not RUNNING  -> no-op     │
  │                               │                    │                      │  attempts<max -> QUEUED    │
  │                               │                    │                      │  else         -> FAILED    │
  │                               │                    │                      │  last_error := sanitized   │
  │                               │                    │                      │  not_before  := now+delay  │
  │                               │                    │                      │ COMMIT (job lock released) │
  │                               │                    │                      ├── recomputeAndApply ──────>│ TX#2: parent lock
  │  200 {"accepted": true}       │<───────────────────┤<─────────────────────┤                            │
  │<───────────────────────────────                    │                      │                            │
```

| Id | Requirement |
|---|---|
| **WOC-26** | `QueueManager.reportFailure(jobId, workerId, reason, detail)` MUST **not** be `@Transactional` (unlike its `heartbeat`/`submitResult` siblings). `RetryManager.requeueOrFail` opens its own `REQUIRES_NEW` transaction and then, *after that commits*, takes the parent-row lock via `ChunkCoordinator`. An outer transaction here would hold a Hikari connection across both phases and reintroduce exactly the lock-ordering hazard CSR-17 removed. Follow `RetryManager.requeueOrFail`'s own "plain orchestrating method" precedent. |
| **WOC-27** | **The ownership check MUST be re-evaluated inside the locked transaction**, not only in the unlocked pre-check. Otherwise this race silently corrupts a *different* attempt: pre-check reads `owner=W1` → concurrently the stale-heartbeat sweep requeues the job and `W2` claims it (`owner=W2`, fresh attempt, LLM already running) → `RetryManager` (which today does not look at `workerId` at all) requeues `W2`'s healthy job. Implementation: `RetryManager` gains an owner-aware entry point `requeueOrFail(Long jobId, String reason, String expectedWorkerId)`; inside `requeueOrFailJobOnly`, after `findByIdForUpdate`, a non-null `expectedWorkerId` that does not equal `job.getWorkerId()` is a no-op returning `OWNERSHIP_MISMATCH`. The existing `requeueOrFail(jobId, reason)` stays as a thin delegate passing `null` (no expectation) so `TimeoutManager`'s two call sites are untouched. |
| **WOC-28** | `RetryManager` returns a small outcome record (e.g. `RequeueOutcome(Outcome outcome, Long reviewId)` with `APPLIED_REQUEUED / APPLIED_FAILED / NOOP_NOT_RUNNING / NOT_FOUND / OWNERSHIP_MISMATCH`) so the controller can map `NOT_FOUND → 404`, `OWNERSHIP_MISMATCH → 403`, everything else → `200 {"accepted": true}`. `reviewId` is used only for the internal `ChunkCoordinator` call and Gateway logging — never returned to the Worker. |
| **WOC-29** | `RetryManager` MUST write `review_jobs.last_error` (sanitized, ≤512 chars) on **both** branches (requeue and fail), for worker-reported and sweep-originated calls alike. The column exists since V1 and has never been written except being nulled at claim time (`QueueManager.claimJobRow:201`). |
| **WOC-30** | Audit trail reuses the existing `RETRY`/`FAILED` events written by `JobStateMachine.transition`. The `details` string carries the origin: `"worker-reported: reason=<CODE>[; detail=<sanitized>]"` vs. the sweep's existing `"heartbeat timeout"`/`"max duration exceeded"`. **No new `EventType` value and no `ck_event_type` migration** — see §8.3. |
| **WOC-31** | Gateway logs one INFO per accepted report: `"Worker-reported job failure: jobId={} workerId={} reason={}"`. An idempotent no-op logs at INFO too (`"…ignored, job is {}"`), an ownership mismatch at WARN (matching `heartbeat`/`submitResult` — a mismatch is a security-relevant signal). |
| **WOC-32** | Concurrency/idempotency: the report introduces **no new lock and no new lock ordering** — it reuses `RetryManager` verbatim, whose job-row lock is bounded by `SET LOCAL lock_timeout = '3s'`. A `PessimisticLockingFailureException`/`QueryTimeoutException` on that lock MUST be caught and mapped to `200 {"accepted": true}` (the sweep will handle the job); it must never surface as a `500` to the Worker. |
| **WOC-33** | `RequestBodySizeLimitFilter` MUST gain a `/jobs/{id}/fail` pattern capped by `gateway.job.max-fail-body-bytes` (default `4096`), same fail-fast-at-the-edge rule as `/reviews` and `/jobs/{id}/result`. |

### 5.5 Worker-side sending rules

| Id | Requirement |
|---|---|
| **WOC-34** | The report is sent from `WorkerLoop.processJob`'s existing `catch (AbandonJobException | LlamaException e)` block (`WorkerLoop.java:196-198`), i.e. **after** `heartbeatScheduler.stop()` has run (it is in the inner `finally`, which executes before the catch) and **before** the loop returns to `claim()`. Synchronous by design — the whole point is to collapse the 210s passive window to ~0. |
| **WOC-35** | **Single best-effort attempt**, using the existing `gatewayRestClient` (`network.gateway-timeout-sec`, 10s). **No redelivery loop, no backoff, no retry.** Any failure (`GatewayUnavailableException`, non-2xx, 404 from an older Gateway that has no such endpoint) is logged at WARN, counted in `worker.gateway.errors`, and swallowed. The Worker then proceeds to the next claim. |
| **WOC-36** | Consequently the Gateway's stale-heartbeat sweep **remains the correctness backstop** and this endpoint is a pure latency optimization. Nothing in the system may become dependent on a report being delivered. This is what keeps the Worker a stateless HTTP client with no retry logic and no durable outbox. |
| **WOC-37** | Paths that MUST NOT report (each for a specific reason): <br>• `abortSignal` fired (`WorkerLoop.java:211-215,222-225`) — the Review went `CANCELLED`/`OBSOLETE`; the Gateway already owns that transition and the job is no longer `RUNNING`. <br>• `RedeliveryOutcome.ABANDONED` (`:230-237`) — a *result* exists and the Gateway may already have it; reporting failure could contradict a result that lands from a concurrent retry, and the thread is interrupted anyway. <br>• `GracefulShutdown`/`abandonCurrentJob` (`:118-128`) — the loop thread is interrupted, so an HTTP call would fail immediately; planned restarts are rare and the sweep covers them. Listed as a possible future extension, deliberately out of scope here. |
| **WOC-38** | Late in-flight heartbeat ticks are harmless and no extra synchronization is required. `HeartbeatScheduler.stop()` uses `shutdownNow()` and does not await an in-flight tick, so a tick can theoretically land after the report. Analysis: if the job was requeued, `QueueManager.heartbeat` returns `accepted(false)` **before** touching `heartbeat_at` (`QueueManager.java:347-350`), so it cannot resurrect the freshness of a requeued job; if the job was re-claimed by a different worker, the tick gets `403` and aborts; if `FAILED`, same non-RUNNING branch. Must be covered by a QA test (§10, T-3.6). |
| **WOC-39** | Worker metric: keep incrementing `worker.jobs.failed` as today; optionally add `worker.failures.reported` (Counter) to distinguish "failed and told the Gateway" from "failed silently". |

### 5.6 The fast-fail storm, and why `not_before` is part of this change

Removing the passive 210s wait also removes the *accidental backoff* it provided. Without a replacement, a deterministic worker-side failure (e.g. `max-tokens` exhausted by a `<think>` block — precisely the suspected cause here) burns all three attempts within seconds, and, because a permanently-failed chunk cascades to sibling cancellation in `ChunkCoordinator`, kills the whole Review almost instantly. Worse, a *transient* 30-second `llama-server` restart — plausible on launchd-managed Mac minis — would also consume all three attempts and permanently fail a Review that today survives.

| Id | Requirement |
|---|---|
| **WOC-40** | New column `review_jobs.not_before TIMESTAMPTZ NULL` (V4). `RetryManager` sets `not_before = now() + gateway.retry.requeue-delay` on the requeue branch (all callers: worker-reported and sweep alike — a small delay is harmless for timeout-originated requeues too). |
| **WOC-41** | `ReviewJobRepository.findNextQueuedJobIdForUpdate` gains `AND (j.not_before IS NULL OR j.not_before <= now())`. `ORDER BY priority DESC, created_at ASC, chunk_index ASC` and the `ix_review_jobs_queue` partial index are unchanged and still used (`status='QUEUED'` predicate); at this scale (tens of rows) no index change is warranted. |
| **WOC-42** | `gateway.retry.requeue-delay` default `30s`. Setting it to `0` disables the mechanism entirely (column stays `NULL`, predicate becomes a no-op) — the documented escape hatch if an operator wants the previous immediate-requeue behavior. It MUST be `>=` the Worker's `network.poll-interval-ms` for WOC-43 to hold. |
| **WOC-43** | Side benefit, worth recording: the delay also closes the practical window for a stale duplicate report landing on a *freshly re-claimed* attempt (§8.4). |
| **WOC-44** | Attempt accounting is unchanged: `attempts` is incremented at claim time only (`QueueManager.claimJobRow:193`), so a job that is requeued while no backend is `ACTIVE` simply waits in `QUEUED` without burning attempts. |

Net latency for the user's scenario: chunk fails at t=0 → report at t≈0.05s → `RETRY` event at t≈0.1s → claimable at t=30s → next poll ≤3s → **~33s instead of ~390s observed**.

---

## 6. Data model — migration `V4__worker_failure_reporting_and_backend_health.sql`

```sql
-- Part 2 (WOC-10): restart-safe probe-failure streak. NULL = not currently failing.
ALTER TABLE backends
    ADD COLUMN probe_failed_since TIMESTAMPTZ;

-- Part 3 (WOC-40): earliest time a requeued job may be claimed again. NULL = immediately.
ALTER TABLE review_jobs
    ADD COLUMN not_before TIMESTAMPTZ;
```

- Both columns are **nullable and additive**, with no backfill and no constraint change. Unlike V2, this migration is **rollback-tolerant**: an older JAR simply ignores both columns and degrades to today's behavior (immediate requeue, single-probe demotion). State that explicitly in `DEPLOYMENT.md`.
- Ordinary transactional DDL, no `CREATE INDEX CONCURRENTLY` — consistent with V1/V2/V3 running inside Flyway's single migration transaction.
- **No change to `review_events`** (no new `EventType`, `ck_event_type` untouched) and **no change to `review_results`**.
- `review_jobs.last_error` (V1) starts being written (WOC-29) — no DDL needed.
- Grants: no new table, so `DEPLOYMENT.md`'s GRANT block needs no change (the app role already has `UPDATE` on `backends` and `review_jobs`).

---

## 7. Configuration

### Gateway (`src/main/resources/application.yml`, `GatewayProperties`)

| Key | Default | Purpose |
|---|---|---|
| `gateway.backend.read-timeout` | `5s` → **`10s`** | WOC-16, safe after WOC-14. |
| `gateway.backend.failure-grace` | **`180s`** | WOC-11. Continuous-failure window before `ACTIVE → SUSPECT`. |
| `gateway.backend.defer-demotion-while-busy` | **`true`** | WOC-13. |
| `gateway.retry.requeue-delay` | **`30s`** | WOC-40/42. `0` disables. |
| `gateway.job.max-fail-body-bytes` | **`4096`** | WOC-33. |

Startup validation (same `@PostConstruct` pattern as SR-15/PMR): `failure-grace >= backend-health-interval` (a grace shorter than one probe interval is meaningless — fail startup or clamp with a WARN); `requeue-delay >= 0` and, if non-zero, a WARN when it is below 3s (the Worker's poll interval).

### Worker (`worker/src/main/resources/application.yml`, `WorkerProperties`)

| Key | Default | Purpose |
|---|---|---|
| `worker.log.idle-summary-interval-sec` | **`300`** (`0` = off), env `WORKER_IDLE_SUMMARY_INTERVAL_SEC` | WOC-04. |

No other Worker config changes. Notably `heartbeat.interval-sec` (60s) and `network.poll-interval-ms` (3000) stay as they are.

---

## 8. Rejected alternatives / decisions with an audit trail

**8.1 Finding 1 — a dedicated "progress" scheduler in the Worker.** Rejected: the heartbeat executor already ticks at exactly the cadence we want, is already crash-guarded (WSR-15), and is already scoped to the life of one job. A second thread would add a lifecycle to get wrong for zero benefit.

**8.2 Finding 2 — options weighed.**
- *(b) alone — just raise the probe timeout.* Insufficient: it does not help when `llama-server` answers `503` promptly (busy slot / loading model), which is the more likely of the two mechanisms in §2.2. It is also unsafe today because the probe runs inside a transaction (`BackendHealthChecker.probeAll` is `@Transactional`) — a 10s×N pass would pin a Hikari connection from a pool of 20 while `/jobs/claim` needs one. Adopted only *after* WOC-14 removes that coupling.
- *(a) alone — N consecutive failures.* Good, but a generation observed at 122–210s can outlast any grace window an operator would accept for genuine-outage detection. Needed, not sufficient.
- *(c) alone — skip probing busy backends.* Rejected in that form: a Worker heartbeat proves the *Worker* is alive, not the backend, so unconditionally trusting it would let a wedged `llama-server` stay `ACTIVE`. Adopted only in the narrowed, dispatch-neutral form WOC-13 (`runningJobs >= capacity`), where the backend is unclaimable anyway and the deferral provably cannot influence any scheduling decision.
- *Chosen: (a)+(c-narrowed)+(b-after-WOC-14).* Each covers the other's blind spot, and none of them weakens genuine-outage detection by more than one grace window.
- *Also rejected:* parsing `/health` bodies to classify `loading model` vs `no slot available` (couples the Gateway to a specific llama-server build — WOC-20); a dedicated `backends.consecutive_failures INTEGER` counter (time-based is robust to interval retuning and scheduler jitter; same one-column cost); keeping the streak in memory (violates "nothing cached that isn't reconstructible from the DB after a restart").

**8.3 Finding 3 — a dedicated `WORKER_FAILED` `EventType`.** Considered, rejected. It would require a `ck_event_type` CHECK-constraint migration, a second event row per failure (the report *and* the resulting `RETRY`/`FAILED`), and a decision about whether `StatisticsService` should count it. The forensic question the user actually asked of `review_events` — "who initiated this RETRY, the sweep or the worker?" — is fully answered by the standardized `details` prefix on the **same** row (WOC-30), with strictly less machinery. If appsec wants the report itself audited even when it is a no-op (a rejected/duplicate report is arguably an untrusted-input security event), this is the natural place to overrule the decision; the cost is one additive migration and it is a clean revision.

**8.4 Finding 3 — per-claim ownership token.** A late duplicate report could in theory be applied to a *new* attempt of the same job if that attempt is claimed by the same `workerId` before the duplicate lands. Note that `/jobs/{id}/heartbeat` and `/jobs/{id}/result` already have exactly the same theoretical weakness (ownership is `workerId`-only, with no per-claim nonce). The clean fix is an opaque `claimToken` minted per claim, returned in `ClaimJobResponse` and required by all three Worker-facing job endpoints. Deliberately **not** done here: it is a cross-cutting contract change well beyond this bugfix, and the residual risk is already small (the Worker sends the report exactly once with no retry — WOC-35 — so a duplicate can only come from HTTP-layer retransmission, which the JDK/RestClient stack does not perform for POST) and is made practically unreachable by `requeue-delay >= poll-interval` (WOC-43). **Flagged for appsec** as a candidate standalone hardening item.

**8.5 Finding 3 — a Worker-side durable outbox / retry for the report.** Rejected outright: it would give the Worker persistent state and retry logic, both explicitly forbidden. Best-effort + the existing sweep as backstop (WOC-35/36) achieves the same latency win with none of that.

---

## 9. Risks and trade-offs

| Risk | Severity | Mitigation |
|---|---|---|
| A genuinely dead backend now stays `ACTIVE` for up to `failure-grace` (180s), so up to ~3 minutes of claims can be handed to a dead backend. | Medium | Each such claim fails fast worker-side and is now **reported immediately** (Part 3), so the job is requeued in seconds rather than minutes — Parts 2 and 3 cover each other. Grace is configurable. |
| WOC-13 keeps an at-capacity backend `ACTIVE` even if it is wedged. | Low | Dispatch-neutral by construction (it is unclaimable at capacity anyway). Exit is guaranteed via the job's own heartbeat sweep / `job.max-duration` backstop, after which the preserved `probe_failed_since` demotes it on the very next pass. |
| Faster failure propagation surfaces failures to users faster — including turning a previously-invisible slow degradation into a visibly `FAILED` Review. | Low (intended) | `requeue-delay` keeps three genuine attempts spread over ≥60s instead of milliseconds. Arguably the desired outcome: a deterministic misconfiguration (`max-tokens`) should be reported in seconds, not after 20 minutes. |
| Probe pass can now exceed the 60s interval with many backends. | Low | WOC-17 re-entrancy guard; `N × 13s` worst case is ~2 minutes at the 10-backend scale ceiling, and a skipped pass is harmless (grace is time-based, not count-based). |
| New INFO log volume. | Low | 1 line/min/worker while busy (was 0), and idle spam drops from ~20 lines/min to ~0.2 lines/min. Net decrease. |
| New Worker-facing endpoint = new attack surface for a compromised worker token. | Medium | Same auth/ownership/opacity as the existing two; reason/detail are audit-only (WOC-24) and sanitized (WOC-25); body capped at 4 KB (WOC-33); the only reachable effect is on jobs the caller already owns, i.e. jobs it could already sabotage by simply not reporting. |
| `V4` adds a predicate to the hottest query in the system (`findNextQueuedJobIdForUpdate`). | Medium | Additive, `NULL`-tolerant, index-compatible; explicitly covered by QA (§10, T-3.7/T-3.8) and by the `requeue-delay: 0` escape hatch. |

**Operational recommendation (not a code change):** raise `LLAMA_MAX_TOKENS` above 4096 for reasoning-heavy models, or disable/limit the model's thinking block. An empty `content` after a 122–210s generation is the signature of the completion budget being consumed by `<think>…</think>`. After this branch this failure mode is reported in ~0.1s instead of ~6 minutes, but it is still a failure.

---

## 10. Test guidance for `qa-engineer`

Part 1: **T-1.1** idle Worker emits no INFO per poll; **T-1.2** a running job emits exactly one INFO per heartbeat interval with a monotonically increasing `elapsedSec`; **T-1.3** no log line ever contains diff/prompt/response content or the bearer token (WSR-10 regression); **T-1.4** the idle summary respects its interval and resets on a claim.

Part 2: **T-2.1** one failed probe does not demote; **T-2.2** continuous failure past `failure-grace` demotes exactly once; **T-2.3** a success mid-streak clears `probe_failed_since` and the grace restarts from scratch; **T-2.4** `SUSPECT → ACTIVE` on a single success; **T-2.5** at-capacity + fresh heartbeat defers demotion and **preserves** `probe_failed_since`; **T-2.6** once the job ends, the next failed probe demotes immediately (grace already elapsed); **T-2.7** `last_seen` advances only on success; **T-2.8** no DB connection is held during probe I/O (assert via a slow-probe stub + a concurrent `/jobs/claim` succeeding); **T-2.9** overlapping ticks are skipped by the guard; **T-2.10** the stall WARN fires when jobs are QUEUED and nothing is ACTIVE.

Part 3: **T-3.1** report on a RUNNING owned job → `200`, job `QUEUED`, `RETRY` event with `worker-reported: reason=…` details, `last_error` set; **T-3.2** report at `attempts == max` → `FAILED` + sibling cascade unchanged; **T-3.3** duplicate report → `200`, no second `RETRY` event; **T-3.4** report by a non-owner → `403`, **no state change** (must include the WOC-27 race: pre-check owner ≠ locked owner); **T-3.5** report racing the stale-heartbeat sweep → exactly one `RETRY`; **T-3.6** a heartbeat tick landing after the report does not refresh `heartbeat_at` of a requeued job (WOC-38); **T-3.7** `not_before` blocks the claim until it elapses and does not disturb ordering among other QUEUED jobs; **T-3.8** `requeue-delay: 0` reproduces immediate-claim behavior; **T-3.9** measure end-to-end abandon → `RETRY` latency (target < 1s + `requeue-delay`) and, separately, the *sweep-only* latency with reporting disabled, to settle the §2.3 open question; **T-3.10** unknown `reason` → `200` + `UNKNOWN` + WARN, never `400`; **T-3.11** `detail` containing CRLF / control chars / 10 KB of text → sanitized, truncated to 200, or `413` at the filter; **T-3.12** Gateway without the endpoint (simulated `404`) → Worker logs WARN and continues, sweep still recovers the job.

Environment note: no Docker on this machine — use Zonky embedded-postgres for anything touching Flyway/`FOR UPDATE`/`not_before`, and plain mocks elsewhere.

---

## 11. Hand-off to `appsec-engineer`

Focus areas for the threat-model round (target: `docs/worker-observability-and-claim-latency-threat-model.md`, or additions to `docs/worker-threat-model.md` + `docs/threat-model.md` if judged too small for its own file):

1. **New Worker-facing endpoint** `POST /jobs/{id}/fail`: authz (`/jobs/**` → `hasRole("WORKER")`), ownership under lock (WOC-27), opacity of `403`/`404` (F02-05/SR-04 parity), body cap (WOC-33), DoS via report spam (bounded by the 3s `lock_timeout`; assess whether a rate limit is warranted).
2. **Untrusted `reason`/`detail`** flowing into logs, `review_events.details`, and `review_jobs.last_error`: sanitization + truncation (WOC-25), no control-flow influence (WOC-24), log-injection.
3. **Availability semantics of WOC-13**: does a dispatch-neutral deferral meaningfully weaken outage detection? (Design argues no; please confirm or constrain.)
4. **Stale-duplicate-report window** and the deferred `claimToken` decision (§8.4) — appsec's call whether to escalate it into this branch.
5. **New INFO logging**: confirm no diff/prompt/response/token leakage in the added lines (WSR-10, WSR-18).
6. **Migration safety**: additive-nullable columns, rollback tolerance, and whether the claim-query predicate change needs a deployment note comparable to V2's.
7. Whether the audit trail should gain an explicit `WORKER_FAILED` event (§8.3) rather than the `details` prefix.

## 12. Documentation to update during implementation

`README.md` (Worker-facing API table + new config keys + new error/HTTP codes), `DEPLOYMENT.md` (V4 migration note, rollback tolerance, new env vars), `worker/README.md` (new outbound call, new log lines, `worker.log.idle-summary-interval-sec`), and the API-surface bullet list in `CLAUDE.md` (add `POST /jobs/{id}/fail`).
