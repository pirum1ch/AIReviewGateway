# LLM Worker (Executor) — Implementation Architecture

**Stage:** architect · **Feature:** part 2 (Worker) · **Target repo dir:** `worker/` (standalone Maven project, own `pom.xml`, NOT a module of the root Gateway pom) · **Downstream consumers:** threat-model stage, dev pipeline.

---

## 0. FLAG FOR USER REVIEW — API-conflict decision + every Worker-spec deviation

### 0.1 Decision: **Option A — adapt the Worker to the real, frozen Gateway API.**

The Worker spec (`# LLM Worker (Executor) — Техническая спецификация.md`) describes a `/internal/workers/*` + `/internal/jobs/*` API that **does not exist** in the shipped Gateway. The real, verified, 346-test Gateway (`JobController`, DTOs, `SecurityConfig`, README §6/§9) exposes only `POST /jobs/claim`, `POST /jobs/{id}/heartbeat`, `POST /jobs/{id}/result`.

**Option B (extend the Gateway with `/internal/*`) is rejected.** It contradicts the Gateway's frozen `Требования v2` API and its own architectural principle "Gateway is the sole owner of business logic and state; PostgreSQL is the single source of truth." It would reopen a security-verified, merged system to add redundant endpoints (a registration/worker-registry table, a second result-shape, a `/failed` channel) that duplicate mechanisms the Gateway already implements differently (implicit registration via the `backends` row; idempotent single-result endpoint; heartbeat-timeout reclaim as the failure channel). The cost/risk is not justified — the Worker spec is a *draft against an assumed API*, and the Gateway is the source of truth.

**Option C (hybrid) is unnecessary** once you accept that the only thing the Worker spec's richer `/next` response added — ready-made `messages[]`, `model`, `temperature`, `maxTokens` — can be produced Worker-side from `promptVersion` + config without any Gateway change.

**Does building the prompt Worker-side violate the spec's "Worker must not analyze prompt content"?** No. The Worker performs **template materialization**, not analysis: it selects a template purely by the *opaque* `promptVersion` string the Gateway supplies (it never inspects, parses, or branches on the diff's *content*), substitutes the diff into a single placeholder, and ships the result to llama-server verbatim. There is no business decision. **However**, this does move one artifact — the prompt template text — physically onto the Worker host, which is a real (and the only material) departure from "Gateway owns ALL business logic." This is **unavoidable under the frozen API**: the Gateway stores and returns only the raw diff (`review_inputs.diff`) and a version tag; it never returns prompt text. Mitigation is contractual, see §0.3.

### 0.2 Deviation ledger (every difference the decision causes — USER MUST CONFIRM)

| # | Worker-spec expectation | Reality on the frozen Gateway | Resolution in this Worker |
|---|---|---|---|
| D1 | `POST /internal/workers/register` (workerId, hostname, version, capabilities.contextWindow/parallel) | **No endpoint.** Backends are registered by operator SQL `INSERT INTO backends(name,url,model,capacity)`; there is no worker registry. | **Registration removed.** Worker "announces" itself implicitly on the first `POST /jobs/claim` (`backendId`=backend *name*, self-chosen `workerId`). `hostname/version/capabilities/contextWindow` are **not transmitted** (Gateway's diff budget uses its own `gateway.diff.context-window`). |
| D2 | `POST /internal/workers/next` → `{jobId, model, temperature, maxTokens, messages[]}` | `POST /jobs/claim` → `{jobId, reviewId, payload:{diff, promptVersion}}` / `204`. | Worker calls **`/jobs/claim`** and **builds `messages[]` locally** from a `promptVersion`-keyed template (§0.3). Endpoint path and shape differ; `204` semantics identical. |
| D3 | Gateway supplies `model` / `temperature` / `maxTokens` | Gateway supplies **none of these**. | They come from **Worker config** (`llama.model/temperature/maxTokens`), optionally overridden per `promptVersion` by a template profile. New config surface not in the spec (§4). |
| D4 | Prompt (`messages[]`) authored by Gateway (business logic) | Gateway returns raw `diff` + `promptVersion` only. | **Prompt template lives on the Worker**, keyed by `promptVersion`. Genuine deviation from "Gateway owns all business logic"; mitigated as a shared versioned platform artifact (§0.3). |
| D5 | `POST /internal/jobs/{id}/complete` `{finishReason, promptTokens, completionTokens, durationMs, response}` | `POST /jobs/{id}/result` `{workerId, rawResponse, promptTokens, completionTokens, durationMs, model}`. | Field renames: `response`→`rawResponse`; **`finishReason` dropped** (no Gateway field); **`workerId` added** (SR-04 ownership); **`model` added** (from config). |
| D6 | `POST /internal/jobs/{id}/failed` `{errorType, message}` | **No failure endpoint.** `/jobs/{id}/result` requires **non-blank** `rawResponse` and marks the review `COMPLETED`. | **No synthetic error result is ever submitted** (that would post garbage to the MR). A llama failure is reported by **abandoning the job**: stop heartbeating, submit nothing. The Gateway's stale-heartbeat sweep (180 s) requeues/fails the job per *its* retry policy. Cost: up to ~180 s reclaim latency and backend capacity held meanwhile (acceptable at 20–30 MR/day). See §7. |
| D7 | Heartbeat `POST /internal/workers/heartbeat` **every 30 s**, body `{status: IDLE\|BUSY, runningJob, uptime, version}` | `POST /jobs/{id}/heartbeat` (**per-job**, only while `RUNNING`), body `{workerId}`, response `{shouldContinue}`. | (a) **Cadence 30 s → default 60 s** (config `heartbeat.intervalSec`, must stay well below the 180 s staleness timeout). (b) **No IDLE heartbeat at all** — when the Worker holds no job it simply polls `/jobs/claim`; there is no Worker-liveness ping between jobs. (c) `status/runningJob/uptime/version` **not transmitted**; Gateway infers per-job liveness from `heartbeat_at`. `shouldContinue:false` drives abort (matches the spec's intent). |
| D8 | `GET /metrics` (Prometheus) | Approved stack table mandates **Actuator + Micrometer** at `/actuator/prometheus`. | Expose **`/actuator/prometheus`** (approved doc supersedes the terse spec). One-line config alias to also serve `/metrics` provided in §8 if the scraper needs that path. |
| D9 | `gateway.apiKey` | Gateway auth is the shared `WORKER_TOKEN` bearer. | `gateway.apiKey` binds to env `WORKER_TOKEN`; sent as `Authorization: Bearer`. |

If the user disagrees with **D6** (abandon-on-failure) in particular, the only alternative is Option B (add a Gateway `/failed` endpoint) — call it out now, before dev starts.

### 0.3 Prompt-template contract (mitigation for D4)

- Template is selected by the **opaque `promptVersion`** string only. Unknown `promptVersion` ⇒ the Worker **abandons the job** (D6 path) and logs `unknown promptVersion=<v>` — it never guesses a template.
- Template is **dumb**: fixed system+user text with a single `{{DIFF}}` placeholder. It **must** instruct the model to emit the exact shape the Gateway's parser expects — a JSON array of `{file, line, severity, comment}` (README §6.6). This output contract is the one piece of "business logic" that must be co-owned; treat templates as **versioned platform artifacts** reviewed together with the Gateway team, shipped on the classpath (`src/main/resources/prompts/<promptVersion>.yml`), not invented ad hoc by the Worker.
- Optional per-version overrides for `model/temperature/maxTokens` live in the same template file; otherwise fall back to global `llama.*` config.

---

## 1. Summary

A maximally lightweight, stateless transport agent co-located 1:1 with a `llama-server`. It loops: claim a job from the Gateway → materialize a prompt from `promptVersion`+`diff` → call local `llama-server` (OpenAI Chat-Completions) → submit the raw result back. It owns **no** business logic, **no** state, **no** GitLab/PostgreSQL access. All queue/retry/dedup/timeout/routing/publish logic stays in the Gateway.

## 2. Assumptions

- Backend `capacity = 1` (spec `parallel:1`): the Worker processes **one job at a time**. The main loop is single-threaded by design.
- Worker→Gateway is **HTTPS** (spec diagram + threat model); Worker→llama is **HTTP over loopback** (`127.0.0.1`).
- llama-server ignores/echoes the `model` field; the Worker sends its configured `llama.model` and reports the same string back in `result.model`.
- The Worker runs under launchd on a Mac mini (primary) or optionally Docker; single process, restarted by the supervisor on crash.

---

## 3. Architecture

### 3.1 Component / data-flow diagram

```
                     Authorization: Bearer WORKER_TOKEN (HTTPS)
 ┌──────────────────────────────────────────────────────────────────────┐
 │  Review Gateway (frozen, external)                                     │
 │    POST /jobs/claim            → {jobId,reviewId,payload{diff,pv}}/204 │
 │    POST /jobs/{id}/heartbeat   → {shouldContinue}                      │
 │    POST /jobs/{id}/result      → {reviewId,status}  (idempotent)       │
 └──────────────────────────────────────────────────────────────────────┘
        ▲ claim/result          ▲ heartbeat (per-job, ~60s)
        │                       │
 ┌──────┼───────────────────────┼──────────────────────────────────────┐
 │  LLM WORKER (this project — single JVM process)                      │
 │  ┌────────────┐   ┌────────────────┐   ┌──────────────────────────┐  │
 │  │GatewayClient│  │HeartbeatSchedule│  │ WorkerProperties (+valid)│  │
 │  └─────▲──────┘   └───────▲────────┘   └──────────────────────────┘  │
 │        │  claim/result    │ tick + shouldContinue→abort               │
 │  ┌─────┴───────────────────┴─────────┐   ┌───────────────────────┐   │
 │  │  WorkerLoop  (single plain thread) │──▶│ PromptTemplateService │   │
 │  │  claim→build→infer→submit→loop     │   └───────────────────────┘   │
 │  └─────┬──────────────────────────────┘                              │
 │        │ chatCompletion (async future, cancellable)                  │
 │  ┌─────▼──────┐   ┌──────────────┐   ┌──────────────────────────┐    │
 │  │ LlamaClient│   │ WorkerMetrics│   │ GracefulShutdown (Lifecyc)│    │
 │  └─────┬──────┘   └──────────────┘   └──────────────────────────┘    │
 │        │  shared single java.net.http.HttpClient (per-request TO)    │
 │  Actuator: /actuator/health, /actuator/prometheus  (loopback mgmt)   │
 └────────┼─────────────────────────────────────────────────────────────┘
          │ HTTP POST /v1/chat/completions (loopback)
 ┌────────▼──────────┐
 │  llama-server      │  127.0.0.1:8000
 └────────────────────┘
```

### 3.2 Components & responsibilities

| Component | Responsibility |
|---|---|
| `WorkerApplication` | Spring Boot entrypoint; enables `@ConfigurationProperties`, scheduling. |
| `WorkerProperties` | `@ConfigurationProperties` binding of the whole config block (§4) + `@PostConstruct` fail-fast validation (mirrors Gateway's `GatewayProperties.validateOnStartup()`). |
| `HttpClientConfig` | Builds **one** `java.net.http.HttpClient` (the single HTTP client, NFR) and two thin `RestClient` wrappers over it (Gateway base-URL + long-timeout llama base-URL), with per-request timeouts. |
| `GatewayClient` | Typed calls to `/jobs/claim`, `/jobs/{id}/heartbeat`, `/jobs/{id}/result`. Adds `Authorization: Bearer`. Maps `204`→empty, `403/404`→ownership/abort, connection errors→`GatewayUnavailable`. Never logs token/diff/response bodies. |
| `LlamaClient` | Calls `POST /v1/chat/completions`; returns raw assistant text + `usage` (prompt/completion tokens). Runs via a **cancellable async future** so an abort can close the exchange mid-generation. |
| `PromptTemplateService` | Loads classpath template by `promptVersion`; builds `messages[]` + resolves `model/temperature/maxTokens`. Unknown version ⇒ signals "abandon job". Pure/mechanical (no diff analysis). |
| `WorkerLoop` | The single-threaded claim→build→infer→submit lifecycle; owns the poll cadence and per-job orchestration; drives `WorkerMetrics`. |
| `HeartbeatScheduler` | While a job is active, `scheduleAtFixedRate` a per-job heartbeat; on `shouldContinue:false`/`403`/`404` sets the abort signal and cancels the in-flight llama future. |
| `WorkerMetrics` | Registers the six spec counters/timers/gauge on the Micrometer registry (§8). |
| `GracefulShutdown` | `SmartLifecycle`: stop claiming, let a near-done generation finish within the bounded grace, else abandon (→Gateway reclaim). |

### 3.3 Tech stack (all open-source, matches approved table)

- **Java 21 LTS** (GPLv2+CPE via any OpenJDK build).
- **Spring Boot 3.5.x** parent — pin to **3.5.16** to match the Gateway (Apache-2.0). Rationale: identical CVE posture as the security-verified Gateway; no version drift for the appsec/SAST stage to re-triage.
- **spring-boot-starter-web** (Tomcat) — needed to serve `/actuator/prometheus` + `/actuator/health`; also transitively provides `RestClient` (spring-web). *No `spring-boot-starter-security`* — the Worker is an HTTP *client*, not an authenticated server; management endpoints bind to loopback (§9).
- **spring-boot-starter-actuator** + **micrometer-registry-prometheus** (Apache-2.0).
- **spring-boot-starter-validation** (bean validation on `WorkerProperties`).
- Jackson (already transitive) for DTO (JSON) mapping.
- **JUnit 5 + Mockito** (EPL-2.0 / MIT); **okhttp `mockwebserver`** or **WireMock** for integration (Apache-2.0) — **no Docker/Testcontainers** on this machine.
- Build: **Maven**, `spring-boot-maven-plugin` → executable fat JAR. Optional multi-stage Dockerfile (JRE 21 slim).

---

## 4. Configuration

### 4.1 `application.yml` (spec block + required additions)

```yaml
gateway:
  url:    ${GATEWAY_URL}            # https:// enforced at startup
  apiKey: ${WORKER_TOKEN}           # Gateway's shared WORKER bearer token (>=32 chars)
worker:
  id:      ${WORKER_ID}             # self-chosen, reused on every heartbeat/result for a job
  version: ${WORKER_VERSION:1.0.0}  # local logging/metric tag only (not sent to Gateway)
backend:
  id:      ${BACKEND_ID}            # backend *name* as registered in `backends`; sent as claim.backendId
llama:
  url:         ${LLAMA_URL:http://127.0.0.1:8000}
  model:       ${LLAMA_MODEL}       # NOT provided by Gateway (D3) — required
  temperature: ${LLAMA_TEMPERATURE:0.1}
  maxTokens:   ${LLAMA_MAX_TOKENS:4096}
network:
  pollIntervalMs:    ${POLL_INTERVAL_MS:3000}
  requestTimeoutSec: ${REQUEST_TIMEOUT_SEC:1800}   # llama read timeout (long-running LLM)
  gatewayTimeoutSec: ${GATEWAY_TIMEOUT_SEC:10}     # short timeout for Gateway calls
heartbeat:
  intervalSec: ${HEARTBEAT_INTERVAL_SEC:60}        # D7: 60 (not 30); must be << 180s staleness
prompt:
  location: classpath:prompts/                     # <promptVersion>.yml templates

spring:
  main.banner-mode: off
  lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_GRACE:120s}   # bounded (§9)
server.shutdown: graceful
management:
  server.address: 127.0.0.1                        # actuator on loopback only
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
```

### 4.2 Startup validation rules (`WorkerProperties.@PostConstruct`, fail-fast, secret-safe messages)

- `gateway.url` non-blank and **starts with `https://`** (dev-only `http://localhost` allowed behind an explicit `worker.allow-insecure-gateway=true` flag).
- `gateway.apiKey` non-blank, **≥ 32 chars** (matches Gateway token entropy); **never echoed** in the error.
- `worker.id`, `backend.id`, `llama.model` non-blank; `llama.url` a valid `http/https` URL.
- `pollIntervalMs > 0`; `requestTimeoutSec > 0`; `0 < heartbeat.intervalSec < 180` (hard-reject ≥ staleness timeout so a misconfig can't cause self-eviction) and warn if `> 90`.
- Fail with `IllegalStateException(propertyName + reason)` — property name only, never the value.

---

## 5. Threading model (plain threads, not virtual — per approved table)

- **1 job-loop thread** (`new Thread(...)`, named `worker-loop`, started by an `ApplicationRunner`). It is the only thing that claims jobs and calls llama. Single-threaded ⇒ no concurrency control needed for job state; capacity=1 respected structurally.
- **1 heartbeat thread** (`ScheduledExecutorService`, single-thread, named `worker-heartbeat`). Active only for the duration of a job: `scheduleAtFixedRate(intervalSec)`.
- **1 llama-call future**: the blocking llama HTTP call is issued via the shared JDK `HttpClient.sendAsync(...)`, returning a `CompletableFuture`. The job-loop thread blocks on `future.get(requestTimeoutSec)`.

**How `shouldContinue:false` interrupts an in-flight llama call:** the heartbeat thread, on `shouldContinue:false` / `403` / `404`, sets a `volatile boolean aborted` and calls `llamaFuture.cancel(true)`. Cancelling the JDK `HttpClient` async exchange closes the underlying connection, so the generation is torn down promptly instead of waiting out the 30-min read timeout. The job-loop thread observes the cancellation (`CancellationException`) → treats the job as abandoned (no result submitted), stops the heartbeat, and returns to polling. This is the same code path as an admin cancel / obsolete supersede.

---

## 6. Job lifecycle (WorkerLoop pseudo-flow)

```
loop while !shuttingDown:
  resp = gateway.claim(backendId, workerId)
  if 204:                      sleep(pollIntervalMs); continue
  job = resp                   # {jobId, reviewId, payload{diff, promptVersion}}
  metrics.jobsTotal++
  try:
    template = prompt.resolve(job.promptVersion)      # unknown ⇒ throw AbandonJob
    messages = template.build(job.diff)
    heartbeat.start(job.jobId, workerId)              # ~60s cadence, sets abort on stop
    llamaFuture = llama.chatAsync(messages, model, temp, maxTokens)
    llamaResp   = timedAwait(llamaFuture, requestTimeoutSec)   # metrics.llamaDuration timer
    if aborted:                metrics: -; continue          # D6: submit nothing
    gateway.submitResultWithRedelivery(job.jobId, workerId, llamaResp)  # §7 idempotent
    metrics.jobsCompleted++
  catch LlamaError | Timeout | AbandonJob:
    metrics.jobsFailed++                              # D6: no /failed call — abandon
  catch GatewayUnavailable during claim:
    metrics.gatewayErrors++; backoff()                # keep running, never exit
  finally:
    heartbeat.stop(job.jobId)
```

---

## 7. Error taxonomy

| Failure | Detection | Worker action (given frozen API) |
|---|---|---|
| **llama read timeout** (`> requestTimeoutSec`) | `future.get` timeout | Cancel future; **abandon** (no result). `jobs_failed_total++`. Gateway reclaims after 180 s. |
| **llama 5xx / malformed body** | HTTP status / parse error | **Abandon** (D6). Do **not** submit a synthetic `rawResponse`. |
| **llama connection refused / down** | connect exception | **Abandon** (D6). Optionally, a cheap pre-claim `GET {llama}/health` avoids claiming while llama is down (backend will also flip `SUSPECT` in the Gateway). |
| **Gateway `shouldContinue:false` / 403 / 404** on heartbeat | heartbeat response | Set abort, cancel llama future, **abandon**, next poll. |
| **Gateway unavailable during claim/heartbeat** | connect/5xx | **Keep running**, `gateway_errors_total++`, exponential backoff (cap ~30–60 s), retry forever. **Never exit the process.** |
| **Gateway unavailable during result submission** | connect/5xx on `/jobs/{id}/result` | **Redeliver the already-computed result** with capped backoff until `200`/`403`/`404`. This is *transport* redelivery of an idempotent, already-produced artifact — **not** business retry (no re-inference), which stays forbidden. Result held **in memory only** (no temp files, NFR); if the process dies first, the Gateway reclaims via heartbeat timeout. |
| **Unknown `promptVersion`** | `PromptTemplateService` | **Abandon** + log; never guess a template. |

Backend-capacity note: an abandoned job stays `RUNNING` (holding the backend's single capacity slot) until the Gateway's ~180 s sweep. The Worker will get `204` from `/jobs/claim` during that window — expected and benign at this scale.

---

## 8. Metrics (Micrometer → exact Prometheus names)

Register on the auto-configured `PrometheusMeterRegistry`. Micrometer lowercases, replaces `.`→`_`, and appends `_total` to counters, so declare dotted meter names that render to the spec's exact strings:

| Micrometer meter (type) | Rendered Prometheus name (spec) |
|---|---|
| `Counter "worker.jobs"` | `worker_jobs_total` |
| `Counter "worker.jobs.completed"` | `worker_jobs_completed_total` |
| `Counter "worker.jobs.failed"` | `worker_jobs_failed_total` |
| `Timer "worker.llama.duration"` (base unit s) | `worker_llama_duration_seconds{_count,_sum,_max}` |
| `Counter "worker.gateway.errors"` | `worker_gateway_errors_total` |
| `Gauge "worker.uptime"` (seconds since start) | `worker_uptime_seconds` |

**Path (D8):** default `/actuator/prometheus` (approved doc). If the scrape config requires the spec's bare `GET /metrics`, add without code change:
`management.endpoints.web.base-path: /` + `management.endpoints.web.path-mapping.prometheus: metrics` → served at `/metrics`. Recommend keeping `/actuator/prometheus` and documenting the alias for ops.

No token/`workerId`/`jobId`-as-label cardinality: keep meters label-free (single Worker per process) to bound memory.

---

## 9. Graceful shutdown

`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` bound the grace window. `GracefulShutdown` (`SmartLifecycle`):
1. Set `shuttingDown=true` → loop stops claiming new jobs.
2. If a job is in-flight, wait up to the bounded grace (default **120 s**) for the current generation to finish and submit its result.
3. If it won't finish in time, **abandon** (do not block indefinitely — an LLM job can run tens of minutes, far beyond any sane shutdown window). The Gateway reclaims it via heartbeat timeout. **Honest note:** "finish the current LLM generation on shutdown" from the spec is only achievable for near-done generations; long ones are abandoned by design.

Management endpoints bind to `127.0.0.1` (`management.server.address`) so the absence of `spring-boot-starter-security` is not an exposure — `/actuator/prometheus` and `/actuator/health` are reachable only from the local host / a co-located Prometheus sidecar, and network ACLs cover the rest.

---

## 10. NFR reality check (honest)

- **Startup < 2 s:** Achievable. Minimal web + actuator + micrometer-prometheus, **no security starter**, `banner-mode: off`. Realistic cold start **~1.2–1.8 s** on Mac-mini-class hardware; enable **JDK 21 AppCDS/AOT cache** and `-XX:TieredStopAtLevel=1` for headroom, optionally `spring.main.lazy-initialization: true`. Meets the target on the intended hardware.
- **RAM < 100 MB: likely NOT met with full Spring Boot + Tomcat — FLAG.** Realistic RSS is **~130–180 MB**. Aggressive tuning (`-Xmx48m -Xss512k -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m`, AppCDS, optionally Undertow instead of Tomcat) brings it to **~110–140 MB** but almost certainly not under 100 MB. Because the **stack is user-approved (Spring Boot stays)**, this gap is documented for acceptance rather than resolved by swapping frameworks. If a hard <100 MB is mandatory, the only real levers are GraalVM native-image (contradicts "fat JAR", complicates RestClient reflection/appsec re-review) or a non-Spring micro-runtime (contradicts the approved table) — recommend the user **accept ~130 MB** at this scale.
- Single process ✔ · single HTTP client ✔ (one shared `java.net.http.HttpClient`) · no DB/ORM ✔ · no temp files ✔ (result held in memory) · no GPU ✔.

---

## 11. Package structure & class inventory

```
worker/
  pom.xml                       # Spring Boot 3.5.16 parent, standalone (not a root module)
  src/main/java/com/review/worker/
    WorkerApplication.java
    config/
      WorkerProperties.java        # @ConfigurationProperties + @PostConstruct validation
      HttpClientConfig.java        # single HttpClient + gatewayRestClient + llamaRestClient beans
      MetricsConfig.java           # WorkerMetrics bean, uptime gauge binding
    gateway/
      GatewayClient.java
      dto/ ClaimRequest, ClaimResponse, JobPayload,
           HeartbeatRequest, HeartbeatResponse,
           ResultRequest, ResultResponse            # mirror Gateway DTO field names EXACTLY
    llama/
      LlamaClient.java
      dto/ ChatCompletionRequest, ChatMessage, ChatCompletionResponse, Choice, Usage
    prompt/
      PromptTemplateService.java
      PromptTemplate.java          # record: system/user text, model/temp/maxTokens overrides
    core/
      WorkerLoop.java              # the single job-loop thread
      HeartbeatScheduler.java
      AbortSignal.java             # volatile flag + future handle
      LlamaResult.java             # rawResponse + tokens + durationMs + model
    lifecycle/
      WorkerRunner.java            # ApplicationRunner: starts worker-loop thread
      GracefulShutdown.java        # SmartLifecycle
    metrics/ WorkerMetrics.java
    error/  LlamaException, GatewayUnavailableException, AbandonJobException
  src/main/resources/
    application.yml
    prompts/v1.yml                 # promptVersion "v1" template (co-owned artifact, §0.3)
    logback-spring.xml             # SLF4J→stdout, no content of diff/response logged
  src/test/java/com/review/worker/ ...            # §12
```

**DTO fidelity is load-bearing** — dev must mirror the Gateway field names verbatim (from the read source):
- `ClaimRequest(String backendId, String workerId)` → `ClaimResponse(long jobId, long reviewId, JobPayload payload)`, `JobPayload(String diff, String promptVersion)`.
- `HeartbeatRequest(String workerId)` → `HeartbeatResponse(boolean shouldContinue)`.
- `ResultRequest(String workerId, String rawResponse, Integer promptTokens, Integer completionTokens, Long durationMs, String model)` → `ResultResponse(long reviewId, String status)`.

---

## 12. Test strategy (JUnit 5 + Mockito; no Docker)

**Unit (Mockito / `MockRestServiceServer`):**
- `WorkerProperties` validation matrix (missing token, http URL, `heartbeat ≥ 180`, blank model).
- `PromptTemplateService`: known version fills `{{DIFF}}`; unknown version → `AbandonJobException`; override precedence (template vs global config).
- `GatewayClient` mapping via **`MockRestServiceServer`** (Spring 6.1 supports it for `RestClient`): `204`→empty, `403/404`, `200` shapes, bearer header present, secrets absent from any logged line.
- `LlamaClient`: parses `choices[0].message.content` + `usage`; 5xx → `LlamaException`.
- `HeartbeatScheduler`: `shouldContinue:false` sets abort + cancels future; `403/404` same.

**Integration (`MockWebServer`/WireMock over real sockets — validates timeouts & cancellation):**
- Happy path: claim → llama → result `200`.
- `204` poll loop honors `pollIntervalMs`.
- `shouldContinue:false` mid-generation cancels the llama exchange, submits **no** result.
- llama read-timeout → abandon, `jobs_failed_total++`.
- Gateway down during result → **idempotent redelivery** until `200`.
- Gateway down during claim → keeps running, backs off, `gateway_errors_total++`, never exits.
- `/actuator/prometheus` renders the six exact metric names.

---

## 13. Security notes (for the appsec/threat-model stage)

- **`WORKER_TOKEN`**: env-only (`gateway.apiKey`←`WORKER_TOKEN`), ≥32 chars enforced at startup; sent only as `Authorization: Bearer` to the Gateway over **HTTPS** (startup-enforced `https://`). Default JDK truststore for cert validation; support a custom truststore for an internal CA. Token **never** logged, never a metric label, never in exception messages (validation echoes property *name* only). Reuses the Gateway's proven pattern.
- **Transport**: Worker→Gateway HTTPS mandatory; Worker→llama loopback HTTP acceptable (no secret, local only). Actuator on `127.0.0.1`.
- **Data sensitivity**: `diff` (proprietary source) and `rawResponse` (model output) are held **in memory only**, never written to disk/temp (NFR + confidentiality). Logs record technical events + `jobId`/`reviewId` and **sizes**, never diff/response content. Aligns with the Gateway threat model's treatment of `review_inputs.diff`/`review_results.raw_response`.
- **Memory-safety / DoS**: cap the assembled prompt and the llama response length (truncate to stay under the Gateway's `/jobs/{id}/result` 500 KB body limit and the Worker's memory budget) to prevent OOM from a runaway generation.
- **Least authority**: Worker holds **no** GitLab/PostgreSQL/admin credentials — only `WORKER_TOKEN`. It cannot mutate review state except through the three worker-scoped endpoints (SR-04 ownership enforced Gateway-side by `workerId`).
- **Self-declared `workerId`** under the shared `WORKER_TOKEN` is a *Gateway-side* accepted residual (README §11); nothing new for the Worker to fix, but note it in the threat model as inherited.

---

## 14. Feature decomposition for the dev pipeline

**Branch 1 — `feature/02-worker-scaffold-clients` (independently testable):**
pom.xml (SB 3.5.16 standalone), `WorkerApplication`, `WorkerProperties`+validation, `application.yml`, `HttpClientConfig` (single shared client + two RestClients), `GatewayClient`+DTOs, `LlamaClient`+DTOs, `PromptTemplateService`+`prompts/v1.yml`, `WorkerMetrics`, actuator `/health`+`/prometheus`. Tests: all unit tests in §12 (each client testable in isolation with `MockRestServiceServer`/MockWebServer — no live loop). **Gate:** clients + config + template + metrics green.

**Branch 2 — `feature/02-worker-loop-lifecycle` (independently testable):**
`WorkerLoop`, `HeartbeatScheduler`, `AbortSignal`, cancellable llama future, error taxonomy (abandon-on-llama-failure D6, idempotent result redelivery, gateway-down resilience), `WorkerRunner`, `GracefulShutdown`. Tests: all integration scenarios in §12. **Gate:** full claim→infer→result and every failure/abort/redelivery path green.

**Branch 3 (optional) — `feature/02-worker-packaging`:**
launchd `.plist`, optional multi-stage Dockerfile (JRE 21 slim), AppCDS/JVM-flags tuning for startup/RAM, `/metrics` path alias, README + runbook, final metric-name assertion test. Fold into Branch 2 if the pipeline prefers two branches.

---

## 15. Risks & trade-offs

- **D6 abandon-on-failure** adds up to ~180 s reclaim latency and holds backend capacity meanwhile — acceptable at 20–30 MR/day; the only alternative is reopening the frozen Gateway (Option B).
- **D4 prompt template on the Worker** is a real ownership deviation, mitigated contractually (§0.3) but the user should confirm the template governance model.
- **<100 MB RAM NFR is unlikely to be met** (~130–180 MB realistic) with the approved Spring Boot + Tomcat stack — documented for acceptance, not silently ignored.
- Single-threaded loop caps throughput at one job/Worker — matches `parallel:1`; horizontal scale is "more Worker+backend hosts," exactly the platform's intended growth axis.
