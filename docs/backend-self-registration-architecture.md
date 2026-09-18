# Architecture: Backend Self-Registration & Backend Admin API

Status: **DRAFT** — hand-off to `appsec-engineer` for the pre-implementation threat model (extends `docs/threat-model.md` T-03/T-15/T-17/SR-10 and `docs/worker-threat-model.md` WT-16/WSR-INH-1).
Branches: `feature/backend-self-registration` (Gateway, this repo) + a matching `feature/backend-self-registration` in the Worker repo (`github.com/pirum1ch/AIReviewWorker`, submodule at `worker/`).
Requirement prefix: **`BSR-nn`**. Appsec layers its own ids on top (suggest `BST-` threats / `BSQ-` requirements), same convention as `WOC-`→`WOT-`/`WOR-`.

---

## 0. Scope and non-goals

Two things, shipped as one coherent feature:

- **(D) Admin backend-management REST API** — replaces today's "no REST endpoint, raw SQL `INSERT`" gap documented in `DEPLOYMENT.md` §5 and `README.md` §"Backend (llama-server) registration has no REST endpoint".
- **(A) Fully automatic Worker self-registration** — on startup, before it polls `/jobs/claim`, the Worker announces itself with its existing shared `WORKER` bearer token. The row goes live `ACTIVE` immediately. **No approval step** (option B of the prior memo was considered and declined).
- **The mandatory mitigation that ships *with* A**, not after it: `gateway.backend.allowed-host-pattern` may no longer be left at its wide-open `.*` default once a Worker-held token can create/update `backends` rows (§3).

**Non-goals — do not implement on this branch:**

- Renaming a backend (`backends.name` stays `updatable = false`; §2.6).
- Hard-deleting a `backends` row (FK-impossible for any backend that ever ran a job; §5).
- Per-backend worker tokens / binding `backendId` to the token (Gateway T-03 `SHOULD`, still an accepted residual — §7).
- Periodic re-announce / announce-driven heartbeating. Announce is once per Worker process lifetime. The existing `/health` probe remains the only liveness signal for a backend.
- Widening `GET /backends` to expose `backends.url` (T-17 minimization stays as-is; the new response DTOs reuse the same url-free `BackendView`).
- Touching `review_jobs` when a backend is decommissioned (§5).
- Any new infrastructure. Everything below is Spring Boot + PostgreSQL + slf4j already on the classpath. **No `pom.xml` change in either module.**

---

## 1. Summary

| Thing | Decision |
|---|---|
| Admin write API | `POST /backends` (upsert by name, PATCH-like), `DELETE /backends/{name}` (soft → `OFFLINE`) — both `ADMIN` |
| Worker self-announce | **Separate** endpoint `POST /backends/announce` — `WORKER` role, narrow fixed field set, always lands `ACTIVE` on create (§2.2) |
| Name collision | New nullable column `backends.announced_by`; a different `workerId` announcing an owned name gets **`409 BACKEND_NAME_TAKEN`**, no mutation (§2.4) |
| SSRF mitigation | `gateway.backend.self-registration.enabled` (default **`false`**); when `true`, startup **fails fast** unless `allowed-host-pattern` is *behaviourally* non-universal; write-time re-validation through the existing `BackendUrlValidator` (§3) |
| Audit | Structured INFO/WARN log lines only — `review_events.review_id` is `NOT NULL` FK to `reviews`, so backend-lifecycle events cannot go there (§6). One new in-memory `MetricsCounters` entry. |
| Migration | `V6__backend_self_registration.sql` — one additive nullable column (§8) |
| Worker config | New **optional** `backend.url` / `BACKEND_URL` (externally-reachable llama address). Unset = self-registration off, legacy behaviour (§4) |
| Worker startup | Announce in `WorkerRunner` **before** `workerLoop.start()`; transient failures retry forever with capped backoff (readiness stays `OUT_OF_SERVICE`), terminal 4xx fails startup (§4.3) |

---

## 2. Gateway-side API design

### 2.1 Why two endpoints, not one role-branching endpoint

**Decision: two genuinely separate endpoints.** `POST /backends` (ADMIN) and `POST /backends/announce` (WORKER).

- `SecurityConfig`'s contract is **exactly one required role per path** (SR-16 explicitly supersedes the architecture doc's looser table: "no 'CI or ADMIN' ambiguity"). A single endpoint serving two roles forces role inspection *inside* the controller — the one thing every controller in this codebase avoids ("this controller contains no authorization logic of its own").
- The two field surfaces genuinely differ: admin may set `status`, `capacity`, `structuredOutputMode`, `promptMessageFormat`; announce may set only `url` + `model` and never chooses a status.
- It gives appsec a single, minimal WORKER-reachable write surface to model, with one request shape and one code path — rather than "the ADMIN endpoint, minus fields, when the principal is a Worker".

**BSR-01** — `POST /backends/announce` and `POST /backends` are separate `@PostMapping`s with separate DTOs and separate service methods. Neither reads the `SecurityContext`.

### 2.2 `POST /backends/announce` (role `WORKER`)

Request:

```jsonc
{
  "backendId": "mac-mini-01",                 // = backends.name; wire name matches ClaimRequest.backendId
  "workerId":  "worker-mac-mini-01",          // same self-declared id as claim/heartbeat/result/fail
  "url":       "http://192.168.1.50:8080",    // externally reachable llama-server root (no trailing /health)
  "model":     "qwen2.5-coder"
}
```

Validation (bean validation on the DTO, sized to match the DB columns so a violation is never a `500`):

| Field | Rule |
|---|---|
| `backendId` | `@NotBlank @Pattern("^[A-Za-z0-9._-]{1,64}$")` — matches `VARCHAR(64)`; the charset also removes CR/LF log-injection (Worker-side precedent WSR-18) |
| `workerId` | `@NotBlank @Pattern("^[A-Za-z0-9._-]{1,64}$")` |
| `url` | `@NotBlank @Size(max = 256)` + `BackendUrlValidator` (§3.3) |
| `model` | `@NotBlank @Size(max = 128)` + `@Pattern("^[A-Za-z0-9._:/-]{1,128}$")` (it is rendered into logs and `/backends`) |

**No `capacity` field.** A Worker is structurally capacity-1 (`WorkerLoop`: "a single plain thread, never a pool"), so there is nothing for it to declare; and omitting it means an operator-set `capacity` is never stomped by a restart. On create the DB default (`1`) applies; on update `capacity` is left untouched.

Responses:

| Status | Body / code | When |
|---|---|---|
| `200` | `{"name","status","created"}` | Accepted — `created:true` for an INSERT, `false` for an UPDATE/no-op |
| `400` | `VALIDATION_ERROR` / `MALFORMED_REQUEST` | Existing `GlobalExceptionHandler` handlers |
| `403` | `FORBIDDEN` | Wrong role, **or the feature is disabled** (matcher absent → `anyRequest().denyAll()`, §3.1) |
| `409` | `BACKEND_NAME_TAKEN` | `announced_by` held by a different `workerId` (§2.4) |
| `409` | `LOCK_TIMEOUT` | Existing handler; row lock contention |
| `413` | `PAYLOAD_TOO_LARGE` | Existing `RequestBodySizeLimitFilter`, new 8 KiB cap (§3.4) |
| `422` | `BACKEND_URL_REJECTED` | URL failed `BackendUrlValidator` — **fixed, cause-undifferentiated message** (§3.3) |

**BSR-02** — The `200` body carries the *effective* status. If the row is `MAINTENANCE`/`OFFLINE`, announce succeeds, updates `url`/`model`, and returns that status; the Worker logs a WARN and polls anyway (getting `204`s). Announce **never** resurrects an operator-parked backend — otherwise the decommission path in §5 would be undone by the decommissioned host simply restarting.

**BSR-03** — Announce never writes `status` (except `ACTIVE` on INSERT), `capacity`, `structured_output_mode`, `prompt_message_format`, `probe_failed_since`, or `last_seen`. Stomping the two mode columns would silently undo a canary rollout (`DEPLOYMENT.md` §"Enabling a backend, once its capability is verified"); `last_seen` means "last *successful probe*" as of WOC-15 and an announce is not a probe.

### 2.3 `POST /backends` (role `ADMIN`) — upsert by name

```jsonc
{
  "name": "mac-mini-01",                       // required, same @Pattern as above
  "url": "http://192.168.1.50:8080",           // required on create; optional on update
  "model": "qwen2.5-coder",                    // required on create; optional on update
  "capacity": 1,                               // optional, @Min(1) @Max(64)
  "status": "ACTIVE",                          // optional: ACTIVE | MAINTENANCE | OFFLINE only
  "structuredOutputMode": "OFF",               // optional, bounded by the V5 CHECK vocabulary
  "promptMessageFormat": "MULTI"               // optional, bounded by the V3 CHECK vocabulary
}
```

- `201 Created` on insert, `200 OK` on update. Body: `BackendView` (§2.7).
- **PATCH-like on update**: an absent optional field leaves the column unchanged. Deliberate — a full-replace `PUT` would let an operator wipe `structured_output_mode` mid-rollout by forgetting one field. `null` is not distinguishable from absent in this shape and is treated the same (unchanged); clearing a mode column back to "use the global default" is out of scope, use SQL (documented).
- **`status` accepts `ACTIVE`/`MAINTENANCE`/`OFFLINE` only.** `SUSPECT` is health-checker-owned; sending it is `400 VALIDATION_ERROR`. This keeps operator-settable and prober-settable statuses disjoint, which composes exactly with `BackendHealthChecker`'s existing WOC-21 rule (it never touches a `MAINTENANCE`/`OFFLINE` row).
- **`@Max(64)` on capacity** is a real control, not cosmetics: `BackendDispatcher` gates dispatch on `running >= capacity`, so an absurd capacity makes a backend infinitely claimable and lets one host be handed the whole queue.
- **Setting `status: ACTIVE` also clears `probe_failed_since`.** Otherwise `BackendDispatcher`'s F-WOC-01 fail-fast decline keeps refusing the backend until the next successful probe pass — an "I set it ACTIVE and nothing dispatches" support ticket for no reason.
- **Any successful admin write sets `announced_by = NULL`** (releases self-registration ownership) — this *is* the host-replacement escape hatch (§2.4).

**BSR-04** — The admin path runs the same `BackendUrlValidator` as announce, but returns the validator's specific message (all four are constant strings with no reflected input). Only the WORKER path collapses them (§3.3).

### 2.4 Name collision — the crux

New nullable column **`backends.announced_by VARCHAR(64)`**: "the `workerId` of the Worker that currently holds self-registration of this row; `NULL` = unclaimed (registered by an admin, by raw SQL, or released by an admin write)."

Announce decision table:

| Existing row | `announced_by` | Action |
|---|---|---|
| none | — | INSERT: `status = ACTIVE`, `capacity = 1`, `announced_by = workerId`. `201`-equivalent (`created:true`) |
| exists | `NULL` | Claim it: set `announced_by = workerId`, update `url`/`model`. Status untouched |
| exists | `= workerId` | Update `url`/`model`. Status untouched |
| exists | `!= workerId` | **`409 BACKEND_NAME_TAKEN`**, zero mutation, WARN log, counter++ |

**BSR-05** — This is a **misconfiguration guard, not a security control**, and must be documented as such in the threat model. `workerId` is self-declared under the shared `WORKER_TOKEN` (T-15/WT-16, accepted residual), so a hostile token holder can announce any `workerId` it likes and take a name over. What it *does* buy, deterministically:

- The realistic failure — two hosts with a copy-pasted `BACKEND_ID` and distinct `WORKER_ID`s — becomes a **loud, blocked** startup failure on the second host instead of two Workers silently sharing one registry row while the Gateway health-probes only one of them.
- URL flapping between two hosts is structurally impossible, not merely visible after the fact.
- The same-host case that *should* self-heal (a Mac mini's advertised address changes, the process restarts) still self-heals with zero admin action.
- Every takeover attempt is logged and counted.

The actual security control on the URL is §3, and it is independent of this.

**Host replacement / re-provisioning** (new `WORKER_ID`, same `BACKEND_ID`): one admin call — `POST /backends {name, url, model, status:"ACTIVE"}` releases ownership (`announced_by = NULL`), the new Worker's next announce claims it. Documented in `DEPLOYMENT.md`.

### 2.5 Concurrency & idempotency

**BSR-06** — Both write paths are a single `@Transactional` (default isolation) service method on a new `BackendRegistryService` (package `com.review.gateway.service`, so `BackendUrlValidator`'s package-private `validate` is reusable **without widening its visibility**).

- Update path takes the existing row under `@Lock(PESSIMISTIC_WRITE)` via a new `BackendRepository.findByNameForUpdate(String)`, so two concurrent announces of the same name serialize and the `announced_by` check cannot race. Worst-case contention is `BackendHealthChecker`'s phase-C transaction, which is short by construction (WOC-14).
- Insert path: on `DataIntegrityViolationException` (unique `backends.name`, two racing creates) retry the whole read-modify-write **once**; the second pass finds the row and takes the update branch. More than one retry is a bug, not a race — let it surface as `500`.
- Announce is idempotent by construction: a re-announce with identical fields is a no-op UPDATE that changes no column (§6 logs it at DEBUG, not INFO).

### 2.6 `Backend.name` stays non-updatable

**BSR-07** — `@Column(name = "name", … updatable = false)` is unchanged. "Update" always means *same name, different url/model/capacity/status*. Rename is out of scope: the name is the join key for the Worker's `BACKEND_ID`, `/jobs/claim`, `BackendDispatcher.resolveClaimableBackend`, and every operational log line — renaming decouples all of them at once for no operational gain. To rename: register the new name, decommission the old (§5).

Entity delta is therefore exactly: one new field `announcedBy` + getter/setter. Nothing else on `Backend` changes.

### 2.7 `BackendView` gets one additive field

**BSR-08** — Add `String announcedBy` to `dto/BackendView` (and `service/dto/BackendSnapshot`). Additive and backward-compatible for `GET /backends`; it is the operator's answer to "which Worker owns this row / why am I getting `BACKEND_NAME_TAKEN`". `url` stays **out** of `BackendView` (T-17), including in the new `POST`/`DELETE` responses.

### 2.8 `SecurityConfig` wiring

**BSR-09** — Matchers, in this order (announce **before** the ADMIN rule, and `POST /backends` exact — not `/backends/**` — so no path serves two roles, SR-16):

```java
if (selfRegistrationEnabled) {
    auth.requestMatchers(HttpMethod.POST, "/backends/announce").hasRole("WORKER");
}
auth.requestMatchers(HttpMethod.POST,   "/backends").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/backends/*").hasRole("ADMIN")
    .requestMatchers(HttpMethod.GET,    "/backends", "/backends/**").hasRole("ADMIN");
```

Everything else under `/backends` falls through to the surviving `anyRequest().denyAll()`.

**BSR-10** — The announce handler additionally lives on a bean annotated `@ConditionalOnProperty(prefix = "gateway.backend.self-registration", name = "enabled", havingValue = "true")` — exact `WebhookController`/WHT-24 precedent: when off, the route does not exist in the context at all, not merely unauthenticated. Put announce on its own `BackendAnnounceController`; keep the admin writes on `AdminController` (unconditional).

---

## 3. The `allowed-host-pattern` mitigation (mandatory, ships with A)

Today `gateway.backend.allowed-host-pattern` defaults to `.*`, and `BackendUrlValidator` normalizes blank → `.*`. That is tolerable while the only writer is a DBA with `psql`; it is **not** tolerable once a `WORKER`-token holder can write the value that the Gateway will then HTTP-GET on a schedule.

**Chosen mechanism: option (b), hardened — a hard fail-fast at startup, plus a feature flag that keeps the surface non-existent by default, plus write-time re-validation.** Not option (a): there is no single "safe" default host pattern (deployments legitimately live on 10./172./192.168./LAN DNS names, all of which the existing loopback/link-local/any-local/multicast block already permits), so any default we pick is either wrong for real deployments or still effectively universal. Making the operator state their own backend network is the only honest version of this control.

### 3.1 Kill switch

**BSR-11** — New `gateway.backend.self-registration.enabled` (env `BACKEND_SELF_REGISTRATION_ENABLED`), **default `false`**. A Gateway upgrade must never silently open a new WORKER-writable endpoint. Turning it on is a deliberate, single-line, documented act — and it is the trigger for BSR-12.

### 3.2 Startup fail-fast (the actual mitigation)

**BSR-12** — In `GatewayProperties.validateOnStartup()` (new `validateBackendSelfRegistrationOnStartup()`, called alongside the existing `validateRetryAndBackendHealthOnStartup()` etc.): when `gateway.backend.self-registration.enabled = true`, the Gateway **refuses to start** unless `gateway.backend.allowed-host-pattern` is set, non-blank, compiles, and is **behaviourally non-universal**.

"Behaviourally non-universal" is tested, not string-compared — a literal `".*".equals(pattern)` check is false assurance (`.+`, `.*.*`, `[\s\S]*`, `(?s).*` all defeat it), and this codebase has explicit precedent for rejecting false-assurance checks (`WorkerProperties.validateServerBinding` / FW-01). The check:

```
SENTINELS = { "evil.example.com", "metadata.google.internal", "a3f9c1e2-probe.invalid" }
if (pattern matches ALL sentinels) -> IllegalStateException
```

Message (names the property, never a value):

> `gateway.backend.allowed-host-pattern must be narrowed to your actual backend network before gateway.backend.self-registration.enabled=true — the configured pattern matches every host — refusing to start`

Also fail fast on a `PatternSyntaxException` (today an uncompilable pattern surfaces only at probe time, as a per-probe exception). When self-registration is **off**, the pattern is validated for compilability only — today's permissive default keeps working for existing deployments, unchanged.

Rationale for coupling the requirement to the flag rather than making it unconditional: it keeps this branch a no-op for every deployment that does not opt in (same discipline as PMR-10/WHT-24), while making the opt-in impossible to perform unsafely.

### 3.3 Write-time validation (defense in depth)

**BSR-13** — `BackendRegistryService` calls `BackendUrlValidator.validate(url, allowedHostPattern)` before persisting, on **both** paths. Reuse as-is; do not fork or reimplement it. It already enforces: scheme ∈ {http, https}; host resolves; not loopback/link-local/any-local/multicast; matches the pattern.

- **Announce (WORKER):** catch `BackendUnavailableException` and rethrow as `BackendUrlRejectedException` → `422 BACKEND_URL_REJECTED` with a **single fixed message** ("Backend URL was rejected"). The validator's four distinct messages would otherwise form a DNS-resolution / allowlist-membership oracle for a `WORKER`-token holder. Real cause logged server-side at WARN.
- **Admin:** return the validator's own (constant, non-reflecting) message — an operator registering a backend needs to know *which* rule it failed.
- Add a `GlobalExceptionHandler` mapping for the new `BackendUrlRejectedException` only. Do **not** globally map `BackendUnavailableException` — it is thrown from scheduled probe code paths where a 4xx mapping is meaningless.

**BSR-14** — The pattern remains enforced at probe time (`BackendProberImpl`, unchanged). Consequence worth stating explicitly for appsec: tightening `allowed-host-pattern` later neutralizes *already-registered* rows on the very next probe pass without any re-registration — the write-time check is an early rejection, never the sole gate.

**Operator-facing implication:** a single-host dev setup cannot self-register `http://127.0.0.1:8000` — loopback is (and already was) rejected by `BackendUrlValidator`, so such a backend could never be probed anyway. Use the host's LAN address. This is pre-existing behaviour, not a new restriction; it must be spelled out in `DEPLOYMENT.md` because the Worker's `LLAMA_URL` default *is* loopback and the difference between the two settings is exactly the point of §4.1.

### 3.4 Body-size cap

**BSR-15** — `RequestBodySizeLimitFilter` currently caps only `POST /reviews`, `/jobs/{id}/result`, `/jobs/{id}/fail`, and the webhook path; anything else is uncapped. Add `POST /backends` and `POST /backends/announce` with a **hardcoded 8 KiB** constant (64+256+128+64 chars of payload plus JSON overhead is two orders of magnitude below it — there is nothing to tune, so no property). Register the announce pattern in the filter only when the feature is enabled, mirroring the existing `webhookPattern` field's `null`-when-disabled treatment. *If appsec wants it configurable, it is one property on `GatewayProperties.Backend`.*

---

## 4. Worker-side design

### 4.1 New configuration

**BSR-16** — One new setting, in the existing `backend:` block (which today holds only `id`) — **not** in `llama:`:

```yaml
backend:
  id:  ${BACKEND_ID}          # unchanged
  url: ${BACKEND_URL:}        # NEW, optional. Externally reachable llama-server root, as the GATEWAY sees it.
```

`WorkerProperties.Backend` gains `private String url;` (no `@NotBlank` — optional by design).

The naming is deliberate: it is the value that lands in `backends.url` on the Gateway, and it pairs with `backend.id` exactly as the two announce fields do. Docs must state the distinction in one line, because it is the single most confusable pair in the whole config surface:

> `llama.url` = where **this process** connects (loopback, `http://127.0.0.1:8000`).
> `backend.url` = where the **Gateway** connects to health-probe the same server (LAN address).

**BSR-17 — required vs optional: optional, and its presence *is* the toggle.** Unset (default) ⇒ no announce, exactly today's behaviour, one INFO line at startup:

> `Backend self-registration disabled (backend.url not set); this backend must be registered via the Gateway's admin API or SQL`

This is what makes the cross-repo rollout safe in either order: a new Worker JAR against an old Gateway, or against a Gateway with the flag off, does not break. No separate boolean flag — a second knob that must agree with the first is a footgun, not a feature.

**BSR-18 — fail-fast validation** in `WorkerProperties.validateOnStartup()` (new `validateBackendUrl()`, only when `backend.url` is non-blank), matching the existing style exactly (property named, value never echoed):

1. Parses as a `URI`, else `"backend.url is not a valid URI — refusing to start"`.
2. Scheme is `http`/`https`, else refuse.
3. Host is **not** loopback (reuse the existing private `isLoopbackHost`), else:
   `"backend.url must be an address the Gateway can reach (not loopback) — it is not the same value as llama.url — refusing to start"`.
   Catching this locally turns an otherwise-guaranteed `422 BACKEND_URL_REJECTED` into a precise, actionable startup message naming the confusion it is almost certainly caused by.
4. WARN (not fail) if `backend.url` and `llama.url` are byte-identical — legal in an unusual non-loopback llama deployment (`llama.allow-non-loopback`), but far more often a mistake.

### 4.2 `GatewayClient.announce(...)`

**BSR-19** — One new method on the existing `GatewayClient`, same `gatewayRestClient` bean, same bearer token, same DTO style (`record AnnounceRequest(String backendId, String workerId, String url, String model)` / `record AnnounceResponse(String name, String status, boolean created)` under `gateway/dto/`), same never-log-the-token/diff discipline:

```java
public AnnounceOutcome announce(AnnounceRequest request);   // ACCEPTED(response) | REJECTED(statusCode)
```

Mapping, mirroring `heartbeat`/`submitResult`'s existing outcome-enum style rather than throwing for expected cases:

- `2xx` → `ACCEPTED`.
- `400/403/409/422` → `REJECTED` (carrying the status code only — **never** the response body, which is Gateway-controlled text).
- Anything else (`5xx`) → `GatewayUnavailableException` via the existing `mapServerError`.
- `ResourceAccessException` → `GatewayUnavailableException`, as everywhere else.

### 4.3 Where it happens, and what happens when the Gateway is down

**BSR-20** — In `WorkerRunner.run(...)`, **before** `workerLoop.start()`. `WorkerRunner` is an `ApplicationRunner`, so this executes after the context refresh and after the embedded server is up (`/actuator/health` liveness already answering) but **before** Spring Boot publishes `ReadinessState.ACCEPTING_TRAFFIC` — meaning a Worker that has not yet registered reports **readiness `OUT_OF_SERVICE` for free**, with zero new code. That is precisely the right signal and it is a deliberate reason to put the call here rather than inside `WorkerLoop`.

Failure policy — **block startup, retry transient failures forever, fail hard on terminal ones**:

| Outcome | Behaviour |
|---|---|
| `ACCEPTED` | INFO (`created`/`updated`, name, status), then `workerLoop.start()`. If status is `MAINTENANCE`/`OFFLINE`, an additional WARN: registered but no jobs will be dispatched until an operator reactivates it |
| `GatewayUnavailableException` (connect refused, 5xx, timeout) | WARN + sleep, **retry indefinitely** with the same capped exponential backoff as the claim loop (base `network.poll-interval-ms`, cap 60 s). The loop never starts until announce succeeds |
| `REJECTED` (400/403/409/422) | **Fail startup**: throw `IllegalStateException`, context fails, non-zero exit. Message names the status and the likely cause (403 ⇒ Gateway's `self-registration.enabled` is off or the token is not the WORKER token; 409 ⇒ `BACKEND_ID` already owned by another `WORKER_ID`; 422 ⇒ `backend.url` rejected by the Gateway's host allowlist) |
| Thread interrupted during backoff | Propagate; the process is shutting down |

Justification for blocking rather than "start polling and announce in the background":

- At startup there is **no in-flight work to lose** — the only thing blocking costs is idle time the Worker had nothing to do with anyway (an unregistered backend's `/jobs/claim` returns `204` forever, and if the Gateway is unreachable, claims would fail identically).
- It keeps the Worker state machine trivially small: `WorkerLoop` runs **iff** announce succeeded. No "announced yet?" flag, no background re-announce scheduler, no new thread — the alternative costs all three.
- The terminal-4xx cases are misconfigurations that will *never* fix themselves; polling for `204`s forever is the silent-stall failure class this project keeps designing against. launchd/systemd restarting into the same loud failure is the correct, visible outcome.

**BSR-21 — re-announced on every restart: yes.** That is what makes it self-healing (address change, row deleted, fresh DB). Volume is one request per **process lifetime**, not per heartbeat — nowhere near `heartbeat.interval-sec`-scale traffic. Even a crash-looping Worker produces field-identical announces, which the Gateway logs at DEBUG (§6), so there is no audit-log spam and no update storm; the DB write is a single-row UPDATE that changes no column.

---

## 5. Admin decommission path

**BSR-22** — `DELETE /backends/{name}` (ADMIN) is a **soft decommission**, never a row delete:

```
status             := OFFLINE
announced_by       := NULL
probe_failed_since := NULL
```

`200` with the resulting `BackendView`; `404 NOT_FOUND` for an unknown name; idempotent (already `OFFLINE` → `200`, no change).

Reusing `OFFLINE` rather than adding a status: `MAINTENANCE`/`OFFLINE` already mean exactly "excluded from new assignments, and the health checker will not touch it" (`BackendDispatcher` requires `ACTIVE`; `BackendHealthChecker` phase C bails on anything else). Nothing new is needed. Use `MAINTENANCE` for "temporarily parked, coming back" and `OFFLINE` for "decommissioned" — both via `POST /backends` with an explicit `status`; `DELETE` is simply the ergonomic shorthand for the latter.

**A hard delete is not offered, and cannot be.** `review_jobs.backend_id` and `review_results.backend_id` are FKs to `backends(id)` with no `ON DELETE` action (V1). Any backend that has ever run a job cannot be deleted without either an FK violation or destroying audit history. Soft-delete is the only correct semantic; say so plainly in the docs so nobody later "fixes" it with `ON DELETE SET NULL`.

**BSR-23** — `DELETE` does **not** touch `review_jobs`. An in-flight job on a decommissioned host completes normally or is swept by the existing stale-heartbeat path. Explicit non-goal: the queue's ownership rules stay in `QueueManager`/`RetryManager`, untouched by the registry.

Clearing `announced_by` here is what lets a replacement host claim the name later (§2.4) without a second admin call.

---

## 6. Audit trail

**BSR-24** — There is **no** backend-lifecycle audit table today, and `review_events` cannot host one: `review_events.review_id` is `NOT NULL REFERENCES reviews(id)` (V1), and every `EventType` is Review-scoped. Do not add a nullable-FK escape hatch, a new table, or a synthetic Review. Structured **log lines** are the audit trail, exactly as the task scopes it.

Required lines (all field-name=value, never a token, never the full stack, and **CR/LF-stripped before logging** — legacy rows registered via raw SQL predate the `name`/`model` `@Pattern` checks and could contain control characters):

| Level | Event |
|---|---|
| `INFO` | `Backend registered via self-announce (name=…, workerId=…, model=…, host=…)` — INSERT. Log the URL's **host[:port]**, not the full URL |
| `INFO` | `Backend updated via self-announce (name=…, workerId=…, changed=[url,model])` — UPDATE that changed ≥1 column |
| `DEBUG` | `Backend re-announced unchanged (name=…, workerId=…)` — the common restart case; keeps a crash-loop out of INFO |
| `WARN` | `Backend announce rejected: name already owned (name=…, owner=…, claimant=…)` — the 409 |
| `WARN` | `Backend announce rejected: URL failed validation (name=…, workerId=…, reason=…)` — reason server-side only |
| `WARN` | `Backend announced while parked (name=…, status=…)` — announce accepted onto a `MAINTENANCE`/`OFFLINE` row |
| `INFO` | `Backend registered/updated by admin (name=…, changed=[…])` / `Backend decommissioned by admin (name=…)` |

**BSR-25** — One new counter in `MetricsCounters`, surfaced through `MetricsSnapshot`/`MetricsResponse`/`GET /metrics`: `backendAnnounceRejected`, a `Map<String, AtomicLong>` **keyed on a closed Gateway-side reason vocabulary** (`NAME_TAKEN` / `URL_REJECTED` / `VALIDATION`) — never the backend name, `workerId`, or URL (same discipline as `webhookDiffIntegrityFailures`/`structuredValidationFailures`). This is the detection signal for a name-takeover or SSRF-probing campaign; it is process-local in-memory observability state, which `MetricsCounters`' own javadoc already establishes does not fall under "PostgreSQL is the single source of truth".

---

## 7. Non-negotiable-principle check

| Principle | Verdict |
|---|---|
| **Gateway is the sole owner of business logic and state** | **Holds.** The Worker sends a *claim*; the Gateway alone validates it (host allowlist, name ownership, field bounds, status policy) and performs the only write. The Worker cannot choose a status, a capacity, or override an operator decision. The name "announce" is chosen over "register" for exactly this reason |
| **Worker is a fully stateless HTTP client; never touches Postgres or GitLab** | **Unchanged.** One more HTTP call on the existing `gatewayRestClient`, existing token, no new credential of any kind. Nothing is cached across restarts — it re-announces every time |
| **PostgreSQL is the single source of truth** | **Holds.** The registry stays in `backends`; the only new state is one column. The one new in-memory value is a metrics counter (BSR-25), explicitly precedented |
| **No extra infrastructure** | **Holds.** No new dependency in either `pom.xml`, no new thread, no new scheduler, no new table |
| **Idempotency everywhere a retry can happen** | **Holds.** Announce is an upsert keyed on the unique `name`, serialized under a row lock, with a single retry on the insert race; a retried identical announce changes no column. `DELETE` is idempotent |
| **Fail fast at the edge** | **Holds.** Bean validation + `BackendUrlValidator` at the controller/service boundary; body cap at the filter; config errors at startup, not at first probe |
| **Gateway restarts must not disturb in-flight work** | **Holds.** Nothing here reads or writes `review_jobs` |

**Residual explicitly *not* fixed here (must be restated in the threat model):** T-03/T-15/WT-16 — `backendId` and `workerId` remain self-declared under a single shared `WORKER_TOKEN`. Self-registration widens what that token can do from "claim jobs for any backend name" to "claim jobs for any backend name **and** create/repoint a registry row for a name it owns or that is unowned". That widening is bounded by (i) the host allowlist, now mandatory and startup-enforced (§3), (ii) the `announced_by` ownership check (§2.4), (iii) the fact that `backends.url` is used *only* for `GET {url}/health` with redirects disabled, a short timeout, and a discarded body — it is never sent a diff, (iv) the kill switch defaulting off. The clean fix remains T-03's `SHOULD` (one worker token per backend, token-bound `backendId`), which stays out of scope.

---

## 8. Migration

`src/main/resources/db/migration/V6__backend_self_registration.sql` — one additive nullable column, no constraint changes, no backfill:

```sql
SET lock_timeout = '5s';   -- a stuck ALTER fails fast rather than blocking behind ACCESS EXCLUSIVE

ALTER TABLE backends
    ADD COLUMN announced_by VARCHAR(64);   -- workerId holding self-registration of this row; NULL = unclaimed
```

Nothing else needs migrating. `capacity`, `model`, `url`, and `status` already have the right types, bounds, and `CHECK` constraints for everything these endpoints can write; the `status` vocabulary is unchanged (no new status value); `name`'s `UNIQUE` is what the upsert keys on.

**BSR-26 — deployment prerequisite, easy to miss and fatal in production:** the Gateway's constrained application DB role currently only ever `UPDATE`s `backends` (the health checker). **This feature introduces the first `INSERT` into `backends` from the application role.** `DEPLOYMENT.md`'s `GRANT` block must add `INSERT` on `backends`, or the very first announce fails with a permission error at runtime. No sequence grant should be needed (`backends.id` is `GENERATED BY DEFAULT AS IDENTITY`, whose sequence permission follows the table, unlike `serial`) — **verify this against the actual deployed role rather than assuming**. `DELETE` is *not* needed (§5 is a soft delete).

---

## 9. Doc / config-reference impact (flagged, not written — later SDLC step)

| File | What becomes wrong or missing |
|---|---|
| `DEPLOYMENT.md` §5 "Step 3: Register the llama backend" | The **"STUB — not implemented: no admin API for backend registration"** banner and the "only way is a direct SQL insert" claim are both false. Rewrite as three paths: self-registration (default for a 1:1 Worker host), admin `POST /backends`, raw SQL (legacy/break-glass) |
| `DEPLOYMENT.md` §10.6 "Backend registration SQL" | Keep as break-glass, but demote below the API |
| `DEPLOYMENT.md` §11 Docker walkthrough (`# 3. Register a backend (§5 -- no REST endpoint, direct SQL only)`) | Comment is wrong; also the `docker exec … INSERT` step can be replaced with `BACKEND_URL` on the Worker container. Note the loopback caveat: `http://127.0.0.1:8000` is **not** announceable (§3.3) |
| `DEPLOYMENT.md` config reference | New rows: `BACKEND_SELF_REGISTRATION_ENABLED` / `gateway.backend.self-registration.enabled`; the changed status of `BACKEND_ALLOWED_HOST_PATTERN` (**now a hard startup requirement when self-registration is on**, no longer "нет (`.*`)"); Worker-side `BACKEND_URL` / `backend.url` with the `llama.url` distinction spelled out |
| `DEPLOYMENT.md` PostgreSQL `GRANT` block | Add `INSERT` on `backends` (BSR-26) |
| `README.md` §"Backend (llama-server) registration has no REST endpoint" | Delete/replace |
| `README.md` §3 role→endpoint table | Add `POST /backends` (ADMIN), `DELETE /backends/{name}` (ADMIN), `POST /backends/announce` (WORKER, conditional on the flag) |
| `README.md` §6.7 `GET /backends` | New `announcedBy` field in the sample response; new sibling sections for the write endpoints; new error codes `BACKEND_NAME_TAKEN`, `BACKEND_URL_REJECTED` |
| `README.md` §10 "Backend registry & health" status table | `MAINTENANCE`/`OFFLINE` rows say "there is no endpoint to set these, only a direct `UPDATE`" — now false |
| `README.md` §4.3 deployment MUST-DOs / SSRF-guard bullet | `allowed-host-pattern` is no longer merely "must be tightened for production" — it is startup-enforced under the new flag |
| Cross-repo coupling table (added during the repo split) | New coupled pair: Worker `backend.url` ⟷ Gateway `gateway.backend.allowed-host-pattern` (**silent on mismatch in one direction only** — the Worker fails startup with a `422`-driven message, so this pair is *louder* than the existing `answer-reserve`/`maxTokens` pair; say so). Plus Worker `backend.url` set ⟷ Gateway `self-registration.enabled` (mismatch ⇒ Worker fails startup on `403`) |
| `worker/README.md` (Worker repo) | New `BACKEND_URL` env var; the announce step in the startup sequence; the four terminal-failure messages and what each means |
| `CLAUDE.md` | "API surface" (three new endpoints), "Data model" (`backends.announced_by`), and the `docs/` inventory line (this file + its threat model) |

---

## 10. Implementation checklist (for `backend-developer`, after appsec)

**Gateway** (`feature/backend-self-registration`):
1. `V6__backend_self_registration.sql`; `Backend.announcedBy` + accessors.
2. `BackendRepository.findByNameForUpdate` (`@Lock(PESSIMISTIC_WRITE)`).
3. `BackendRegistryService` (package `…gateway.service`) — `announce(...)` / `upsertByAdmin(...)` / `decommission(name)`; `@Transactional`; the §2.4 decision table; `BackendUrlValidator` reuse; insert-race single retry.
4. DTOs (`record`s): `AnnounceBackendRequest`, `AnnounceBackendResponse`, `UpsertBackendRequest`; `BackendView` + `BackendSnapshot` gain `announcedBy`.
5. `BackendAnnounceController` (`@ConditionalOnProperty`); `POST`/`DELETE` on `AdminController`.
6. `SecurityConfig` matchers (BSR-09, order matters); `RequestBodySizeLimitFilter` patterns (BSR-15).
7. `GatewayProperties.Backend.selfRegistration.enabled` + `validateBackendSelfRegistrationOnStartup()` (BSR-12, sentinel check).
8. `BackendUrlRejectedException` + `GlobalExceptionHandler` mapping; `BackendNameTakenException` → `409`.
9. `MetricsCounters.backendAnnounceRejected` → `MetricsSnapshot` → `MetricsResponse`.
10. Tests: the §2.4 table (all four rows), concurrent-announce race, `MAINTENANCE` never resurrected, admin `SUSPECT` rejected, startup fail-fast for `.*`/`.+`/`[\s\S]*`/blank/uncompilable, `422` message identical for every URL-rejection cause on the WORKER path, matcher wiring (WORKER cannot reach `POST /backends`; ADMIN cannot reach `/backends/announce`; both `403` when the flag is off).

**Worker** (matching branch in `AIReviewWorker`):
1. `WorkerProperties.Backend.url` + `validateBackendUrl()` (BSR-18, all four rules).
2. `GatewayClient.announce(...)` + `AnnounceRequest`/`AnnounceResponse`/`AnnounceOutcome`.
3. `WorkerRunner`: announce before `workerLoop.start()`, backoff loop + terminal-4xx `IllegalStateException` (BSR-20).
4. `application.yml`: `backend.url: ${BACKEND_URL:}` with the bilingual comment distinguishing it from `llama.url`.
5. Tests: unset ⇒ no announce and the loop still starts; transient failure ⇒ retries and does not start the loop; each terminal status ⇒ startup fails with its own message; `MAINTENANCE` response ⇒ WARN but the loop starts.

---

Two things flagged for appsec before implementation: (1) **BSR-26** — the app DB role has never had `INSERT` on `backends`; without that grant the first announce fails in production only, not in tests. (2) The `announced_by` ownership check is a *misconfiguration* guard, not a security boundary (`workerId` is self-declared under the shared token) — written that way deliberately so appsec doesn't credit it as a control; the real URL control is the startup-enforced allowlist.
