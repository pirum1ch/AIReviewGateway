# AppSec SAST Report — fix/worker-observability-and-claim-latency (V4: Worker observability, fail-slow backend health, `POST /jobs/{id}/fail`)

Scope: `feature/prompt-manager..fix/worker-observability-and-claim-latency`, HEAD `8415d11`, working tree
carrying only three pre-existing unrelated changes I did not touch (a modified
`docs/security/feature-diff-chunking-sast-report.md` from an earlier session, untracked `.claude/skills/`,
untracked `.eml`). 13 commits (`a82b1f1` architecture → `f69b275` threat model → 8 dev commits
→ `2d389de` QA → `5965eff`/`8415d11` QA-fix round), 77 files, +4589/−219.

In scope: `service/BackendHealthChecker` (three-phase rewrite), `service/BackendDispatcher`,
`service/RetryManager` (owner-aware requeue, `last_error`, `not_before`), `service/QueueManager.reportFailure`,
`service/MetricsCounters`, `service/EventService.scrub`, `service/TextSanitizer.sanitizeSingleLine`,
`service/StatisticsService`, `controller/JobController.reportFailure`, `config/GatewayProperties`
(retry/backend-health subtree + startup validation), `config/RequestBodySizeLimitFilter` (WOR-09 decoded-path
fix), the four Worker-facing request DTOs, `dto/FailJobRequest`/`FailJobResponse`/`BackendView`/`MetricsResponse`,
`model/enums/JobFailureReason`, `model/Backend.probeFailedSince`, `model/ReviewJob.notBefore`,
`repository/ReviewJobRepository` (claim predicate + three new queries),
`db/migration/V4__worker_failure_reporting_and_backend_health.sql`, and on the Worker side
`core/WorkerLoop`, `core/HeartbeatScheduler`, `gateway/GatewayClient.reportFailure`,
`gateway/dto/FailRequest`, `error/JobFailureReason`/`LlamaException`/`AbandonJobException`,
`llama/LlamaClient`, `metrics/WorkerMetrics`, `config/WorkerProperties.Log`.

Method: independent re-verification of every one of my own pre-implementation MUSTs/SHOULDs
(**WOR-01…WOR-20**, `docs/worker-observability-and-claim-latency-threat-model.md` §7 release gate) against
the code that was actually built — reading and tracing the shipped code, never the architecture doc and
never the developer's or QA's own summaries — plus a general SAST pass (injection, access control /
ownership, resource exhaustion, information disclosure, error handling, migration safety, dependency
delta, logging discipline), plus explicit re-verification of the two QA-flagged defects and of the
incidentally-fixed pre-existing `RequestBodySizeLimitFilter` bypass.

**Suites (run by me, from a clean checkout of this branch):** Gateway `mvn test` →
`Tests run: 618, Failures: 0, Errors: 0, Skipped: 0`, exit 0. Worker `mvn -f worker/pom.xml test` →
`Tests run: 135, Failures: 0, Errors: 0, Skipped: 0`, exit 0. Both match the numbers reported to me; no
regressions anywhere in the pre-existing suites.

**Scanners (run by me):** `semgrep` (`p/java` + `p/sql-injection` + `p/secrets` — the exact gate config
from `.github/workflows/security-gate.yml`) over `src/main/java` + `worker/src/main/java` → **0 findings**,
0 errors, 155 files. `gitleaks detect` over the full history (95 commits, 2.48 MB) → **no leaks found**.
SCA: `pom.xml` and `worker/pom.xml` have a **zero-byte diff** vs. `feature/prompt-manager` — no dependency
delta to analyse. `.github/workflows/security-gate.yml` and `docker-compose.yml` are likewise untouched.

---

## Round-1 verdict (superseded by the verification round at the end of this document): **NEEDS ONE MORE DEV PASS** — 1 High + 1 Medium block the merge.

Severity counts: Critical 0 · **High 1** · **Medium 1** · Low 2 · Info 9.

This is a good branch. 17 of the 18 blocking MUSTs are genuinely closed in shipped code, both QA-found
defects are correctly and completely fixed, the untrusted-input handling (WOR-05/06/07) is the strongest
in the repo to date, and the pre-existing percent-encoding body-cap bypass (WOR-09) is properly closed on
all three endpoints without regressing any of them.

But the branch's own headline safety property does not hold:

1. **F-WOC-01 (High).** WOR-01's *formula* is implemented exactly as I specified it — and the *property* it
   was written to enforce ("the attempt budget MUST NOT be exhaustible inside one backend-demotion grace
   window") still fails with the shipped defaults, because the formula omits the backend-outage **detection
   latency**. The attempt clock starts when the backend dies; the grace clock starts at the first *failed
   probe*, up to `backend-health-interval` (60s) + `backend.read-timeout` (10s) later. With
   `requeue-delay=90s`, `max-attempts=3`, `failure-grace=180s`, `backend-health-interval=60s`, the third
   attempt is claimed at ≈ T+180s while the earliest possible demotion/dispatch-decline is T+180s…T+240s —
   so in nearly every phase alignment the third attempt is handed to a backend already known-dead, the
   Review goes permanently `FAILED`, and `ChunkCoordinator` cascades `CANCELLED` to every sibling chunk.
   That is **WOT-01's exact scenario**, which the threat model rated High/BLOCKING, and it is an
   availability *regression* against pre-branch behavior (where the same outage parks the job in `QUEUED`,
   burning no attempts). The end-to-end test WOR-01 called for was never written — only its
   startup-validation half — which is why green suites did not surface this.
2. **F-WOC-02 (Medium).** `BackendHealthChecker.applyFailure` returns from the WOC-13 at-capacity deferral
   **before** `probe_failed_since` is ever set, so a failure streak that *begins* while the backend is busy
   is never recorded at all. That silently disables **WOR-10** (dispatcher declines a past-grace backend),
   **WOR-11** (stall-WARN eligibility) and **WOR-13** (deferral cap) in precisely the scenario each of them
   was written for — a `llama-server` that dies mid-job on a capacity-1, 1:1-paired host, i.e. the
   documented deployment. Two of those three are blocking MUSTs. The unit test for this path asserts
   `probeFailedSince == null` and thereby encodes the defect as expected behavior.

Neither finding needs a redesign: F-WOC-02 is a three-line reordering, and F-WOC-01 is either a default
change plus a corrected startup rule, or (better) a two-line change in `BackendDispatcher`. They interact —
fixing F-WOC-02 also removes one of the two ways the grace clock starts late — so they should land together.

**AppSec must-fix before merge:** F-WOC-01, F-WOC-02.
**Should-fix in the same pass (cheap, same files):** F-WOC-03, F-WOC-04.
**Non-blocking, fix at leisure:** F-WOC-05 … F-WOC-13.

---

## Findings

| # | Severity | CWE / OWASP | Where (file:line) | Description | Remediation |
|---|----------|-------------|-------------------|-------------|-------------|
| **F-WOC-01** | **High** (CVSS:3.1 `AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:N/A:H` ≈ 5.9 as an unauthenticated-trigger availability defect; rated High in context because it is an availability *regression* vs. pre-branch behavior and destroys work that is unrecoverable without a CI re-run) | CWE-696 (incorrect behavior order) / CWE-770, A04:2021 | `GatewayProperties.java:203-210` (the WOR-01 rule) + `:549` (`requeueDelay=90s`) + `:765` (`failureGrace=180s`); `BackendHealthChecker.java:180-192`; `BackendDispatcher.java:77,93-98`; `RetryManager.java:147-164` | **WOR-01's property is not achieved by WOR-01's formula: the attempt budget is still exhausted before a dead backend can be demoted or declined.** The startup rule enforces `requeue-delay × (max-attempts − 1) ≥ failure-grace` (90×2 = 180 ≥ 180 — passes), but the two quantities are measured **from different instants**. The attempt clock starts when the backend dies (the in-flight Worker call fails within milliseconds and `POST /jobs/{id}/fail` requeues immediately); the grace clock starts at `probe_failed_since`, which can only be set by a probe pass, i.e. anywhere in `[T, T+backend-health-interval]` (60s), plus up to `backend.read-timeout` (10s) for the probe itself. Trace with shipped defaults: `T+0` backend dies, attempt 1 fails, `not_before=T+90`; `T+90` attempt 2 claimed (backend `ACTIVE`, streak < grace ⇒ `BackendDispatcher` allows), fails, `not_before=T+180`; `T+180` attempt 3 claimed — declined only if `probe_failed_since ≤ T`, which is impossible since `probe_failed_since ≥ T` by construction — fails ⇒ `attempts(3) ≥ max(3)` ⇒ `FAILED` ⇒ `ChunkCoordinator` cascades `CANCELLED` to every sibling chunk. Earliest demotion/decline is `T+180…T+240`. So a `llama-server` outage of roughly 3 minutes (a model reload on a Mac mini is routinely longer) permanently kills the in-flight Review, where **pre-branch the same outage was survivable** (single-probe demotion parked the job in `QUEUED`, and a `QUEUED` job burns no attempts — WOC-44). This is WOT-01 unclosed, not a new threat; the implementation faithfully matches what I wrote, and the arithmetic I wrote is what is wrong. F-WOC-02 widens the same gap further (the streak may never start at all while the backend is busy). | Either (a) **correct the coupling**: make the startup rule `requeue-delay × (max-attempts − 1) ≥ failure-grace + scheduler.backend-health-interval + backend.read-timeout` and raise the shipped `requeue-delay` default to `150s` (2×150 = 300 ≥ 250) — cheap, but it lengthens every recovery; or, preferred, (b) **make dispatch fail-fast while status stays fail-slow**: have `BackendDispatcher.resolveClaimableBackend` decline a backend with **any** non-null `probe_failed_since`, not only a past-grace one. That is the honest reading of "dispatch-neutral" — a backend that has just failed a probe should not receive new claims, while its *status* still only flips after the full grace window (WOC-11's actual goal is avoiding status flapping, not handing work to a dead host). It caps a healthy backend's cost at one probe interval of no claims, restores the pre-branch "park in `QUEUED`" behavior, and makes the coupling far less load-bearing. In either case, **write WOR-01's second test** (the one never written): backend stubbed permanently unreachable ⇒ the third attempt is *not* claimable before demotion/decline, and the Review does not reach `FAILED`. |
| **F-WOC-02** | **Medium** | CWE-754 (improper check for unusual condition) / CWE-390, A04:2021 | `BackendHealthChecker.java:166-178` (`applyFailure`, the early `return false` at `:174` precedes the `probeFailedSince` assignment at `:176-178`) + `:203-220` (`shouldDeferDemotion`); test `BackendHealthCheckerTest.java:160-172` | **A failure streak that begins while the backend is at capacity is never recorded, which silently disables WOR-10, WOR-11 and WOR-13.** `applyFailure` evaluates `shouldDeferDemotion(...)` **first** and returns before `if (backend.getProbeFailedSince() == null) setProbeFailedSince(now)`. So for the single most likely real outage — `llama-server` dies or wedges *while processing a job*, on a capacity-1 host paired 1:1 with its Worker (the documented deployment) — every probe is deferred and `probe_failed_since` stays `NULL` indefinitely. Consequences, each of which is exactly the control that was supposed to compensate: (i) `BackendDispatcher.isPastGraceFailureStreak` sees `null` ⇒ returns `false` ⇒ **WOR-10 never fires**, so the backend is claimable the instant the running job frees a slot — the WOT-07 window WOR-10 exists to close; (ii) `hasEligibleActiveBackend()` sees `null` ⇒ counts the dead backend as eligible ⇒ **WOR-11's stall WARN is suppressed**, i.e. the one alarm this branch adds is silenced by the very deferral WOR-11 was written against; (iii) `shouldDeferDemotion`'s own WOR-13 cap is keyed on `probeFailedSince` ⇒ `null` ⇒ **the deferral is unbounded**, the opposite of "cannot defer forever". Note the code comment at `:169-171` ("`probe_failed_since` is preserved (neither cleared nor restarted)") is true only for a streak that already started; and `BackendHealthCheckerTest.atCapacityWithFreshHeartbeatDefersDemotionAndPreservesStreak` asserts `getProbeFailedSince()).isNull()` under a comment claiming the streak is preserved — the defect is pinned as expected behavior, which is why the suite is green. WOR-13's own test only passes because it seeds `probe_failed_since` by hand, a state the deferral path cannot actually produce. | Record the streak **before** the deferral decision: move `if (backend.getProbeFailedSince() == null) backend.setProbeFailedSince(now);` above the `shouldDeferDemotion` call and persist it on the deferral path too (`backendRepository.save(backend)` before the early return). The deferral then keeps its intended meaning — "do not flip the *status* while this backend is usefully busy" — while `probe_failed_since` stays the honest, restart-safe record of when the backend stopped answering, so WOR-10/11/13 all engage. Fix the test to assert the streak is **set** (and that status stays `ACTIVE`), and fix the two misleading comments. |
| **F-WOC-03** | Low | CWE-396 (declaration of catch for generic exception — here, too *narrow*) / CWE-755, A04:2021 | `WorkerLoop.java:290-297` (`reportFailureBestEffort`) vs. `GatewayClient.java:135-148`; loop guard at `WorkerLoop.java:167-198` | **The new best-effort report can kill the worker-loop thread — the exact silent-stall failure class this feature exists to eliminate.** `reportFailureBestEffort` catches only `GatewayUnavailableException`, and `GatewayClient.reportFailure` maps only `RestClientResponseException` and `ResourceAccessException` into it. Any other `RuntimeException` crossing that call (a `RestClientException` subtype from response conversion, an `IllegalArgumentException` from URI templating, an `HttpMessageConversionException`) propagates out of `processJob`'s `catch (AbandonJobException \| LlamaException)` block — which is itself the handler, so nothing above it catches — and out of `runLoop`'s `while`, whose only `catch` covers `gatewayClient.claim`. The thread then exits (`"worker-loop thread exiting"`), the Worker process stays up and healthy-looking, and it silently stops claiming forever. WOC-35 states the report is best-effort and "nothing in the system may become dependent on this report being delivered" — the code does not yet honor that for unmapped exception types. | `catch (RuntimeException e)` in `reportFailureBestEffort` (log WARN, `incrementGatewayErrors()`, swallow) — it is by definition best-effort. Consider also a `catch (RuntimeException)` guard around the body of `runLoop`'s `while` so no future call site inside `processJob` can de-schedule the loop, mirroring `HeartbeatScheduler`'s WSR-15 crash guard. |
| **F-WOC-04** | Low | — (test-coverage gap against a blocking MUST) | `worker/src/test/java/com/review/worker/core/WorkerObservabilityLoggingContentTest.java` (covers WOC-01 claim, WOC-02 heartbeat tick, WOC-04 idle summary) | **WOR-17's regression test is missing for the one new log site that actually carries the risk.** The requirement was "no new log statement passes a collection or content-bearing object to an slf4j placeholder … `SECTIONMARKER` must not appear in any Worker log line ≤ INFO". Three of the four new INFO sites have content assertions; the fourth — `WorkerLoop.runInference`'s `"Starting inference (… diffChars={}, systemMessages={} …)"` (`WorkerLoop.java:302-308`), the one that would have dumped every Prompt-Manager section had it been written the obvious way — has none. I verified by reading that it passes `systemMessageCount` (an `int`) and `diffChars` (an `int`), never `List<String>` or `prompt.messages()`, so the control **is** correct in code; only its regression guard is absent, and `SensitiveDtoToStringMaskingTest` guards the DTO's `toString()`, not this call site. | Add the marker test WOR-17 specifies: a claim payload whose `systemMessages` contain `SECTIONMARKER`, asserting no captured log event at level ≤ INFO contains it. |
| F-WOC-05 | Info | CWE-1059 (incorrect documentation) | `src/test/java/com/review/gateway/config/GatewayPropertiesApplicationYamlBindingTest.java:40-48` and `:19-33` | Two stale/incorrect claims in the QA test that caught the WOC-16 regression, left behind after the fix. (a) The javadoc still opens "**Finding: this currently FAILS.** `gateway.backend.read-timeout` is still hard-coded to `5s`" — it no longer does (`application.yml:79` is `10s`, verified). (b) The javadoc asserts "nothing in the existing suite can ever catch drift between `src/main/resources/application.yml` and its own documented values" — that is not true: `ApplicationYamlBootTest` (added in the Prompt Manager round for F-PM-02) already boots the **real** `src/main/resources/application.yml` through `spring.config.location=file:…` and Spring's own `ConfigData` machinery, and would have caught this class of drift given an assertion. The infrastructure existed; only the assertion was missing. | Rewrite the javadoc as a regression note ("this pinned the WOC-16 drift; fixed in `5965eff`"), and either fold the two assertions into `ApplicationYamlBootTest` (one place that boots the shipped YAML) or cross-reference it so the next reviewer does not conclude the gap is structural. |
| F-WOC-06 | Info | CWE-665 (improper initialization) | `db/migration/V4__worker_failure_reporting_and_backend_health.sql:10` | The migration uses session-scoped `SET lock_timeout = '5s'`, not `SET LOCAL`. Flyway borrows its connection from the same Hikari pool and returns it afterwards without resetting session state, so one pooled connection keeps a 5s `lock_timeout` for the life of the process. The direction is benign (fail-fast, and every lock-taking path already sets its own `SET LOCAL lock_timeout = '3s'`), and it is invisible in tests because Zonky hands out fresh databases — but it is unintended cross-talk from a migration into runtime. | `SET LOCAL lock_timeout = '5s';` — Flyway runs each migration in a transaction, so `LOCAL` is sufficient and scoped. |
| F-WOC-07 | Info | CWE-1188 (insecure/inconsistent default) | `GatewayProperties.java:773-778` vs. its own javadoc and `README.md:174` | `Backend.deferDemotionMax` hard-codes `Duration.ofMinutes(45)` while its javadoc and the README both say it "defaults to `gateway.job.max-duration`". The two agree only because `Job.maxDuration` also happens to be `45m`. An operator who lowers `gateway.job.max-duration` (a documented, expected tuning knob) silently leaves the deferral cap at 45m — the WOR-13 bound then no longer tracks the quantity it is defined against. | Either derive it at validation time when unset (`if (deferDemotionMax == null) deferDemotionMax = job.getMaxDuration();`) or change the javadoc/README to say "45m, chosen to match the shipped `gateway.job.max-duration`". |
| F-WOC-08 | Info | CWE-1059 | `DEPLOYMENT.md` §8b ("What NOT to do") | WOR-16 required `requeue-delay: 0` to be documented in `DEPLOYMENT.md` as **explicitly re-accepting the SR-06 stale-duplicate-report residual**. §8b documents the both-or-neither pairing and the revert-to-pre-V4 semantics, but not the residual being re-accepted. The boot-time WARN in `GatewayProperties.validateRetryAndBackendHealthOnStartup()` does name it correctly, so the information exists — just not where an operator reads it before flipping the switch. | One sentence in §8b: setting both to `0` re-accepts the SR-06 stale-duplicate-report window (a `/fail` report from a previous attempt can land on a fresh one) and the WOT-01 fast-fail-storm risk. |
| F-WOC-09 | Info | CWE-20 / CWE-172 (encoding error) | `RequestBodySizeLimitFilter.java:99-106` (`decodedPath`) | Two edge behaviors of the WOR-09 fix, both currently non-exploitable, worth pinning. (a) `UriUtils.decode` throws `IllegalArgumentException` on a malformed `%`-sequence; Tomcat normally rejects those with `400` before the filter runs, but if one ever reaches the filter the request dies as a container-level `500` rather than a `400` through `GlobalExceptionHandler` (fail-**closed**, so not a bypass). (b) The whole path is decoded before segmentation, so `%2F` becomes a segment separator here while Spring MVC decodes per segment — the filter therefore matches a **superset** of what MVC routes (e.g. `/jobs/1%2Fresult` gets capped and then 404s), again fail-safe. Also worth recording as correct-by-luck: the `413` log line at `:65-66` deliberately prints `request.getRequestURI()` (the **raw**, still-encoded path), which is what keeps a `%0d%0a` in the URI from becoming a real CRLF in the log — do not "improve" that to the decoded path. | Wrap the decode in a `try/catch (IllegalArgumentException)` that falls back to the raw path (still matching the plain patterns) and let the request continue to Spring's own `400`. Add a comment on the `:65` log line explaining why the raw URI is logged there on purpose. |
| F-WOC-10 | Info | CWE-755 | `BackendHealthChecker.java:108-121` (`runPass`) | A `DataAccessException` from phase C (`applyProbeResult` → `writeTransactionTemplate.execute`) propagates out of the per-backend loop, so `checkQueueStalled()` and `checkStuckQueuedJobs()` — the two alarms this feature adds, including the just-added WOR-15(a) one — are skipped for that entire tick. `ScheduledJobs.probeBackends` catches and logs, so the schedule survives; but a DB hiccup silences the stall alarms exactly when they are most useful. | Wrap the per-backend body in its own `try/catch (RuntimeException)` (log + continue to the next backend), or move the two checks into a `finally`. |
| F-WOC-11 | Info | — (hygiene) | `TextSanitizer.java:39-55` | Inserting `sanitizeSingleLine` between `sanitizePath`'s javadoc and `sanitizePath` itself orphaned that javadoc: two consecutive javadoc blocks now precede `sanitizeSingleLine`, and the CSR-09 documentation of `sanitizePath`'s contract (what it strips, that `null` means "drop this value") is attached to nothing. The class-level javadoc still carries the substance, and behavior is unchanged. | Move the original block back above `sanitizePath`. |
| F-WOC-12 | Info | — (observability accuracy) | `WorkerMetrics.java:57-61` vs. `WorkerLoop.java:291-296` | `worker.failures.reported` is described as counting reports "the Gateway accepted (or that were at least attempted)" but is incremented only on the success path — a failed attempt increments `worker.gateway.errors` instead. Operationally fine (the two together give the full picture) but the description overstates what the counter means, and WOC-39's stated purpose is "whether the fast-recovery path is actually landing". | Either increment before the call (true "attempted") or correct the description to "reports the Gateway accepted". |
| F-WOC-13 | Info (pre-existing, newly reachable) | CWE-754 | `QueueManager.java:466-473` + `RetryManager.java:109-121` | If `ChunkCoordinator.recomputeAndApply` (called **after** `RetryManager`'s job transaction commits) throws a lock failure, `reportFailure`'s `catch (PessimisticLockingFailureException \| QueryTimeoutException)` swallows it and returns `200 ACCEPTED`. The job row is already committed as `QUEUED`/`FAILED` while the parent Review's derived status was never recomputed, and nothing re-runs the recompute later (a subsequent report/sweep on the same job returns `NOOP_NOT_RUNNING` with a `null` `reviewId`, so it does not retry the parent update either). The identical hazard already exists on the `TimeoutManager` path, so this is a pre-existing structural gap that the new endpoint merely reaches more often; it does not create a security exposure, only a stale parent status. | Out of scope for this branch. Track it: either retry the parent recompute on the next scheduler tick (a "parent status disagrees with its jobs" sweep) or narrow the `catch` so only the `RetryManager` phase is swallowed and a `ChunkCoordinator` failure is logged distinctly at WARN. |

---

## WOR-by-WOR verification against the shipped code

Verified by reading the shipped implementation, not the design doc, not the developer's commit messages,
and not the tests alone (where a test is the only evidence, that is stated).

| Req | Type | Verdict | Evidence |
|---|---|---|---|
| **WOR-01** | MUST | ⚠️ **PARTIAL — see F-WOC-01** | The startup rule (`GatewayProperties.java:203-210`), the paired zero escape hatch (`:169-186`), and the corrected `90s` default (`:549`) are all present, correct, and tested (`GatewayPropertiesRetryAndBackendHealthValidationTest`). The *property* the rule was meant to guarantee does not hold with the shipped defaults, because the rule omits outage-detection latency. The end-to-end test the requirement specified was never written. |
| **WOR-02** | SHOULD | Not implemented (acceptable) | `RetryManager.computeNotBefore()` (`:177-183`) derives the delay from config only, never from backend health. Legitimate: WOR-02 was the *alternative* that would have allowed relaxing WOR-01 to a WARN; the developer took the fail-fast route instead. Noting it because implementing WOR-02 would also be a valid F-WOC-01 remedy. |
| **WOR-03** | MUST | ✅ Met | `MetricsCounters` (process-local, `ConcurrentHashMap` keyed only by the three Gateway-constant labels — no unbounded growth from caller input); incremented at all four sites (`QueueManager.java:357,391,444-445,481-482`); surfaced ADMIN-only via `MetricsSnapshot`/`MetricsResponse`. **Zero `review_events` rows** are written for a rejected or no-op report — I traced every branch of `reportFailure`; the `NOT_FOUND`/`OWNERSHIP_MISMATCH`/`NOOP_NOT_RUNNING` paths never reach `EventService`. §4.7's "no authenticated unbounded-INSERT primitive" holds. |
| **WOR-04** | MUST | ✅ Met in substance | `QueueManager.java:462-464` composes `"worker-reported: reason=<ENUM>"` (Gateway constant, from a closed enum, **first**) `+ "; detail=<sanitized>"`; `RetryManager` appends its own constant `" (attempt X/Y)"`/`" (attempts exhausted: X/Y)"` suffix and documents the grammar in its class javadoc (`RetryManager.java:42-52`). Deviation from the letter of the requirement: the sanitized `detail` is not literally last — the Gateway-constant attempt suffix follows it. The security property (the origin discriminator cannot be forged by a crafted `detail`, because the prefix is emitted first from a fixed vocabulary and `detail` is Cc/Cf-stripped) is fully preserved, and the deviation is deliberate and documented. Accepted. |
| **WOR-05** | MUST | ✅ Met | `WorkerLoop.DETAIL_BY_REASON` (`:49-68`) is a compile-time `EnumMap` of fixed strings; `reportFailureBestEffort` passes only `reason.name()` and that constant. `WorkerLoop.java:261-263` replaced `log.warn(… e.getMessage())` with reason + `e.getClass().getSimpleName()`. Grep confirms **no** `getMessage()`/`toString()` anywhere in the Worker's logging or in the `FailRequest` construction path. Proven end-to-end by `WorkerLoopContractAndResilienceTest:854-884` (malformed llama JSON containing `SECRETMARKER` ⇒ neither the `/fail` body nor the log contains it). |
| **WOR-06** | MUST | ✅ Met | (a) `@NotBlank @Size(max=64) @Pattern("^[A-Za-z0-9._:-]{1,64}$")` on `workerId` in all four DTOs and on `backendId` in `ClaimJobRequest` — verified file by file; this also removes the pre-existing `VARCHAR(64)`-overflow `500` on `POST /jobs/claim`. (b) `reason` is `@Size(max=32)` + whitelist-parsed via `JobFailureReason.fromWireValue` (never `Enum.valueOf`, never `400`), and the unknown-value WARN logs **only the length** (`QueueManager.java:457-460`). (c) `detail` is sanitized **at ingress** (`QueueManager.java:461`, `sanitizeSingleLine(detail, 200)`) before it reaches `RetryManager`, any logger, `last_error` or `EventService`; the raw string provably does not propagate. Negative tests exist at all four endpoints (`JobControllerTest:294-360`). |
| **WOR-07** | MUST | ✅ Met | `EventService.scrub` (`:83-99`) now delegates to `TextSanitizer.sanitizePath(details, 4096)` **before** token-shape redaction and the 500-char cap — CSR-09 ordering preserved, no second implementation of the F-DC-02 lesson, `null` propagated as "drop the value". Existing token-redaction tests still pass. |
| **WOR-08** | MUST | ✅ Met | `RequestBodySizeLimitFilter:50,87-89` + `gateway.job.max-fail-body-bytes = 4096` (`GatewayProperties.java:576`, validated `>= 1` at startup). Tested over/under limit. |
| **WOR-09** | MUST | ✅ Met — see the dedicated section below | |
| **WOR-10** | MUST | ⚠️ **Implemented but inoperative — see F-WOC-02** | `BackendDispatcher.java:77,93-98` is correct code, placed before the capacity check, mirroring the checker's comparison field-for-field. It just cannot fire in the scenario it was written for, because `probe_failed_since` is never set on the deferral path. |
| **WOR-11** | MUST | ⚠️ **Implemented but inoperative — see F-WOC-02** | `BackendHealthChecker.checkQueueStalled()`/`hasEligibleActiveBackend()` (`:228-250`) implement exactly the required condition ("`ACTIVE` **and** not past a grace-elapsed streak"). Same root cause: the deferred-dead backend it is meant to catch has a `NULL` streak, so it still counts as eligible. |
| **WOR-12** | MUST | ✅ Met | `probeFailedSince` flows `Backend` → `BackendSnapshot` → `BackendView` → `GET /backends` (ADMIN-only, URL still deliberately withheld). The per-pass INFO lines distinguish "within grace" (`:189-190`), "deferred because at capacity" (`:172-173`) and demotion (`:186-187`). |
| **WOR-13** | SHOULD | ⚠️ Implemented, inoperative in the deferral case — see F-WOC-02 | `shouldDeferDemotion` (`:203-220`) checks the cap first, but keyed on `probeFailedSince`, which is `NULL` on the path that matters. Its test seeds a value the deferral path cannot produce. |
| **WOR-14** | MUST | ✅ Met | `RetryManager.computeNotBefore/databaseNow` (`:177-201`) takes the base instant from `SELECT now()` — the **database** clock — and the claim predicate compares against `now()` in the same database (`ReviewJobRepository:56`). Single clock end to end; no JVM/DB straddle. The `10m` upper cap is enforced (`GatewayProperties.java:162-165`). `databaseNow()` handles four driver return types and fails loudly on anything else. |
| **WOR-15** | MUST | ✅ Met — both halves; see the dedicated QA-fix section below | |
| **WOR-16** | MUST | ✅ Met (docs half partial — F-WOC-08) | `MIN_NONZERO_REQUEUE_DELAY = 15s` bounded against the Worker's `network.gateway-timeout-sec`, with the reasoning recorded in the constant's javadoc (`GatewayProperties.java:42-48`); `requeue-delay: 5s` refuses startup, `0` (paired with `failure-grace: 0`) starts with a WARN naming both re-accepted risks. |
| **WOR-17** | MUST | ✅ Met in code, ⚠️ one test missing (F-WOC-04) | No new slf4j call anywhere in the branch passes a `Collection` or content-bearing object: `WorkerLoop.java:302-308` logs `diffChars`/`systemMessageCount` as ints; `HeartbeatScheduler` logs ids/counts/`elapsedSec`; `GatewayClient.claim` logs `jobId`/`reviewId` and demotes the empty poll to DEBUG; the idle summary logs a count and the Worker's own configured backend id. Verified by reading every added log statement in the diff. |
| **WOR-18** | MUST | ✅ Met | `JobControllerTest:257-288` — `401` unauthenticated, `403` with CI, `403` with ADMIN, reachable only with WORKER. `SecurityConfig:55` (`/jobs/**` → `hasRole("WORKER")`) is unchanged and the new path is inside it; SR-16's one-role-per-path property is intact. |
| **WOR-19** | MUST | ✅ Met | The unlocked pre-check (`QueueManager.java:435-455`) is a cheap short-circuit only; `RetryManager.requeueOrFailJobOnly` re-checks ownership **inside** the `FOR UPDATE` transaction (`:137-141`) and the returned `RequeueOutcome` — not the pre-check — determines the HTTP status (`:475-492`). A lock timeout maps to `200 {"accepted": true}` (`:468-473`), never `500`. `QueueManagerReportFailureRaceIntegrationTest:149-238` exercises the genuine TOCTOU interleaving and asserts no state change and no spurious `RETRY` event. |
| **WOR-20** | MUST | ✅ Met | `getLastError()` has **no** call site outside `model/ReviewJob` and `RetryManager` (grep-verified across `src/main/java`). `ReviewStatusResponse` is unchanged; no CI- or Worker-facing body carries error text. |
| WOR-INH-1/2/3 | Accepted | Unchanged | SR-06 (claim token) and SR-20 (rate limiting) remain open as recorded; `403` vs `404` on `/jobs/{id}/*` remains a job-id existence oracle, deliberately preserved for parity — and the stricter half is honored: the `/fail` `200` body carries no `reviewId` and no Review status (`FailJobResponse` is `{accepted}` only). |

---

## The two QA-found defects: independent re-verification

### (a) `gateway.backend.read-timeout` drift — **VERIFIED-FIXED, and the safety justification genuinely holds**

- `src/main/resources/application.yml:79` is now `read-timeout: 10s`, matching `GatewayProperties.Backend`'s
  Java default (`:751`) and `README.md:173`.
- **No other path re-introduces a lower value.** I enumerated every config source in the repo:
  `src/test/resources/application.yml` sets only `gateway.backend.allowed-host-pattern` (no timeout);
  `docker-compose.yml` passes only `BACKEND_ALLOWED_HOST_PATTERN` for this subtree; there is no profile-
  specific YAML, no `.properties`, and no `${...}` placeholder on `read-timeout` for an env var to
  undercut. `DEPLOYMENT.md`'s env-file appendix does not mention it. The only two places the value exists
  now agree, and `GatewayPropertiesApplicationYamlBindingTest` pins the shipped file by path so the drift
  cannot silently return.
- **The WOC-14 reasoning chain holds in shipped code, not just in comments.** `BackendHealthChecker.runPass`
  is genuinely three-phase: phase A is a read-only `TransactionTemplate` that only loads candidates; phase B
  (`safeProbe` → `BackendProber.probe`) runs with **no** transaction and no `EntityManager` in scope — I
  confirmed `BackendProberImpl` holds only a `RestClient`, has no repository/`EntityManager` dependency and
  no `@Transactional`; phase C is a separate short write transaction that re-reads by id. Neither `probeAll`
  nor `ScheduledJobs.probeBackends` is `@Transactional`, and `open-in-view: false`, so no Hikari connection
  can be pinned for the probe's duration. `BackendHealthCheckerProbeTransactionIsolationTest` asserts this
  against a real database rather than by inspection. The `10s` raise is therefore safe as claimed, and
  WOC-17's `AtomicBoolean` re-entrancy guard (`:85-93`) correctly prevents overlapping passes when
  `N × 13s` exceeds the 60s interval.

### (b) WOR-15(a) stuck-`QUEUED` WARN — **VERIFIED-FIXED, fires correctly, no false positives, on a real production path**

- `ReviewJobRepository.countStuckQueuedJobs` (`:145-162`) is `status='QUEUED' AND not_before IS NOT NULL AND
  not_before < :cutoff`, with `cutoff = now − gateway.job.max-duration`
  (`BackendHealthChecker.checkStuckQueuedJobs`, `:260-267`). Named parameter, native query, no string
  concatenation.
- **Fires under the specified condition** and **does not false-positive on a legitimately delayed job**: a
  job still inside its `requeue-delay` window has `not_before` in the near past or the future, so
  `not_before < now − 45m` is false; a `NULL not_before` (immediately claimable, including every pre-V4
  row) is excluded explicitly. All three cases have tests
  (`BackendHealthCheckerStuckQueuedJobsTest:113-149`), and the "healthy backend present" fixture proves it
  is genuinely distinct from WOC-18's zero-eligible-backend WARN rather than a duplicate of it.
- **Wired into a pass that actually runs in production**: `checkStuckQueuedJobs()` is called from
  `runPass()` (`:119`), which is called by `probeAll()`, which is `@Scheduled` in `ScheduledJobs.java:64-71`
  at `gateway.scheduler.backend-health-interval`. No new scheduled job, as required. It is not a test-only
  path. Two caveats already filed as Info: an exception earlier in the pass skips it for that tick
  (F-WOC-10), and the `cutoff` is computed from the JVM clock while `not_before` is written from the DB
  clock — harmless at a 45-minute threshold, but the only place in the branch where the two clocks still
  meet.

---

## WOR-09: the incidentally-fixed pre-existing percent-encoding bypass

The pre-existing bug (filter matching `PathPattern`s against the raw, un-decoded `getRequestURI()` while
Spring MVC routes on the decoded path) is **correctly closed on all three protected endpoints**, and I
confirmed no endpoint's enforcement regressed in the process:

- `limitFor` now builds a `PathContainer` from a decoded, context-path-stripped path
  (`:80,99-106`), so `POST /jobs/1/%66ail`, `POST /jobs/1/%72esult` and `POST /re%76iews` all hit their
  caps — all three are tested (`RequestBodySizeLimitFilterTest:196-215`).
- **No regression on the happy paths**: plain `/reviews` (`320000`), `/jobs/{id}/result` (`500000`) and
  `/jobs/{id}/fail` (`4096`) still map to their own limits, boundary behavior is unchanged (`==` limit
  passes, `>` rejects), `/jobs/claim` and `/jobs/{id}/heartbeat` remain deliberately uncapped by this
  filter, non-POST methods and unrelated paths are untouched, and an under-limit percent-encoded path still
  passes through to the chain. All covered by tests, and I re-derived each by reading `limitFor`.
- **The fix cannot be inverted into a new bypass.** Whole-path decoding makes this filter match a
  *superset* of what MVC routes (`%2F` becomes a separator here but stays intra-segment for MVC), which is
  the fail-safe direction; matrix parameters are handled identically by both because both use
  `PathContainer` parsing; `..` segments and case differences produce no match in either. Two residual edge
  behaviors are recorded as F-WOC-09.
- Not regressed either: the `413` body still carries no internal detail, and the `413` log line still
  prints the **raw** URI, which is what prevents a `%0d%0a` in the path from becoming a real CRLF in the
  Gateway log.

---

## Other positive verifications (general SAST pass)

- **Injection.** Every new query (`findNextQueuedJobIdForUpdate`'s added predicate,
  `existsFreshRunningJobForBackend`, `countQueuedJobs`, `countStuckQueuedJobs`) is a static native query
  with `@Param` binding — no concatenation, no dynamic SQL, no caller-controlled fragment. Semgrep's
  `p/sql-injection` over both modules: 0 findings.
- **The claim predicate does not create head-of-line blocking.** `not_before` is a `WHERE` filter, not a
  post-`LIMIT` filter, so a deferred job never masks a claimable lower-priority one; `FOR UPDATE SKIP
  LOCKED` semantics and the `priority DESC, created_at ASC, chunk_index ASC` ordering are untouched.
  `QueueManager.claimJobRow` nulls `not_before` on every claim (`:214`), so a requeued-then-claimed job
  cannot carry a stale gate.
- **Lock discipline (CSR-17/18/19) unchanged.** `reportFailure` adds **no** new lock and no new ordering:
  it reuses `RetryManager`'s existing `REQUIRES_NEW` + `SET LOCAL lock_timeout = '3s'` + job-row-`FOR
  UPDATE`-then-commit-then-parent sequence, and is deliberately non-`@Transactional` so no outer
  transaction spans both phases. The unlocked pre-check takes no lock at all.
- **Ownership / access control.** All three Worker-facing job endpoints check `workerId`-equality against
  the claimed owner; `/fail` checks ownership **before** status, so a non-owner learns nothing about job
  state; a `QUEUED` job (owner `NULL`) yields `403` exactly like a mismatched owner. The `403`/`404`
  existence oracle is unchanged (accepted, WOR-INH-3), and no new response body echoes `reviewId` or
  status.
- **Resource exhaustion.** The `/fail` path costs one unlocked `SELECT` plus (only when the job is
  genuinely `RUNNING` and owned) one short bounded transaction; the no-op and rejected paths write
  nothing. `MetricsCounters`' map cannot grow beyond three keys. `FailJobRequest` is capped at the edge
  (4 KB), at the DTO (`@Size`), and again at ingress (200 chars after sanitization). No unbounded
  collection, no new in-memory accumulation, no new thread or scheduler.
- **Information disclosure.** `GlobalExceptionHandler.handleValidation` still returns `field + ": " +
  defaultMessage` and never the rejected value, so the new `@Pattern`/`@Size` constraints cannot reflect
  attacker input (SR-17 preserved). `last_error` is never surfaced (WOR-20). The Gateway's INFO/WARN lines
  for `/fail` print only ids, a Gateway-side enum, and sanitized text.
- **Migration safety.** V4 is two nullable additive columns, no backfill, no constraint change, no index
  change — metadata-only on PostgreSQL 11+; `ddl-auto: validate` plus the two matching entity fields means
  a mismatch is boot-blocking, which is the desired failure mode. Rollback tolerance is real, not asserted:
  an older JAR ignoring `not_before` claims immediately and ignoring `probe_failed_since` demotes on a
  single probe — both are pre-V4 behavior. Verified by running the full Flyway chain V1→V4 in the suite.
  `DEPLOYMENT.md` §8b documents it. Only nit: F-WOC-06.
- **Config-drift class of defect (F-DC-04/F-PM-02's lineage).** The three documented env vars this branch
  adds or touches all actually bind: `WORKER_IDLE_SUMMARY_INTERVAL_SEC` has a real placeholder in
  `worker/src/main/resources/application.yml:52`, and `README.md`/`worker/README.md`/`DEPLOYMENT.md` agree
  with it. The new Gateway properties are intentionally Java-default-only (no `${}` placeholders) and the
  README does not claim env names for them, so there is no repeat of F-PM-02. `ApplicationYamlBootTest`
  boots the real shipped YAML and now also exercises the new startup validation against it, so the shipped
  defaults are proven to satisfy the WOR-01 rule at boot.
- **Dependencies / secrets / CI.** Zero-byte `pom.xml` diff in both modules; `gitleaks` over 95 commits →
  no leaks; `semgrep` gate config → 0 findings; `.github/workflows/security-gate.yml` untouched, so the
  SR-23 gate covers this branch unchanged.

---

## Must-fix list for the next dev pass

1. **F-WOC-02 first** (three-line reorder in `BackendHealthChecker.applyFailure` + persist on the deferral
   path), because it also removes one of the two reasons the grace clock starts late. Fix the test that
   pins the defect and the two comments that describe behavior the code does not have.
2. **F-WOC-01**: prefer option (b) — `BackendDispatcher` declines any backend with a non-null
   `probe_failed_since` — and, if option (a) is chosen instead, change both the default and the startup
   rule together. Either way add WOR-01's missing end-to-end test (permanently-unreachable backend ⇒ the
   Review must not reach `FAILED` before the backend is declined/demoted).
3. **F-WOC-03**: widen `reportFailureBestEffort`'s catch to `RuntimeException`.
4. **F-WOC-04**: add the WOR-17 `SECTIONMARKER` assertion for the `Starting inference` log line.

Everything else (F-WOC-05 … F-WOC-13) is Info-grade and can ride along in the same pass or be deferred; none
of it blocks.

---

## Bottom line

`fix/worker-observability-and-claim-latency` is **not yet ready to merge to `master`**. The untrusted-input,
access-control, edge-cap, audit-trail, logging-discipline and migration work is complete and correct — 17 of
18 blocking MUSTs verified closed in shipped code, both QA-found defects properly fixed, the pre-existing
body-cap bypass genuinely eliminated on all three endpoints, and no new injection, disclosure or
exhaustion surface. What blocks is the availability property the branch exists to protect: with the
shipped defaults a ~3-minute backend outage still permanently fails an in-flight Review and cascades to its
sibling chunks (F-WOC-01), and the compensating controls that were supposed to catch that
(`probe_failed_since`-driven dispatch decline, stall WARN, deferral cap) never engage in the scenario they
were written for (F-WOC-02). Both fixes are small and local; neither requires a design change.

Send it back for one more `backend-developer` pass on F-WOC-01 and F-WOC-02 (plus F-WOC-03/F-WOC-04 while
in the same files), then a short appsec re-verification round focused on those two findings and on the two
new tests. Nothing else in this branch needs to be revisited.

Prefix note: the threat model's §5 refers to this report as
`docs/security/feature-worker-observability-sast-report.md` with prefix `F-WO-`; it is filed here under the
full feature slug with prefix `F-WOC-`, consistent with `F-DC-`/`F-PM-` and with the branch's other two
documents.

---

# Verification round (final) — fix commits `e6cf943` / `35a076c` / `a219857` / `c920100`, 2026-08-18

Scope: `62d8877..c920100` on `fix/worker-observability-and-claim-latency` (HEAD `c920100`) — 4 commits,
11 files, +962/−52, of which the production delta is **four files only**: `BackendHealthChecker.java`
(+the F-WOC-02 reorder), `BackendDispatcher.java` (F-WOC-01), `GatewayProperties.java` (one new startup
check), `WorkerLoop.java` (one widened catch). Everything else is tests. No `pom.xml`, no migration, no
YAML, no controller/DTO, no security config, no CI workflow touched in this round (`git diff --stat`
verified). Working tree carries only the same three pre-existing unrelated items as round 1 (modified
`docs/security/feature-diff-chunking-sast-report.md`, untracked `.claude/skills/`, untracked `.eml`).

Method: **fresh re-derivation, not re-reading my own round-1 text.** For each finding I read the shipped
code (not the commit message, not the test names), re-derived the timing/ordering argument from the
actual constants, then attacked the tests themselves — round 1's central lesson was that a green suite
had pinned F-WOC-02 as expected behavior, so a test is treated as evidence only after I have checked what
it would do against the *pre-fix* code. For F-WOC-01 I did that empirically (revert experiment below).

**Suites (run by me, this session, from the branch HEAD):** Gateway `mvn -q test` → exit 0,
`Tests=627, Failures=0, Errors=0, Skipped=0` (surefire aggregate). Worker `mvn -q -f worker/pom.xml test`
→ exit 0, `Tests=137, Failures=0, Errors=0, Skipped=0`. Delta vs. round 1 is exactly the promised new
coverage and nothing else: Gateway 618 → 627 (`BackendDispatcherTest` 7 → 12, the three new
`GatewayPropertiesRetryAndBackendHealthValidationTest` cases, `BackendDispatchFailFastEndToEndTest` +1),
Worker 135 → 137 (`WorkerLoopReportFailureCrashGuardTest` +1, `WorkerObservabilityLoggingContentTest`
5 → 6). All four new/changed test classes confirmed present in the surefire reports and passing.

**Scanners (run by me):** `semgrep --config p/java --config p/sql-injection --config p/secrets` (the
gate config from `.github/workflows/security-gate.yml`) over `src/main/java` + `worker/src/main/java` →
**0 findings**, exit 0. `gitleaks detect` over the full history (100 commits, 2.56 MB) → **no leaks
found**. No dependency delta to analyse (no `pom.xml` change in this round either).

## Verdict summary

| # | Round-1 severity | Round-2 verdict |
|---|---|---|
| **F-WOC-01** | High (blocking) | ✅ **VERIFIED-FIXED** — and proven load-bearing by a revert experiment |
| **F-WOC-02** | Medium (blocking) | ✅ **VERIFIED-FIXED** — including both defective tests |
| **F-WOC-03** | Low (should-fix) | ✅ **VERIFIED-FIXED** |
| **F-WOC-04** | Low (should-fix) | ✅ **VERIFIED-FIXED** |
| F-WOC-05 … F-WOC-13 | Info/Low | Untouched, still open, still non-blocking (spot-checked, see below) |
| **F-WOC-14** (new) | **Low** | README/`DEPLOYMENT.md` now describe the *old* claim-eligibility rule and omit the new startup check — docs-only, non-blocking |
| **F-WOC-15** (new) | Info | Residual: ≤ one probe interval of declined claims after a busy backend's job ends — accepted trade-off, no worse than pre-branch |
| **F-WOC-16** (new) | Info | The WOR-11 stall WARN can now fire for a busy-but-alive backend (intended, but ops-noise worth wording for) |
| **F-WOC-17** (new) | Info | The new startup check's detection-latency term models a *single* probe, not a whole multi-backend pass |

No Critical/High/Medium remains open. Nothing in the four new observations blocks the merge.

---

## Per-finding verdicts

### F-WOC-01 (High) — **VERIFIED-FIXED**

**The shipped change** (`BackendDispatcher.java:93-110`) is remedy option (b) from my round-1 write-up,
implemented as specified: `resolveClaimableBackend` now declines on `probeFailedSince != null` outright,
with no `failure-grace` comparison anywhere in the class (`isPastGraceFailureStreak` is gone, grep-
verified: `getProbeFailedSince()` outside `Backend`/`BackendHealthChecker` now appears only in the
dispatcher's new predicate and in `StatisticsService:61`). Status handling is untouched — `applyFailure`
still only flips `ACTIVE → SUSPECT` after a continuous `failure-grace` streak, so the fail-slow/
fail-fast decoupling is real and not a disguised re-introduction of single-probe demotion.

**I re-derived the timing argument from the constants, not from the commit message.** Detection latency
(the quantity my round-1 formula omitted) is bounded above by `scheduler.backend-health-interval` (60s)
+ `backend.read-timeout` (10s) = **70s**: `probe_failed_since` is now written by the *first* failed probe
on every path (F-WOC-02), including while the backend is at capacity mid-job, so no phase alignment can
push the record later than the next scheduled pass plus that pass's own probe timeout. The attempt clock
is unchanged: the failure-driving claim of the *final* attempt lands at `T + requeue-delay ×
(max-attempts − 1)` = `T + 180s`, and the *first* retry claim at `T + 90s`. Both are ≥ 70s, so both are
declined; and because `attempts` is incremented in exactly one place (`QueueManager.claimJobRow:200`,
reached only *after* `resolveClaimableBackend` returns non-empty), a declined claim burns nothing. The
job parks in `QUEUED` — the pre-branch behavior, WOC-44 — instead of the Review going permanently
`FAILED` and `ChunkCoordinator` cascading `CANCELLED` to its siblings. **WOT-01 is closed.**

**The new end-to-end test is genuine and load-bearing — I proved it rather than assumed it.**
`BackendDispatchFailFastEndToEndTest` uses the real Spring-managed `QueueManager` +
`BackendHealthChecker` beans against a real (Zonky) PostgreSQL with only `BackendProber` (the HTTP
boundary) mocked, real wall-clock `Instant.now()`, a real `probeAll()` pass, a genuinely claimable
`QUEUED` job (Review + `review_chunks` row), a positive control before the probe, an `attempts == 0`
assertion after the decline, and a recovery leg proving the job becomes claimable again once a
successful probe clears the streak. That is exactly the test WOR-01 called for and round 1 found missing.
To confirm it is not tautological I temporarily restored the pre-fix predicate in the working tree
(`probeFailedSince != null && elapsed >= failure-grace && !exempt`) and ran only that class:

```
[ERROR] Tests run: 1, Failures: 1 -- BackendDispatchFailFastEndToEndTest
  aSingleFailedFirstProbeImmediatelyMakesTheBackendUnclaimable:135
  [F-WOC-01: a backend that has failed at least one probe must not receive new claims, even though its
   status is still ACTIVE and failure-grace has not elapsed ...]
```

Restored with `git checkout -- src/main/java/com/review/gateway/service/BackendDispatcher.java`;
`git status --short` re-verified afterwards (only the three pre-existing unrelated items). **No
production code was changed by me.**

**Does it re-introduce the bug the branch exists to fix? No — but not for the reason the commit message
gives.** The commit claims the WOC-13 at-capacity exemption is what protects a backend that is merely
busy mid-generation. Reading the code, that exemption is a genuine **no-op for claim outcomes**: when
`atCapacity` is true the method falls through to `if (atCapacity) return empty` regardless (the commit
message concedes this in its parenthetical, and `BackendDispatcherTest`
`atCapacityWithFreshHeartbeatExemptionIsConsultedButStillDeclinedByTheCapacityCheck` asserts exactly
that). What actually protects the busy case is (i) status no longer flaps — `ACTIVE → SUSPECT` still
needs the full grace streak, so there is no minutes-long `SUSPECT` lockout, which was symptom 2 of the
original incident; and (ii) the decline self-clears on the *first* successful probe once the job ends
(`applySuccess` nulls the streak, WOC-12 recover-fast, unchanged), bounding the post-job exclusion at one
probe interval. That is strictly no worse than pre-branch `master`, where the same missed probe demoted
the backend to `SUSPECT` on the spot and recovery also took until the next successful probe. Filed as
**F-WOC-15** (Info) because it does narrow WOC-13's dispatch benefit to the in-flight window, which is
worth knowing operationally — but it does not reopen the incident this branch was cut for.

**The added startup check** (`GatewayProperties.java:216-232`) states the property correctly this time:
`requeue-delay × (max-attempts − 1) ≥ backend-health-interval + backend.read-timeout`, i.e. the last
attempt cannot be claimed before the earliest possible decline. It sits *after* the paired
`requeue-delay=0`/`failure-grace=0` escape hatch's early `return` (`:167-181`), so WOR-16's documented
revert-to-pre-V4 path still boots (confirmed in the test log: the WARN fires, no exception). It is
strictly additive and can only reject configs where `failure-grace ∈ [backend-health-interval,
backend-health-interval + read-timeout)`, since the pre-existing checks already force
`budget ≥ failure-grace ≥ backend-health-interval` — so it is not a startup-compat trap for existing
tunings. Shipped defaults pass with 110s of margin. The `>=` boundary is a benign tie (detection latency
is an upper bound, not a schedule), and the multi-backend caveat is recorded as F-WOC-17.

### F-WOC-02 (Medium) — **VERIFIED-FIXED**

The reorder is real, not cosmetic. `BackendHealthChecker.applyFailure:171-195` now sets
`probeFailedSince` **before** `shouldDeferDemotion` is consulted **and persists it on the deferral path**
(`backendRepository.save(backend)` immediately before the early `return false` — that save was the half
of the fix that could have been forgotten, and it is present). The three consequences I traced in round 1
are therefore all reversed:

- **WOR-10** — the dispatcher now sees a non-null streak for a backend that died mid-job on a capacity-1
  1:1-paired host, so the WOT-07 window (claimable the instant the slot frees, while known-dead) is shut.
- **WOR-11** — `hasEligibleActiveBackend()` no longer counts that backend as eligible once its streak
  passes grace, so the stall WARN can fire (see F-WOC-16 for the flip side).
- **WOR-13** — `shouldDeferDemotion`'s cap is now keyed on a value that actually exists, so the deferral
  is genuinely bounded. Side effect worth noting as an improvement, not a defect: with the streak always
  recorded, `defer-demotion-max: 0` now means "never defer" rather than "defer forever", which is the
  sane reading of a cap.

No off-by-one introduced: on the very first failure `Duration.between(now, now) = 0` is compared
`>= defer-demotion-max` (45m) → false → the deferral still applies on the first pass, as intended
(verified by the rewritten `deferralCapIsEnforced`, whose first pass asserts `flips == 0` and status
`ACTIVE`). Repeat failures mid-streak leave the field untouched, so the extra `save` is a Hibernate no-op
and does not rewrite the row every 60s. The `WOC-21` guard in `applyProbeResult` (re-read by id, skip
`MAINTENANCE`/`OFFLINE`) still wraps everything, unchanged.

**Both defective tests are corrected.** `atCapacityWithFreshHeartbeatDefersDemotionAndPreservesStreak`
→ renamed `...ButStillRecordsTheStreak` and now asserts `getProbeFailedSince()).isNotNull()` *and*
status still `ACTIVE` — the assertion that had encoded the defect is inverted, not deleted. The
misleading javadoc/comments that claimed "preserved" behavior the code did not have are rewritten in
both `BackendHealthChecker`'s class javadoc and at the deferral site. `deferralCapIsEnforced` no longer
hand-constructs a post-state the deferral path could not produce: it now runs a **real** first pass
through `probeAll() → applyFailure → shouldDeferDemotion` (asserting `flips == 0` and that the streak was
recorded by that path), then backdates only the elapsed time — the same simulate-elapsed-time technique
T-2.2/T-2.6 already use — and asserts the second real pass demotes despite the backend still being at
capacity with a fresh heartbeat. That is the real code path being exercised, as required.

### F-WOC-03 (Low) — **VERIFIED-FIXED**

`WorkerLoop.reportFailureBestEffort:302-310` now catches `Throwable` — broader than the
`RuntimeException` I asked for, and deliberately so: it matches the existing WSR-15 precedent verbatim
(`HeartbeatScheduler.java:99`, whose comment says the catch "must never be narrowed"). Metrics/logging
behavior is unchanged (`incrementGatewayErrors()` + one WARN carrying `jobId` and the throwable).

I checked the two things a `catch (Throwable)` can break here and neither applies: (a) shutdown is
flag-based — `requestShutdown()` only sets `shuttingDown`, and `runLoop`'s `while` re-tests it at the top
of every iteration, so swallowing an interrupt-derived exception cannot wedge shutdown
(`abandonCurrentJob()`'s interrupt still reaches the abort signal and the loop still exits via the flag);
(b) no new disclosure — the API key is a default header on the `RestClient`, never in a URI, and the URI
template takes only a `long jobId`, so the widened catch cannot log a credential; a
`RestClientResponseException`'s body excerpt was already reaching this WARN pre-fix via the
`GatewayUnavailableException` cause chain, and the Gateway's `/fail` responses carry no rejected input
(SR-17). `Error`s are now swallowed at this one site too, which is consistent with WSR-15 and acceptable
for a call the design defines as best-effort.

`WorkerLoopReportFailureCrashGuardTest` is a real regression guard: it drives a genuine
`LlamaException` into `processJob`'s handler, makes `reportFailure` throw an `IllegalStateException`
(unmapped — the old narrow catch would never have caught it), then asserts via
`verify(..., timeout(3000).atLeast(2)).claim(...)` that the loop went on to claim again, that
`isRunning()` is still true, and that the failure was counted (`incrementGatewayErrors` ×1,
`incrementFailuresReported` ×0). Against the pre-fix catch the thread would have exited after the first
claim and that verification could not pass.

### F-WOC-04 (Low) — **VERIFIED-FIXED**

`WorkerObservabilityLoggingContentTest#startingInferenceLogNeverRendersRawDiffOrSystemMessageContent`
exercises the real WOC-03 call site: a real `WorkerLoop` claims a payload whose `diff` **and**
`systemMessages` both contain `SECTIONMARKER`, and the test first asserts the `"Starting inference"` line
actually fired (a deliberate guard against a trivially-passing test — it polls until the line appears and
fails if it never does), then asserts the line is INFO, carries `jobId`/`reviewId`/`diffChars=<length>`/
`systemMessages=2`/`model`/`maxTokens`, and finally that **no** captured event at level ≤ INFO contains
the marker anywhere. It would fail immediately if anyone "improved" the line to log the list or the diff.
Scope note: the appender is attached to the `WorkerLoop` logger, which is where all four new INFO sites
of this feature that could carry content live; the test's own comment says so.

---

## Fresh-eyes pass over the fix commits themselves

Things a rushed fix in this exact code could have broken, each checked directly:

- **Lock discipline / hot path.** `resolveClaimableBackend` has exactly one call site
  (`QueueManager.claimJobRow:189`) and it runs **before** `findNextQueuedJobIdForUpdate()` takes the
  `FOR UPDATE SKIP LOCKED` row lock — so the added `existsFreshRunningJobForBackend` query can never
  lengthen a row-lock hold. It is also short-circuited twice over (`probeFailedSince != null &&
  atCapacity`), i.e. it never runs on the common path; `BackendDispatcherTest` pins that with two
  `verify(..., never())` assertions. `countRunningJobsForBackend` merely moved earlier in the same
  method. No new repository method, no new query, no dynamic SQL — both queries are the pre-existing
  static native ones with `@Param` binding. Nothing became `@Transactional`, so the round-1
  transactional-AOP trap (`BackendDispatcherClaimDeclineTransactionBugTest`) stays closed.
- **Race conditions.** The dispatcher reads `probe_failed_since` without a lock inside claim's
  `REQUIRES_NEW` transaction while the checker writes it in its own short phase-C transaction; under
  READ COMMITTED the worst case is one claim decided against a value that is one probe pass stale, in
  the conservative direction on the write side (streak set earlier) and self-correcting on the clear side
  (the next poll, ≤3s later, sees the cleared value). No lock ordering changed, no new lock taken.
- **Boundary conditions.** Checked all four: `probeFailedSince == null` → claimable
  (`aBackendThatHasNeverFailedAProbeStaysClaimable`); streak of 0s → declined; `atCapacity` uses the
  unchanged `running >= capacity`; the deferral cap comparison is `>=` on a `Duration.ZERO` first
  failure. No off-by-one.
- **`GET /backends` semantics.** Unchanged shape — `StatisticsService:61` is the only other reader of
  the field and `BackendSnapshot`/`BackendView` were not touched. `probeFailedSince` is simply non-null
  more often now, which is the honest record and is ADMIN-only; the URL is still withheld. If anything
  the README's existing description of that field (round-1 text, `README.md:644-646`) became *correct*
  as a result of F-WOC-02, having been wrong before.
- **The WOC-13 exemption in other cases.** Enumerated: `atCapacity=false` → exemption cannot apply
  (correct: a free backend that failed a probe must be declined, that is the fix); `atCapacity=true` →
  declined by the capacity check anyway; `MAINTENANCE`/`OFFLINE`/`SUSPECT` → rejected earlier by the
  status check, before either query. There is no input under which the exemption makes a
  probe-failing backend claimable.
- **Contracts and surfaces.** No change to any `/jobs/*` or `/reviews` request/response, no new field, no
  status-code change, no `EventType`, no migration, no `application.yml`, no security config, no CI
  workflow. Round-1's verified-good properties (WOR-03…WOR-09, WOR-12, WOR-14…WOR-20, the
  `RequestBodySizeLimitFilter` percent-encoding fix, the V4 migration) are untouched by these commits and
  their tests still pass in the 627/137 runs above.
- **Round-1 Info findings spot-checked as genuinely untouched** (i.e. no silent partial fix to
  re-verify): `V4__...sql:10` is still session-scoped `SET lock_timeout` (F-WOC-06);
  `GatewayPropertiesApplicationYamlBindingTest`'s stale javadoc is unchanged (F-WOC-05); `DEPLOYMENT.md`
  §8b still omits the SR-06 residual sentence (F-WOC-08).

### F-WOC-14 (Low, **NEW**) — `README.md`/`DEPLOYMENT.md` describe the pre-fix claim rule

The behavior change shipped without its documentation, in a repo where `README.md` is by CLAUDE.md's own
rule the authority on current behavior (this is the F-DC-04/F-PM-02 lineage of finding):

- `README.md:942` — "the claim path independently declines any backend whose `probe_failed_since` streak
  is **already past `failure-grace`**, regardless of its persisted `status`" — that is now the *old*
  rule. The claim path declines on the first failed probe.
- `README.md:161` and `DEPLOYMENT.md:739-743` document only the `requeue-delay × (max-attempts − 1) ≥
  failure-grace` startup check. The second, now load-bearing check (`≥ backend-health-interval +
  backend.read-timeout`) is undocumented, so an operator who raises `gateway.backend.read-timeout` past
  ~110s with shipped defaults hits an unexplained boot refusal. The exception message is explicit and the
  failure is fail-fast at startup rather than at runtime, which is why this is Low and not Medium.

Remediation: three short doc edits (the two `README` passages + the `DEPLOYMENT.md` §8b arithmetic
bullet). **Does not block the merge** — nothing behaves incorrectly — but it should land as a docs-only
commit on this branch or immediately after, or `README` stops being the authority CLAUDE.md says it is.
(`CLAUDE.md:109` carries the same now-stale "excluded from new assignments [at `SUSPECT`]" phrasing; that
file is the user's, so I am flagging it rather than proposing an edit.)

### F-WOC-15 (Info, **NEW**) — residual: ≤ one probe interval of declined claims after a busy backend frees up

Consequence of F-WOC-01 + F-WOC-02 together, in the deployment this branch was written for: if
`llama-server` does not answer `/health` while generating, the streak is now recorded during the
deferral, so when the job ends the backend stays undispatchable until its next *successful* probe — up to
`backend-health-interval` + one probe (≈70s), typically ~30s. Pre-branch `master` behaved the same way
(single-probe `SUSPECT`, recovery on next success), so this is not a regression against the merge target;
it is a regression against the pre-fix state of this branch, where the streak was never recorded at all
(that was the F-WOC-02 defect). The branch's stated symptom-2 goal — "blocking new claims for *minutes*"
— is still met: bounded, self-healing, and with no status flapping. Optional future mitigation, out of
scope here: probe a backend immediately when a claim is declined on a non-null streak, or on job
completion, instead of waiting for the next scheduled tick. Operators for whom this matters can lower
`gateway.scheduler.backend-health-interval` (`failure-grace ≥ backend-health-interval` still holds).

### F-WOC-16 (Info, **NEW**) — the WOR-11 stall WARN can now fire for a backend that is alive but busy

Also a direct consequence of F-WOC-02 recording the streak during a deferral, and formally what WOR-11
asked for. On a single-backend, multi-chunk review where `/health` is unresponsive during a >180s
generation, `hasEligibleActiveBackend()` returns false while sibling chunks sit `QUEUED`, so
`"Queue stalled: N job(s) QUEUED but 0 eligible ACTIVE backend(s) (suspect=0, maintenance=0, offline=0)"`
is logged once per pass for the duration. The statement is true (those jobs genuinely cannot be
dispatched) but an operator will read it as an outage. Suggested one-line improvement, non-blocking: add
the at-capacity count to the message so "busy" and "dead" are distinguishable at a glance.

### F-WOC-17 (Info, **NEW**) — the new startup check models one probe, not a whole pass

`dispatchDetectionLatency = backend-health-interval + backend.read-timeout` assumes the target backend is
probed at the start of its pass. Phase B is sequential over all `ACTIVE`/`SUSPECT` candidates, so with
several simultaneously-timing-out backends ahead of it (plus a WOC-17-skipped tick) real detection
latency can reach `interval + (N−1)×(connect+read) + read`. At the documented scale ceiling (10 backends,
all dead) that is ≈187s against the 180s default budget window — a marginal, self-limiting case (if every
backend is dead the Review cannot succeed anyway; the 110s default margin covers ~8 timing-out peers).
Recording it so the formula is not mistaken for a tight bound: it is a sufficient condition for the
single/few-backend deployments in scope, not a proof for arbitrary N.

---

## WOR delta vs. round 1

| Req | Round 1 | Round 2 |
|---|---|---|
| **WOR-01** | ⚠️ PARTIAL (F-WOC-01) | ✅ **Met** — property re-derived against shipped constants, end-to-end test present and proven load-bearing |
| **WOR-10** | ⚠️ inoperative (F-WOC-02) | ✅ **Met** — and now strictly stronger than specified (any streak, not only past-grace) |
| **WOR-11** | ⚠️ inoperative (F-WOC-02) | ✅ **Met** — streak recorded on the deferral path, WARN eligibility engages (see F-WOC-16) |
| **WOR-13** | ⚠️ inoperative (F-WOC-02) | ✅ **Met** — cap keyed on a value that now exists, exercised through the real deferral path |
| **WOR-17** | ✅ code / ⚠️ test gap | ✅ **Met** — marker regression test added at the WOC-03 call site |
| All other WOR-* | ✅ | ✅ unchanged (not touched by these commits; suites re-run green) |

18/18 blocking MUSTs now verified closed in shipped code.

## Findings open after this round

| # | Severity | Status |
|---|---|---|
| F-WOC-14 | Low | Open — docs-only, should ride on this branch or immediately after merge |
| F-WOC-05 … F-WOC-13, F-WOC-15, F-WOC-16, F-WOC-17 | Info (F-WOC-13 pre-existing/structural) | Open, accepted, none blocking |

---

## FINAL VERDICT: **PASS — APPROVED FOR MERGE** (`fix/worker-observability-and-claim-latency` → `master`)

Both blocking findings are genuinely fixed in shipped code, not papered over:

- **F-WOC-01** is fixed by the remedy I preferred in round 1, the timing property now holds when
  re-derived from the actual constants (70s worst-case detection vs. a 90s first retry and a 180s final
  attempt), the fix is guarded by a new end-to-end test that I confirmed *fails* against the pre-fix
  predicate, and it does not reopen the flapping/lockout symptom the branch was cut for.
- **F-WOC-02** is fixed by the exact reorder specified, *including* persisting on the deferral path, and
  both tests that had encoded the defect (one asserting `null`, one seeding an impossible state) are
  corrected to exercise the real path.
- **F-WOC-03** and **F-WOC-04** are closed with real regression tests, the former matching the WSR-15
  catch-all precedent already in the Worker.

The fix commits introduce no new injection, access-control, disclosure, lock-ordering, resource or
contract surface: four small production edits, no query/migration/dependency/config/CI change, semgrep 0
findings, gitleaks clean, both suites green at 627/137 with the delta accounted for test-by-test. The
four new observations are one Low docs-drift item and three Info-grade residuals, all recorded above;
none of them warrants another pipeline iteration.

Recommend merging to `master`, with the F-WOC-14 doc edits as the only follow-up worth doing promptly.
