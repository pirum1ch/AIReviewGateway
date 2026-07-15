# LLM Worker — SAST / security-audit report

**Stage:** appsec / SAST · **Feature:** part 2 (Worker) · **Branch:** `feature/02-worker-loop-lifecycle`
**HEAD:** `87254a8` · **Scope:** everything under `worker/` (commits `34c2b7d`, `730aeac`, `87254a8`).
**Baseline:** this project's own threat model `docs/worker-threat-model.md` (WSR-01..WSR-18) and the
Gateway threat model it extends. **Suite:** 87/87 green (`cd worker && mvn -q test`).

Tooling run locally (no Docker):
- `semgrep 1.169.0` — `p/java`, `p/sql-injection`, `p/secrets` over `worker/` → **0 findings, 0 errors**.
- `osv-scanner` over a CycloneDX SBOM (`cyclonedx-maven-plugin` aggregate BOM, 53 packages) → 2 advisories (1 Medium, 1 Low).
- Manual code review of every WSR-MUST call site + concurrency/race review of the loop/heartbeat/abort/lifecycle path.

---

## Verdict: **PASS-with-notes** — 2 must-fix-before-merge, no Critical/High, no injection/exfil defect

The Worker's handling of untrusted Gateway/llama data is implemented carefully and, in several places
(BoundedInputStream mid-stream abort, AbortSignal attach/abort synchronization, HeartbeatScheduler
`catch (Throwable)` fail-safe, promptVersion allowlist-before-resolution), exactly to the letter of the
threat model. Both must-fix items are **control-effectiveness gaps**, not exploitable code vulnerabilities:
a documented loopback binding that is silently ineffective (FW-01) and a CI gate that does not actually
cover the new module (FW-02). Neither ships a data-exfil or code-execution path.

### Must-fix before merge
- **FW-01** — actuator endpoints bind to all interfaces despite the `127.0.0.1` config (WSR-12 FAIL).
- **FW-02** — the CI security gate (SCA + build/test) skips the standalone `worker/` module (WSR-17 FAIL).

---

## Findings

### FW-01 — Actuator binds to all interfaces; `management.server.address` is silently ineffective (Medium, MUST-FIX)
- **STRIDE/CWE:** Information Disclosure / CWE-16, CWE-668 · maps to **WT-11 / WSR-12**.
- **Where:** `worker/src/main/resources/application.yml:9-21`; false-assurance guard at
  `worker/src/main/java/com/review/worker/config/WorkerProperties.java:212-224`.
- **Issue:** `management.server.address: 127.0.0.1` is set, but **`management.server.port` is not**, so the
  actuator runs on the main server port (`server.port: 8081`). Per Spring Boot's documented behavior,
  *"You can listen on a different address only when the port differs from the main server port"* — when the
  management port equals the main port, `management.server.address` **has no effect**, and the server binds
  to `server.address`, which is **unset ⇒ all interfaces (`0.0.0.0`)**. There is no `server.address` in the
  config (confirmed: only `management.server.address` is present). Result: `GET /actuator/prometheus` and
  `/actuator/health` are reachable from **any host that can reach the Mac mini on 8081**, not just loopback.
- **Why the startup guard doesn't catch it:** `WorkerProperties.validateManagementServerAddress()` validates
  that the *string value* `"127.0.0.1"` is a loopback literal — which it is — but the property it validates
  is inert at runtime. The guard therefore prints a green light on a control that does nothing (a
  false-assurance anti-pattern: worse than no check, because it looks covered in review and tests).
- **Exploit scenario:** the Mac minis sit on an office LAN / VPN segment (threat model WTB-HOST/WT-11). Any
  peer on that segment scrapes `worker_*` metrics (job volume, llama durations, uptime → activity/topology
  fingerprinting) and health status. Exposure is bounded to metrics+health because `exposure.include` is
  correctly limited to `health,prometheus` (no `env`/`heapdump`/`threaddump`/`beans` — so **no token or heap
  leak**), which is why this is Medium and not High. But WSR-12 is a MUST and is not met at runtime.
- **Fix (pick one, first is simplest):**
  1. Add `server.address: 127.0.0.1` — the Worker is a pure HTTP *client* and serves nothing to the network
     except actuator, so binding the whole server to loopback is correct and closes this fully; **and**
  2. tighten the guard so it reflects reality: fail startup unless *either* `server.address` is loopback
     *or* (`management.server.port` is set to a distinct port **and** `management.server.address` is loopback).
     Validating an inert property must not pass.
- **Verify:** after the fix, `curl http://<lan-ip>:8081/actuator/health` from another host is refused;
  `curl http://127.0.0.1:8081/actuator/prometheus` still works.

### FW-02 — CI security gate does not cover the standalone `worker/` module (Medium, MUST-FIX)
- **CWE:** CWE-1104 (unmaintained/unscanned third-party components) · maps to **WT-18 / WSR-17**.
- **Where:** `.github/workflows/security-gate.yml` — `sca` job (line 58) and `build-test` job (line 115);
  root `pom.xml` (does **not** declare `worker` as a `<module>` — it is a standalone project by design).
- **Issue:** two of the four gate jobs operate on the **root Gateway pom only**:
  - `sca`: `mvn ... makeAggregateBom` at repo root produces an SBOM for the Gateway aggregate; `worker/`'s
    dependency tree (`micrometer-registry-prometheus`, `snakeyaml`, `mockwebserver`, and the Boot 3.5.16
    transitive set) is **not in it**, so the "block High/Critical CVE" gate **never sees a worker dependency**.
    FW-03/FW-04 below exist in `worker/` and were caught only by a *manual* osv-scan, not by CI.
  - `build-test`: `mvn -B -ntp verify` at root does not build/test `worker/` (not a module) → the **87 worker
    tests never run in CI**; a regression in a WSR-MUST behavior would be green in the pipeline.
  - `gitleaks` and `semgrep` scan `.` (whole tree) and **do** cover `worker/` source — those two are fine.
- **Exploit scenario:** a future worker dependency bump introduces a High/Critical CVE (or a PR breaks the
  BoundedInputStream / abort / allowlist logic); the pipeline stays green because neither the SCA nor the
  test job looks at `worker/`. The threat model's WSR-17 ("the Worker needs its **own** SCA/secret/SAST
  gate") is explicitly not satisfied.
- **Fix:** add `worker/` coverage to the gate — either a matrix/`working-directory` over both poms, or a
  dedicated worker job that runs `cyclonedx makeAggregateBom` + `osv-scanner` on `worker/target/bom.json`
  and `mvn -f worker/pom.xml verify`. Keep the same High/Critical block policy.
- **Verify:** the gate's SBOM contains `com.review:llm-worker` deps and the 87 worker tests appear in CI logs.

### FW-03 — `jackson-databind` 2.21.4: CVE-2026-54515 `@JsonIgnoreProperties` bypass (Medium, non-blocking)
- **Advisory:** GHSA-5jmj-h7xm-6q6v / CVE-2026-54515 · CVSS **5.3** (`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N`).
- **Where:** transitive via `spring-boot-starter-parent:3.5.16` (same version pinned as the Gateway).
  Fixed in **2.21.5**.
- **Impact in *this* code:** case-insensitive deserialization can restore properties filtered by a
  per-property `@JsonIgnoreProperties`, enabling mass-assignment on protected fields. The Worker's inbound
  DTOs (`ClaimResponse`/`JobPayload`/`HeartbeatResponse`/`ResultResponse`/`ChatCompletionResponse`) are
  plain records and **do not rely on `@JsonIgnoreProperties` for any security control**, so real
  exploitability here is effectively nil. Below the gate's High/Critical block threshold.
- **Recommendation:** override `jackson-databind` to `2.21.5` in `worker/pom.xml` `<dependencyManagement>`
  (and mirror on the Gateway for a consistent baseline), or accept as tracked-Medium matching the Gateway's
  documented CVE posture. Non-blocking.

### FW-04 — `logback-core` 1.5.34: GHSA-jhq6-gfmj-v8fx (Low, non-blocking)
- **Advisory:** GHSA-jhq6-gfmj-v8fx · CVSS **2.9**. Fixed in **1.5.35**. Transitive via Boot 3.5.16.
- **Impact:** requires attacker control over logback configuration, which is packaged in the fat JAR and not
  attacker-writable on a hardened host (WSR-16). Negligible in this deployment. Bump on the next Boot patch.

### FW-05 — DTO records use default `toString()` (diff/rawResponse) — latent, no active leak (Low, defense-in-depth)
- **CWE:** CWE-532 · maps to **WSR-10 / WT-09**.
- **Where:** `gateway/dto/JobPayload.java` (`diff`), `gateway/dto/ResultRequest.java` (`rawResponse`),
  `ClaimResponse.java`.
- **Issue:** these are Java `record`s, so their auto-generated `toString()` renders **all fields including
  the full diff / raw model response**. A future `log.debug("... {}", job)` or `log.warn("...", request)`
  would silently dump proprietary source into logs.
- **Current status — no active leak:** every log site was reviewed (grep of all `log.*` statements): the code
  logs only `jobId`/`reviewId`/`status`/byte-sizes/token-counts, never a whole DTO. `getApiKey()` is used
  only to build the `Authorization` header (`HttpClientConfig`), never logged. `RestClient` server-error
  exceptions carry the *response* body, not the *request* body, so the diff/rawResponse cannot surface via an
  exception message either. **WSR-10 passes today**; this is hardening only.
- **Recommendation:** give `JobPayload` and `ResultRequest` a masking `toString()` (e.g. print
  `diffBytes=…`/`rawResponseBytes=…` instead of content) so the invariant survives future edits. Optionally a
  Semgrep rule forbidding these types as `{}` log arguments.

### FW-06 — QA defect WorkerLoop.java:226-227 (jobs_completed miscount): security assessment (Informational)
- Per instruction, not re-reported as a defect — **security impact only:** **none.** The miscount is a
  Micrometer counter (`worker_jobs_completed_total`) incremented after a result-redelivery is abandoned on
  interrupt. It mutates no review state, no auth decision, no data path; at worst it slightly skews an
  observability metric (analogous to the Gateway's anomaly-detection counters, SR-24). No confidentiality,
  integrity, or availability consequence. Benign from an AppSec standpoint.

---

## Per-WSR closure table

| WSR | Requirement (short) | Verdict | Evidence / note |
|-----|---------------------|:-------:|-----------------|
| WSR-01 | promptVersion allowlist **before** resolution | **PASS** | `PromptTemplateService.validatePromptVersion` runs first; regex `^[A-Za-z0-9._-]{1,64}$` + explicit `!contains("..")`. Bypass attempts all fail: `null`→rejected; empty→`{1,64}` rejects; `../../etc/passwd`, `v1/../secret`→`/` and `..` rejected; null byte / `%2e` / non-ASCII / unicode→outside class rejected; exactly-64→allowed (correct). `/` can never reach the `ClassPathResource` path concat. |
| WSR-02 | literal substitution, no expression engine | **PASS** | `substituteDiff` = single `String.replace("{{DIFF}}", diff)`; no SpEL/Freemarker/Velocity/`StringSubstitutor` on classpath (pom reviewed). A diff containing `${…}`/`#{…}`/`{{DIFF}}` is inserted verbatim and never re-parsed. |
| WSR-03 | diff size cap on claim → abandon | **PASS** | `validateDiffSize` (UTF-8 byte length vs `worker.limits.max-diff-bytes`, default 262 144) throws `AbandonJobException` before prompt assembly. |
| WSR-04 | bounded llama read, no unbounded buffering | **PASS** | `BoundedInputStream` throws at `maxBytes+1` **mid-stream** (both `read()` overloads), independent of `Content-Length`/chunked encoding; off-by-one correct (`>` not `>=`). Wired in `LlamaClient.readBounded` for both sync and async parse paths. |
| WSR-05 | oversize → abandon, never truncate-and-submit | **PASS** | Oversize → `ResponseTooLargeException` → `LlamaException` → `WorkerLoop.processJob` catch → `jobs_failed_total++`, **no** `/result` call. No `truncate`/content `substring` anywhere in the submit path (the one `substring` is trailing-slash trimming on the llama URL). |
| WSR-07 | templates classpath-only, no `file://` escape | **PASS** | `validatePromptLocation` fails startup unless `prompt.location` starts with `classpath:`; `loadTemplate` uses only `ClassPathResource`. No filesystem template code path. |
| WSR-09 | real TLS validation; insecure flag loopback-only | **PASS** | `HttpClient.newBuilder()` uses the default system SSLContext (full cert+hostname validation); **no** custom `TrustManager`/`SSLContext`/hostname-verifier anywhere (grep clean). `allow-insecure-gateway` honored only for a loopback `gateway.url` host, else startup fails regardless of the flag. |
| WSR-10 | token/diff/response never logged | **PASS** (note FW-05) | All log sites emit ids/sizes/status only; token used only for the bearer header. Latent record-`toString()` risk hardened in FW-05, no active leak. |
| WSR-11 | `https://` enforced (loopback exception) | **PASS** | `validateGatewayUrl`: non-`https` fails unless loopback host + explicit flag. |
| WSR-12 | management loopback fail-fast | **FAIL** | **FW-01** — `management.server.address` inert without a distinct `management.server.port`; actuator binds all interfaces; guard gives false assurance. |
| WSR-14 | capped backoff, never tight-spin/exit | **PASS** | `WorkerLoop.nextBackoff` doubles from `pollIntervalMs`, capped at `MAX_BACKOFF_MS=60_000`; claim errors and result-redelivery both back off; loop never exits on Gateway/llama failure. |
| WSR-15 | heartbeat can't die silently; fail-safe abort | **PASS** | `HeartbeatScheduler.tick` wraps the whole body in `catch (Throwable)` (correctly *not* narrowed) so a throwing tick cannot cancel `scheduleAtFixedRate`; `MAX_CONSECUTIVE_FAILURES=3` triggers `abortSignal.abort()` fail-safe. `AbortSignal.attach`/`abort` both `synchronized` → attach-after-abort cancels immediately, double-abort idempotent, no attach/abort race window. No zombie path. |
| WSR-17 | Worker has its own SCA/secret/SAST gate | **FAIL** | **FW-02** — gitleaks+semgrep cover `worker/` (scan `.`), but SCA-SBOM and `mvn verify` run on the root pom only; worker deps ungated, worker tests not run in CI. |
| WSR-06 (SHOULD) | pin llama URL loopback | PASS | Non-loopback llama URL warns unless `allow-non-loopback`; default `127.0.0.1:8000`. |
| WSR-08 (SHOULD) | template governance / read-only install | N/A (deployment) | Runbook/packaging concern (branch 3); not a code artifact. |
| WSR-13 (SHOULD) | health show-details limited | PASS (default) | Not overridden ⇒ Spring default `show-details: never`. |
| WSR-16 (SHOULD) | non-root, no heap dump, file perms | PARTIAL (deployment) | Best-effort WARN if `-XX:+HeapDumpOnOutOfMemoryError` is set (`WorkerProperties.warnIfHeapDumpOnOutOfMemoryEnabled`) + documented launch flag in `application.yml`; run-as-user/file-perms remain a packaging/runbook item. |
| WSR-18 (SHOULD) | strip CR/LF from logged promptVersion | PASS | Rejected `promptVersion` is logged as `length=` only, never the raw value. |

### Concurrency / lifecycle review (no findings)
- **Single-thread invariant:** metrics are mutated only by the `worker-loop` thread; the `worker-heartbeat`
  thread touches only `gatewayClient.heartbeat` + `abortSignal.abort()` + logging — **no metrics race**.
- **AbortSignal:** `attach()`/`abort()` synchronized on the same monitor; `aborted` volatile; abort-before-attach,
  double-abort, and concurrent attach/abort are all safe (reviewed against the threat-model race checklist).
- **Shutdown ordering:** `WorkerRunner` (ApplicationRunner) starts the loop after refresh; `GracefulShutdown`
  (SmartLifecycle) `requestShutdown()` (cooperative, no interrupt) → bounded await → `abandonCurrentJob()`
  (interrupt) only after grace elapses. No unbounded block; abandoned jobs are reclaimed Gateway-side. Sound.

---

## Dependency scan summary (osv-scanner, 53 packages)
| Advisory | Package | Version | CVSS | Fixed | Gate | Finding |
|----------|---------|---------|:----:|-------|------|---------|
| GHSA-5jmj-h7xm-6q6v (CVE-2026-54515) | jackson-databind | 2.21.4 | 5.3 (Med) | 2.21.5 | below High/Crit block | FW-03 |
| GHSA-jhq6-gfmj-v8fx | logback-core | 1.5.34 | 2.9 (Low) | 1.5.35 | below block | FW-04 |

Both are transitive from `spring-boot-starter-parent:3.5.16` (identical to the Gateway baseline — inherited,
not introduced by the Worker's own new deps). The genuinely new Worker deps —
`micrometer-registry-prometheus`, `snakeyaml`, `mockwebserver` (test) — showed **no** known vulnerabilities.

---

## Must-fix-before-merge list
1. **FW-01 (WSR-12):** bind the actuator to loopback for real — add `server.address: 127.0.0.1` (and make the
   startup guard validate the *effective* binding, not the inert `management.server.address`).
2. **FW-02 (WSR-17):** extend `.github/workflows/security-gate.yml` to run SCA (SBOM+osv) and `mvn verify`
   against `worker/pom.xml`, with the same High/Critical block policy.

## Recommended (non-blocking)
3. FW-03: pin `jackson-databind` to `2.21.5` (or accept as tracked-Medium matching the Gateway).
4. FW-05: masking `toString()` on `JobPayload`/`ResultRequest` to keep WSR-10 regression-proof.
5. FW-04: pick up `logback-core` 1.5.35 on the next Boot patch bump.
