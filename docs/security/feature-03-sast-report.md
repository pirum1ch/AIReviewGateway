# AppSec SAST Report — feature/03-api-security (REST / token security / integrations / scheduling)

Scope: commits `b239670` (dev) + `1ff546d` (QA tests). In scope: `controller/*`, `config/*`
(`SecurityConfig`, `TokenAuthenticationFilter`, `RequestBodySizeLimitFilter`, `WebConfig`,
`RestClientConfig`, `ScheduledJobs`, `SchedulingConfig`, `GatewayProperties` validation),
`service/GitLabClientImpl`, `service/BackendProberImpl`, `service/BackendUrlValidator`, the
`ResultProcessor` F02-02 change, `dto/*`, `application.yml`. This is the final feature and owns the
HTTP/security-layer MUST controls deferred from features 01–02. Method: manual expert SAST against
SR-01..SR-24 + attempted SSRF/authz bypass construction + offline `mvn dependency:tree` CVE review.
HEAD `1ff546d`, tree clean, no production code/tests modified.

**Suite (run by me): `mvn test` → 345 tests, 341 green, 4 failing = the two intentional QA defect
demos** (`BackendDispatcherClaimDeclineTransactionBugTest` ×3 → `UnexpectedRollbackException`;
`ReviewControllerTest.getStatusWithNonNumericIdShouldReturn400NotInternalError` ×1). No unexpected
regressions.

Verdict: **PASS-with-notes.** Every HTTP-layer MUST control is implemented and verified — SR-01, SR-02,
SR-10, SR-11, SR-15, SR-16, SR-17, SR-18 — and F02-02 is genuinely closed with a pessimistic row lock.
No Critical/High new security finding. The new findings are Low/Info (an SSRF DNS-rebinding TOCTOU, a
permissive default allowlist, uncapped worker-only bodies, and the accepted SR-06/07 self-declared-
identity residual). The two failing QA tests are availability/cosmetic defects that do **not** breach
confidentiality or integrity — SR-15's generic error body holds even on those unmapped paths.

Severity counts: Critical 0 · High 0 · Medium 0 · Low 3 · Info 3.
AppSec must-fix-before-merge: **none.** Must-fix-before-production (availability, QA-owned): the CRITICAL
claim-decline 500. Deployment-config must-dos: F03-02, F03-05. One MUST process-control still open
system-wide: **SR-23** (CI security gates) — see closure summary.

---

## Findings

| # | Severity | CWE / OWASP | Where | Description | Remediation |
|---|----------|-------------|-------|-------------|-------------|
| F03-01 | Low | CWE-918, A10:2021 | `BackendUrlValidator.java:62-76` + `BackendProberImpl.java:37-43` | **SSRF DNS-rebinding TOCTOU.** `validate()` resolves the host once via `InetAddress.getByName` and checks loopback/link-local/etc., but the probe `RestClient` re-resolves the host independently at connection time. A hostname whose DNS record flips between the two lookups (public IP at validation, `127.0.0.1`/internal at connect) bypasses the range check. Redirect-disable does not help against rebinding. | Resolve once and connect to the validated literal IP (pin the address), or re-assert the range check on the socket's resolved address. Mitigating factors: the registry is admin-controlled and private ranges are intentionally allowed, so the practical blast radius is small — acceptable to defer with tracking at this scale. |
| F03-02 | Low | CWE-918 / CWE-1188, A05:2021 | `application.yml:71`, `GatewayProperties.java:360` | **SSRF allowlist arm is a no-op by default.** `gateway.backend.allowed-host-pattern` defaults to `.*` (matches any host). The always-on loopback/link-local/any-local/multicast block still applies, but the *host allowlist* half of SR-10 provides zero restriction until an operator sets a real pattern — so by default a backend URL may point at any non-loopback host, including internal private-range services. | **Deployment must-do:** set `BACKEND_ALLOWED_HOST_PATTERN` to the actual backend network in production. The permissive default is a documented deliberate choice (varied LAN topologies); flagged so it is not forgotten at deploy time. |
| F03-03 | Info | CWE-770, A04:2021 | `WebConfig.java:21` + `RequestBodySizeLimitFilter.java:61-72` | **`POST /jobs/claim` and `POST /jobs/{id}/heartbeat` bodies are not size-capped.** `limitFor()` returns a limit only for `POST /reviews` and `POST /jobs/{id}/result`; claim/heartbeat bodies (tiny DTOs) fall through with no explicit cap, and there is no Tomcat-level JSON body backstop. WORKER-authenticated + small payloads → low risk. | Accept, or add a small default cap for the other worker POSTs. No Tomcat `maxSwallowSize`/post-size backstop is configured — consider one for defense-in-depth. |
| F03-04 | Low | CWE-306 / CWE-639, A01:2021 | `JobController.java:49,62,78`; `QueueManager.isOwner` | **SR-06/SR-07 not implemented — worker identity is self-declared.** `workerId`/`backendId` come from the request body under a single shared `WORKER_TOKEN`. SR-04 ownership (MUST) **is** enforced (heartbeat/result verify `workerId == claimant`), but a worker-token holder who learns another worker's `workerId` + a sequential `jobId` can still forge a heartbeat/result (T-04 residual). No claim-lease token (SR-06), no token→backend binding (SR-07). | Threat-model **ACCEPTED-RISK / SHOULD** at this scale. As the final feature, this must be **explicitly re-accepted** (recorded below) or scheduled: per-backend tokens + a random claim-lease on `/jobs/claim` close it with no new infra. |
| F03-05 | Low | CWE-319, A02:2021 | `application.yml:8` | **`DB_URL` default has no `sslmode=require`.** Default points at loopback (accepted-risk, documented), but a production `DB_URL` to a remote Postgres without `sslmode=require` would transit DB credentials, diffs (A6) and raw responses in cleartext. | **Deployment must-do:** append `?sslmode=require` for any non-loopback DB. Document in the ops runbook (SR-15 DB hop). |
| F03-06 | Info | CWE-770, A04:2021 | (absent) | **SR-20 (per-token rate limiting) not implemented.** No in-memory token-bucket on `/reviews`/`/jobs/claim`/`heartbeat`; no queue-depth shedding. A valid-token holder can flood the single Gateway (T-16/T-23). | Threat-model **SHOULD / ACCEPTED-RISK**. Open; a single-instance in-memory limiter (no Redis) remains the recommended follow-up. |

## Assessed security impact of the two QA-known defects (not re-reported)

- **QA-CRITICAL — claim-decline → `UnexpectedRollbackException` → 500 (not 204).** `QueueManager.claim`
  (`@Transactional REQUIRES_NEW`) calls `BackendDispatcher.resolveClaimableBackend`
  (`@Transactional readOnly`) which **throws** `JobNotClaimableException` for the unknown/not-ACTIVE/
  at-capacity cases; that throw marks the joined physical transaction rollback-only, so even though
  `claim()` catches it and returns `Optional.empty()`, the commit then throws `UnexpectedRollbackException`
  → 500. **Security impact: availability only, no confidentiality/integrity breach.** The response body is
  the generic `INTERNAL_ERROR` (SR-15 holds — no stack trace/class/SQL leaked). But "backend at capacity"
  is a *normal steady-state* condition, so every worker poll under backpressure gets a 500 → log spam and
  potential worker error-loops. **Must-fix before production for availability (QA/dev-owned)** — fix by not
  throwing across the tx boundary on the decline path (return an empty/optional result, or `noRollbackFor`,
  or split the capacity check out of the writing tx). Not an AppSec merge blocker.
- **QA-MINOR — `MethodArgumentTypeMismatchException` (e.g. `GET /reviews/abc`) → 500 (not 400).** Unmapped
  in `GlobalExceptionHandler` → generic 500. **Security impact: negligible** — cosmetic status-code
  correctness only; SR-15 generic body still leaks nothing. Fix: add a handler mapping it to 400.

---

## Positive verifications (MUST controls — checked and passed)

- **SR-01 — authN before controller + startup secret validation: PASS.** `GatewayProperties.
  validateOnStartup()` (`@PostConstruct`) refuses to start if any of the four secrets is null/blank or
  `< 32` chars, and the GitLab base-url is not `https://` (error messages never echo secret values). All
  secrets are `${ENV}` placeholders with **no defaults**, so a missing env var fails placeholder
  resolution → no accidental allow-all. `SecurityConfig` ends with `anyRequest().denyAll()` + a 401
  `authenticationEntryPoint`; a missing/garbage token on any protected path → 401 (verified by
  `ReviewControllerTest.createReviewReturns401WithoutAToken` and the role-matrix tests).
- **SR-02 — constant-time token compare: PASS.** `TokenAuthenticationFilter.constantTimeEquals` SHA-256-
  hashes both sides to a fixed 32-byte digest then compares with `MessageDigest.isEqual` — no
  `String.equals`/`==`, no length leak (both sides hashed to equal length first). The sequential CI→WORKER
  →ADMIN role probe early-returns, but that only distinguishes *which role* a **valid** token has to its
  own holder; an invalid token always runs all three constant-time comparisons → no token-recovery oracle.
- **SR-10 — SSRF guard: PASS-with-notes (F03-01/F03-02).** `BackendUrlValidator` enforces scheme∈{http,
  https}, blocks `localhost` literal + loopback/link-local/any-local/multicast (covers `127.0.0.1`,
  `169.254.169.254` metadata, `::1`, `0.0.0.0`), treats unresolvable hosts as unsafe, and applies the host
  allowlist — re-validated on **every** probe, not just at write. Probe `RestClient` disables redirects
  (`Redirect.NEVER`) and uses short connect/read timeouts. **Bypass attempts that FAIL (good):** userinfo
  tricks (`http://allowed@127.0.0.1/` → `URI.getHost()` returns the real host `127.0.0.1` → blocked);
  decimal/octal IP encodings (Java `InetAddress.getByName` does not honour libc-style `2130706433`/`0177.`
  forms → resolves elsewhere or fails → not loopback); IPv6 loopback (`[::1]` → loopback or unresolvable →
  blocked); scheme smuggling (`file:`/`gopher:` rejected). `GitLabClientImpl` uses a fixed config base-url
  with `Long` path-segment templating (no host injection) and sends `PRIVATE-TOKEN` only via the
  base-url-bound client → the GitLab token never leaves the configured host. Residuals: DNS rebinding
  (F03-01) and the permissive default allowlist (F03-02).
- **SR-11 — request-body size cap: PASS-with-notes (F03-03).** `RequestBodySizeLimitFilter` is registered
  at `Ordered.HIGHEST_PRECEDENCE` **ahead of Spring Security**, so an oversized (even unauthenticated)
  `POST /reviews`/`/jobs/{id}/result` is rejected `413` on `Content-Length` before auth or Jackson reads
  the body — fail-fast at the edge. The chunked/no-Content-Length bypass is explicitly documented and
  accepted for internal CI/Worker clients. (Ordering answer to the coordinator: an unauthenticated
  oversized request is **not** processed — it is 413'd before the auth filter.)
- **SR-15 — no internal detail in errors + HTTPS: PASS.** `GlobalExceptionHandler` returns only
  `ErrorResponse(code,message)`; the `Exception.class` backstop logs server-side and returns a fixed
  `INTERNAL_ERROR` body — no stack trace, class name, SQL, or bean-validation internals. Crucially, even
  the two *unmapped* QA-defect paths hit this backstop → still no leak. GitLab base-url `https` enforced at
  startup. (DB-hop TLS = F03-05 ops note.)
- **SR-16 — strict single-role matrix: PASS.** `DELETE /reviews/**`→ADMIN, `GET /backends[/**]`→ADMIN,
  `GET /metrics[/**]`→ADMIN, `/reviews/**`→CI, `/jobs/**`→WORKER, `/health`+actuator-health→permitAll,
  `anyRequest().denyAll()`. Ordering is correct (DELETE/GET admin rules precede the broad `/reviews/**`
  CI rule). Verified: `deleteRequiresAdminNotCi`, the 401/403 role-matrix tests. Path-traversal bypass
  (`/jobs/../reviews`) is blocked by Spring's default `StrictHttpFirewall` (400) before matching; no HTTP
  method-override filter is enabled; no "CI or ADMIN" ambiguity remains.
- **SR-17 — secrets/actuator/topology: PASS.** Tokens & GitLab token are `${ENV}`, never committed;
  `GatewayProperties.Security`/`GitLab` have masking `toString()`. Actuator exposes **health only**
  (`management.endpoints.web.exposure.include: health`) — no env/beans/configprops. `GET /backends`
  (`BackendView`) deliberately **omits the internal backend URL** (id/name/model/capacity/status/running/
  lastSeen only) → no internal topology leak (T-17). ADMIN-only anyway.
- **SR-18 — auth-failure logging + identity: PASS.** `TokenAuthenticationFilter` logs rejected tokens with
  method/path (never the token value); `SecurityConfig` logs 401/403 with path (+ authenticated principal
  for 403). Security-relevant domain events still carry the authenticated identity via `EventService`
  (feature 02).
- **F02-02 — concurrent duplicate-submit race: CLOSED-VERIFIED.** `ReviewRepository.findByIdForUpdate`
  (`@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`) is used in both `markFailed` and
  `persistCommentsAndComplete`; the second concurrent `submitResult` blocks on the row lock until the first
  `REQUIRES_NEW` tx commits, then re-reads status ≠ RUNNING and no-ops → no duplicate comments/publishes.
  The `DataIntegrityViolationException` catch was correctly moved **outside** the `REQUIRES_NEW`
  `TransactionTemplate` (a caught DIV inside would still mark the tx rollback-only → `UnexpectedRollback`).
  Exercised by `ResultProcessorConcurrentSubmitTest`.
- **SR-13 — bound parameters: PASS.** New queries (`findByIdForUpdate` JPQL, `countByStatusGrouped` JPQL,
  derived finders) are all parameterized; no string-built SQL anywhere in `src/main`.
- **Scheduling: PASS.** Each `@Scheduled` tick is wrapped in its own try/catch (a failing tick cannot
  de-schedule the task or take down siblings); intervals come from config; single-instance → no distributed
  lock needed. Underlying sweeps are idempotent + `FOR UPDATE`/status-guarded. Candidate queries are
  bounded by the 20–30 MR/day scale (no unbounded fan-out that constitutes a DoS).
- **Security headers / CORS: OK.** `SecurityConfig` leaves `.headers()` at Spring Security defaults
  (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, cache-control on secured responses) and adds
  no `.cors()` → cross-origin blocked by default (correct for a non-browser API). HSTS is expected to be
  added by the TLS-terminating reverse proxy (threat-model T-12).

## Dependency analysis (`mvn -o dependency:tree`)

`spring-security-{web,config,core,crypto}:6.5.11` is now on the runtime classpath (new this feature) —
a current, actively-patched 6.5.x line; no known unpatched CVE at 6.5.11 (the servlet stack is unaffected
by the WebFlux-only CVE-2024-38821; authorization-bypass fixes of the 6.x line are included well before
6.5.11). All other resolved versions are **identical to the feature-02 verified-clean baseline**:
`spring-boot:3.5.16`, `tomcat-embed-core:10.1.55`, `spring-core/web/webmvc:6.2.19`,
`jackson-databind:2.21.4`, `postgresql:42.7.11`, `hibernate-core:6.6.53.Final`, `logback-core:1.5.34`,
`snakeyaml:2.4`, `flyway-core:11.7.2`. **PASS — no new or regressed vulnerable versions.**

---

## Whole-system MUST-controls closure (final feature — everything open is stated)

| SR (MUST) | Status | Where |
|-----------|--------|-------|
| SR-01 token env-load + length floor + 401 | **CLOSED** | f03 `GatewayProperties.validateOnStartup`, `SecurityConfig` |
| SR-02 constant-time compare | **CLOSED** | f03 `TokenAuthenticationFilter` |
| SR-04 job ownership check | **CLOSED** | f02 `QueueManager` (verified) |
| SR-05 diff only to claimant | **CLOSED** (w/ SR-06/07 residual F03-04) | f02 claim flow |
| SR-08 LLM output HTML-escape/neutralize | **CLOSED** | f02 `CommentParser` (incl. F02-08) |
| SR-09 comment count/length caps | **CLOSED** | f02 `CommentParser` (incl. filePath) |
| SR-10 SSRF allowlist/loopback/redirect/timeout | **CLOSED-with-notes** (F03-01/F03-02) | f03 `BackendUrlValidator`/`RestClientConfig` |
| SR-11 edge body cap | **CLOSED-with-notes** (F03-03) | f03 `RequestBodySizeLimitFilter` |
| SR-12 no secrets/payload in events/logs | **CLOSED** | f02 `EventService` + f03 masked `toString` |
| SR-13 bound parameters | **CLOSED** | f01/f02/f03 |
| SR-14 no diff/raw/token in logs | **CLOSED** | f02/f03 |
| SR-15 generic errors + HTTPS | **CLOSED** (DB-sslmode ops note F03-05) | f03 `GlobalExceptionHandler` + startup https check |
| SR-16 single-role matrix | **CLOSED** | f03 `SecurityConfig` |
| SR-17 error/actuator/topology disclosure | **CLOSED** | f03 handler + actuator health-only + `BackendView` |
| SR-18 auth-failure logging + identity | **CLOSED** | f03 filter/`SecurityConfig` + f02 `EventService` |
| SR-21 raw-response cap | **CLOSED** | f02 `ResultProcessor` (verified) |
| **SR-23 CI security gates (gitleaks/SCA/semgrep)** | **OPEN** | no `.github/workflows`/`.gitlab-ci.yml`/pre-commit config in repo |

**Every code-level MUST control is closed.** The one still-open MUST is **SR-23** — no CI/CD security-gate
config exists in the repository. This is a process/infra control (not application code), but as the final
feature it must be **explicitly wired or accepted**: add `gitleaks` (pre-commit + full-history),
`osv-scanner`/OWASP Dependency-Check on `pom.xml` (block High/Critical), and `semgrep`
(`p/java`/`p/spring`/`p/sql-injection`/`p/secrets`) on each PR, per threat-model §5. Without it, an
F01-01-class dependency-EOL or a committed secret can recur ungated.

**SHOULD-level controls still open (threat-model ACCEPTED-RISK / tracked):** SR-03 (multi-token rotation),
SR-06 (claim lease token), SR-07 (per-backend token binding) — all F03-04; SR-19 (append-only DB grant on
`review_events`, ops); SR-20 (rate limiting, F03-06); SR-22 (at-rest/backup encryption + retention purge,
ops); SR-24 (per-worker claim metrics). These are the residual T-04/T-16/T-18/T-23 surfaces; each is a
SHOULD the threat model already accepted at this scale.

## Must-fix / must-do list

- **AppSec must-fix before merge:** none (no Critical/High security finding).
- **Must-fix before production (availability, QA/dev-owned):** the CRITICAL claim-decline → 500
  (`QueueManager.claim`/`BackendDispatcher` tx boundary). No data-security impact; SR-15 holds.
- **Deployment-config must-do:** F03-02 (`BACKEND_ALLOWED_HOST_PATTERN` tightened), F03-05
  (`sslmode=require` for non-loopback DB), and provision the SR-01 secrets ≥32 chars via env.
- **Process must-do (SR-23):** wire the CI security-gate pipeline, or explicitly accept its absence.
- **Explicitly re-accept (final-feature sign-off):** SR-03/06/07 (self-declared worker identity under a
  shared token, F03-04), SR-20 (no rate limiting, F03-06), and the ACCEPTED-RISK items (SPOF availability,
  shared-CI-token IDOR on `GET /reviews/{id}`, inherent diff exposure to a legitimately-claimed worker,
  DB-loopback non-TLS).

## Bottom line

Feature-03 delivers all deferred HTTP-layer MUST controls cleanly and closes F02-02; the code-level
security posture of the whole system is sound (all code MUSTs closed, dependencies clean, no injection/
SSRF-bypass/auth-bypass found). **PASS-with-notes.** The only non-code gap is SR-23 (CI gates), and the
only functional blocker for production is the QA-owned claim-decline availability bug — neither is an
AppSec merge blocker. Land the deployment-config must-dos and the SR-23 pipeline before go-live.

---

# Verification round — QA-defect remediation commit `bd789a1` (2026-07-13)

Method: read the full `bd789a1` diff **and** the current sources; checked the `Optional` refactor did
not weaken any contract (searched for every caller and every remaining reference to the old throwing
path); confirmed no new transaction-boundary hazard; re-ran the whole suite. HEAD `bd789a1`, tree clean,
no production code/tests modified by me.

**Suite (run by me): `mvn test` → `Tests run: 346, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.**
All four previously-failing intentional QA defect demos are now inverted to passing.

## QA-defect verdicts

- **QA-CRITICAL (claim-decline 500) — CLOSED-VERIFIED.** `BackendDispatcher.resolveClaimableBackend`
  now returns `Optional<Backend>` (empty for unknown / not-ACTIVE / at-capacity) and its own
  `@Transactional(readOnly=true)` is removed, so **no exception crosses a transactional-AOP proxy
  boundary** and nothing marks `QueueManager.claim`'s `REQUIRES_NEW` transaction rollback-only — the
  decline path now commits and returns 204. Verified: it is the **only** caller (`QueueManager.claim:72`
  consumes the `Optional`); `JobNotClaimableException` is **no longer thrown anywhere** in `src/main`
  (grep-confirmed: only the now-dead exception class + its orphaned `GlobalExceptionHandler` mapping
  remain — harmless, as agreed). No new tx hazard: the reads (`findByName`, `countRunningJobsForBackend`)
  still execute inside `claim`'s transaction (its sole call site is transactional), and a plain
  no-`@Transactional` bean method cannot mark the shared tx rollback-only. Tests
  `BackendDispatcherClaimDeclineTransactionBugTest` (×3, inverted to `doesNotThrow` + empty) and
  `BackendDispatcherTest`'s regression guard genuinely exercise all three decline cases.
- **QA-MINOR (type-mismatch 500 → 400) — CLOSED-VERIFIED.** `GlobalExceptionHandler.handleTypeMismatch`
  maps `MethodArgumentTypeMismatchException` → `400 VALIDATION_ERROR`. The message
  (`"<param>: must be a valid <SimpleTypeName>"`, e.g. `"id: must be a valid Long"`) exposes only the
  public path-variable name and a generic Java simple-type name — **no** stack trace, package, internal
  class, or SQL; SR-15/SR-17 preserved (same disposition as the existing `handleValidation`). Demo test
  `getStatusWithNonNumericIdShouldReturn400NotInternalError` inverted and passing.

## Finding dispositions (unchanged, carried forward)

F03-01 (Low, DNS-rebinding TOCTOU) · F03-02 (Low, permissive default allowlist — **deployment must-do**)
· F03-03 (Info, uncapped worker-only bodies) · F03-04 (Low, SR-06/07 self-declared identity — accepted)
· F03-05 (Low, DB `sslmode` — **deployment must-do**) · F03-06 (Info, SR-20 rate limiting — accepted).
None is a merge blocker; F03-02/F03-05 are deployment prerequisites.

## FINAL VERDICT: **APPROVED FOR MERGE**

Both QA defects are closed and independently verified against the current sources and a green 346/346
suite; the `Optional` refactor weakened nothing and introduced no new transaction hazard; every
code-level MUST control for the whole system is closed; dependencies are clean; no injection / SSRF-bypass
/ auth-bypass was found. Feature-03 — and the system — is cleared to merge, subject to the deployment
prerequisites and the one open process control below.

---

# Whole-system security posture summary (final — all three features)

**Code-level MUST controls: ALL CLOSED and verified.**
SR-01 (env tokens + ≥32-char + `https` startup gate), SR-02 (constant-time compare), SR-04 (job
ownership), SR-05 (diff only to claimant), SR-08 (LLM output HTML-escape/neutralize), SR-09 (comment
count/length + filePath caps), SR-10 (SSRF allowlist/loopback/redirect/timeout — notes F03-01/02),
SR-11 (edge body cap — note F03-03), SR-12 (no secrets/payload in events), SR-13 (bound parameters),
SR-14 (no diff/raw/token in logs), SR-15 (generic errors + HTTPS — DB-hop = F03-05), SR-16 (single-role
matrix + `denyAll`), SR-17 (actuator health-only, no topology/URL leak), SR-18 (auth-failure logging +
identity), SR-21 (raw-response cap). Injection surface clean; no SSRF/authz bypass found; dependencies on
the actively-patched baseline (`spring-boot 3.5.16`, `spring-security 6.5.11`, `tomcat 10.1.55`,
`jackson 2.21.4`, `postgresql 42.7.11`).

**The one OPEN MUST — SR-23 (CI/CD security gate).** No pipeline config exists in the repo. Concrete next
step (open-source, per threat-model §5): add a CI workflow that runs `gitleaks` (pre-commit + full-history,
block on any hit), `osv-scanner` **or** OWASP Dependency-Check on `pom.xml` (fail build on High/Critical —
this is the gate that would have caught the F01-01 EOL-Spring-Boot class of issue), and `semgrep`
(`p/java` `p/spring` `p/sql-injection` `p/secrets`) on every PR; keep the per-PR set under ~2 min and run
Dependency-Check-full + a ZAP baseline on a schedule. Until wired, **SR-23 must be explicitly accepted by
the owner** with the understanding that dependency-EOL/secret-commit regressions are ungated.

**Accepted-risk SHOULDs (threat-model-sanctioned at this scale; recorded, not blocking):**
SR-03 (multi-token rotation), SR-06 (claim lease token), SR-07 (per-backend token binding) — the
self-declared-worker-identity residual (F03-04, T-04); SR-19 (append-only DB grant on `review_events` —
ops/DB-role task); SR-20 (in-memory per-token rate limiting — F03-06, T-16/T-23); SR-22 (at-rest/backup
encryption + retention purge — ops); SR-24 (per-worker claim metrics). Plus the standing ACCEPTED-RISK
items: single-instance SPOF availability, shared-CI-token IDOR on `GET /reviews/{id}` (diff excluded from
the response), inherent diff exposure to a legitimately-claimed worker, and DB-loopback non-TLS.

**Deployment prerequisites (must be satisfied at/before go-live):**
1. Provide all four secrets (`CI_TOKEN`, `WORKER_TOKEN`, `ADMIN_TOKEN`, `GITLAB_TOKEN`) as env vars,
   each ≥32 chars — the app refuses to start otherwise (SR-01). Least-privilege, expiring GitLab
   project/group token scoped to the reviewed projects (SR-14/T-11).
2. Set `BACKEND_ALLOWED_HOST_PATTERN` to the real backend network — do not ship the `.*` default (F03-02).
3. Append `?sslmode=require` to a non-loopback `DB_URL` (F03-05); provide `DB_USER`/`DB_PASSWORD` via env.
4. Terminate TLS on every hop (CI→/Worker→/Admin→Gateway, Gateway→GitLab already `https`-enforced) at the
   reverse proxy; that proxy also supplies HSTS (T-12).
5. Wire the SR-23 CI security gate (above).
6. Grant the Gateway DB role `INSERT`/`SELECT`-only on `review_events` (SR-19) and enable volume/backup
   encryption + a retention purge for `review_inputs.diff`/`review_results.raw_response` (SR-22).
