# LLM Worker

The LLM Worker is a stateless transport agent that sits between the Review Gateway's job queue and one
local `llama-server` instance. This document is a deployment and integration guide, written the same way
as the root [`README.md`](../README.md): everything described here reflects what is actually implemented
in `worker/` — `WorkerProperties`, `application.yml`, `WorkerLoop`/`HeartbeatScheduler`, the SAST report
under `docs/security/worker-sast-report.md`, and `docs/worker-architecture.md`. Where the code leaves
something unimplemented, or the original spec described something this codebase does not do, this
document says so explicitly instead of describing aspirational behavior.

## Table of contents

1. [Overview](#1-overview)
2. [How it works](#2-how-it-works)
3. [Requirements](#3-requirements)
4. [Build](#4-build)
5. [Configuration reference](#5-configuration-reference)
6. [Deployment](#6-deployment)
7. [Integration scheme](#7-integration-scheme)
8. [Observability](#8-observability)
9. [Operational notes](#9-operational-notes)
10. [Adding a prompt version](#10-adding-a-prompt-version)

---

## 1. Overview

The Worker is a maximally lightweight, **stateless** bridge co-located 1:1 with a `llama-server`
instance: it claims one job from the Gateway, calls its local `llama-server`, and submits the raw result
back. It owns **no** business logic, **no** persistent state, and **no** GitLab or PostgreSQL access —
all queue/retry/dedup/timeout/routing/publish logic lives in the Gateway (see the root README's
[§9 Worker protocol](../README.md#9-worker-protocol) for the Gateway-side view of this same contract).

- **1:1 pairing with one backend.** A Worker process is configured with exactly one `gateway.url` and
  one `llama.url`; it claims jobs only for the single `backend.id` it is configured with
  (`ClaimRequest.backendId`). Running N `llama-server` instances means running N Worker processes, each
  with its own `backend.id`/`llama.url`.
- **No GitLab or database access at all.** The Worker holds only a single bearer token
  (`gateway.api-key`) for the Gateway's Worker-facing endpoints; it has no GitLab API credentials, no
  JDBC driver, and no knowledge of the Gateway's schema, retry counting, deduplication, or how the raw
  LLM response gets parsed into structured comments.
- **Single-threaded job processing, plain threads.** The main loop (`core.WorkerLoop`) runs on exactly
  one `Thread` named `worker-loop` — there is no thread pool and no virtual-thread usage; capacity is
  structurally 1 concurrent job per Worker process. A second, per-job plain thread
  (`ScheduledExecutorService` named `worker-heartbeat`) exists only for the duration of one job.
- **No custom REST API of its own.** The only HTTP surface the Worker itself exposes is Spring Boot
  Actuator (health + Prometheus metrics, loopback-only — see [§8](#8-observability)); it never accepts
  business requests from anything.

## 2. How it works

```
 worker-loop thread                                    worker-heartbeat thread (per job)
 ──────────────────                                    ─────────────────────────────────
      │
      ▼
 POST /jobs/claim ───────────────────────▶ Review Gateway
      │
      │ 204 No Content            ──▶ sleep(network.poll-interval-ms), loop
      │
      │ 200 OK {jobId, reviewId, payload:{diff, promptVersion}}
      ▼
 resolve prompt:
   classpath:prompts/<promptVersion>.yml
   literal {{DIFF}} substitution                       │
      │                                                 │
      │ unknown promptVersion / oversized diff          │
      │  ──▶ AbandonJobException, jobs_failed++, loop   │
      ▼                                                 │
 start heartbeat scheduler ─────────────────────────────┤
      │                                                 ▼
      ▼                                       POST /jobs/{id}/heartbeat  (every heartbeat.interval-sec)
 POST /v1/chat/completions  (async, cancellable)         │
   on llama-server                                       │ shouldContinue:false / 403 / 404
      │                                                  │  ──▶ abort signal + cancel llama future
      │ timeout / 5xx / malformed / oversize             │
      │  ──▶ LlamaException, abandon, jobs_failed++      ▼
      │                                       job aborted: submit nothing, no completed/failed metric
      │ aborted mid-flight (see left) ──────────────────▶│
      │
      ▼ success
 stop heartbeat scheduler
      │
      ▼
 POST /jobs/{id}/result  (rawResponse + tokens + durationMs + model)
      │
      │ Gateway unreachable ──▶ retry with capped backoff, in memory only, until 200/403/404
      ▼
 200/403/404 ──▶ jobs_completed++, loop back to claim
```

Step by step, matching the code in `core.WorkerLoop`/`core.HeartbeatScheduler`:

1. **Claim.** `POST /jobs/claim` with `{backendId, workerId}` (`gateway.GatewayClient.claim`). `204` means
   nothing to claim right now (empty queue, backend not `ACTIVE`, or at capacity — all indistinguishable
   by design on the Gateway side); the loop sleeps `network.poll-interval-ms` and polls again. `200`
   yields `{jobId, reviewId, payload:{diff, promptVersion}}`.
2. **Resolve the prompt** (`prompt.PromptTemplateService.resolve`): `promptVersion` is checked against an
   allowlist regex (`^[A-Za-z0-9._-]{1,64}$`, plus an explicit rejection of any value containing `..`)
   *before* it is ever used to build a resource path; the matching `classpath:prompts/<promptVersion>.yml`
   is loaded, and the diff is substituted into the template's `{{DIFF}}` placeholder with a single literal
   `String.replace` (never re-parsed by any template/expression engine). An unknown `promptVersion` or an
   oversized diff (`worker.limits.max-diff-bytes`) throws `AbandonJobException` — the job is abandoned
   before llama is ever called.
3. **Start the heartbeat.** `core.HeartbeatScheduler` starts a per-job, single-thread
   `ScheduledExecutorService` that calls `POST /jobs/{id}/heartbeat` every `heartbeat.interval-sec`
   (default 60s). `shouldContinue:false`, `403`, or `404` on that call sets an abort signal and cancels
   the in-flight llama call. A heartbeat tick that itself throws is caught (never lets the scheduler die
   silently) and, after 3 consecutive failures, also aborts the job as a fail-safe.
4. **Call llama-server.** `POST /v1/chat/completions` (OpenAI Chat-Completions shape) is issued
   asynchronously on the single shared `java.net.http.HttpClient`, so the abort signal above can cancel it
   mid-generation instead of waiting out the full `network.request-timeout-sec`. A timeout, a non-2xx
   status, a malformed/empty body, or a response exceeding `worker.limits.max-response-bytes` all result
   in the job being **abandoned** — no synthetic result is ever submitted for a llama failure.
5. **Submit the result.** `POST /jobs/{id}/result` with `{workerId, rawResponse, promptTokens,
   completionTokens, durationMs, model}`. If the Gateway is unreachable, the Worker retries this call with
   capped exponential backoff (holding the already-computed result in memory only, never on disk) until
   the Gateway responds `200`/`403`/`404` — this is transport-level redelivery of an idempotent,
   already-produced result, not a re-invocation of the LLM.
6. **Loop.** Back to step 1, whether the previous job completed, was abandoned, or was aborted.

**Abandon-on-failure semantics.** The Gateway has no `POST /jobs/{id}/failed` endpoint — the Worker
never reports a llama/prompt failure to the Gateway directly. Instead it simply stops heartbeating and
submits nothing; the Gateway's own stale-heartbeat sweep (default every 30s, `~180s` staleness threshold
per the root README's [§4.2](../README.md#42-everything-else-has-a-working-default)) notices the job's
heartbeat has gone stale and requeues or fails it according to its own retry policy. This trades up to
~180s of reclaim latency (and the backend's capacity slot held meanwhile) for not needing any Gateway API
this Worker does not otherwise call.

## 3. Requirements

| Component | Version / need | Notes |
|---|---|---|
| Java | 21 | `worker/pom.xml` targets Java 21, Spring Boot 3.5.16 parent (pinned to the same line as the Gateway). |
| Maven | 3.9+ | Standard build; `spring-boot-maven-plugin` produces an executable fat JAR. |
| Network reachability to the Gateway | HTTPS (or loopback HTTP, dev-only) | See `gateway.url` in [§5](#5-configuration-reference). |
| Network reachability to a `llama-server` | HTTP (loopback by default) | See `llama.url` in [§5](#5-configuration-reference). |
| PostgreSQL | **not required** | The Worker has no JDBC dependency and no database access of any kind. |
| Docker | **not required, anywhere** | Tests use real-socket `okhttp3:mockwebserver` instances standing in for both the Gateway and `llama-server` — no Testcontainers, no external services. This machine has no Docker installed either. |

## 4. Build

```bash
export JAVA_HOME="$HOME/tools/jdk-21.0.11+10"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"

mvn -q -f worker/pom.xml verify
```

(Adjust the `JAVA_HOME`/Maven paths to wherever your JDK 21 / Maven 3.9+ are actually installed — the
paths above are this project's own local toolchain layout, not a hard requirement.)

`mvn verify` compiles, runs the full test suite (no external services needed — see [§3](#3-requirements)),
and packages the executable jar via `spring-boot-maven-plugin`. The build artifact is:

```
worker/target/llm-worker.jar
```

(`worker/target/llm-worker.jar.original` is the pre-repackage jar the Boot plugin leaves behind; it is
not the artifact to deploy.) Run it directly:

```bash
java -jar worker/target/llm-worker.jar
```

with the required environment variables from [§5.1](#51-required-no-default--startup-fails-without-them)
set.

## 5. Configuration reference

All configuration is bound by `config.WorkerProperties` from `src/main/resources/application.yml`, which
has **no common prefix** — `gateway`, `worker`, `backend`, `llama`, `network`, `heartbeat`, `prompt` are
all top-level keys. `WorkerProperties.validateOnStartup()` (a `@PostConstruct` hook) fails startup fast
on any violation below; every failure message names the property only, never its configured value.

### 5.1 Required (no default — startup fails without them)

| Env var | Property | Purpose | Validation |
|---|---|---|---|
| `GATEWAY_URL` | `gateway.url` | Review Gateway base URL. | Must be a valid URI; must use `https://` **unless** the host is loopback (`127.0.0.1`/`::1`/`localhost`) **and** `worker.allow-insecure-gateway=true` — a non-loopback host always requires `https://`, regardless of that flag. |
| `GATEWAY_API_KEY` | `gateway.api-key` | Bearer token sent as `Authorization: Bearer` on every Gateway call; must match the Gateway's own `WORKER_TOKEN`. | Must be non-blank (JSR-380 `@NotBlank`). **Note:** `docs/worker-architecture.md` §4.2 documents a "≥ 32 chars" guidance for this token (mirroring the Gateway's own token-entropy check), but the current code does not enforce a minimum length — only non-blank is checked. Operators should still issue a high-entropy value (e.g. `openssl rand -hex 32`) to match the Gateway's actual `WORKER_TOKEN` requirement, even though the Worker itself will start with a short one. |
| `WORKER_ID` | `worker.id` | Self-chosen identifier reused on every claim/heartbeat/result call for a job (`ClaimRequest.workerId`). | Must be non-blank. |
| `BACKEND_ID` | `backend.id` | The backend's registered **name** in the Gateway's `backends` table, sent as `ClaimRequest.backendId`. | Must be non-blank. |
| `LLAMA_MODEL` | `llama.model` | Model name sent to `llama-server` and reported back as `ResultRequest.model` (llama-server is not queried for this — it is Worker config, since the Gateway never supplies it). | Must be non-blank. |

### 5.2 Everything else (has a working default)

| Env var | Property | Default | Notes |
|---|---|---|---|
| `WORKER_ALLOW_INSECURE_GATEWAY` | `worker.allow-insecure-gateway` | `false` | Dev-only escape hatch — see the `GATEWAY_URL` row above; has no effect for a non-loopback `gateway.url`. |
| `WORKER_MAX_DIFF_BYTES` | `worker.limits.max-diff-bytes` | `262144` (256 KiB) | Hard cap on the claimed diff, in UTF-8 bytes; exceeding it abandons the job before any llama call. Defensive bound against a misbehaving/compromised Gateway, set generously above the Gateway's own diff-token budget. |
| `WORKER_MAX_RESPONSE_BYTES` | `worker.limits.max-response-bytes` | `200000` | Hard cap on the llama response body, enforced **mid-stream** (never buffers past the cap); exceeding it abandons the job. Matches the Gateway's own documented "normal" raw-response ceiling. |
| `LLAMA_URL` | `llama.url` | `http://127.0.0.1:8000` | Must be a valid `http`/`https` URI. A non-loopback host only logs a WARN at startup (does not fail) unless `llama.allow-non-loopback` is also set — the llama socket is unauthenticated by design. |
| `LLAMA_ALLOW_NON_LOOPBACK` | `llama.allow-non-loopback` | `false` | Suppresses the non-loopback `llama.url` startup warning; never fail-fast either way (this is a SHOULD, not a MUST). |
| `LLAMA_TEMPERATURE` | `llama.temperature` | `0.1` | Sampling temperature sent to llama-server (unless overridden by the prompt template — [§10](#10-adding-a-prompt-version)). |
| `LLAMA_MAX_TOKENS` | `llama.max-tokens` | `4096` | `max_tokens` sent to llama-server (unless overridden by the prompt template). Must be `> 0`. |
| `WORKER_POLL_INTERVAL_MS` | `network.poll-interval-ms` | `3000` | Sleep between `POST /jobs/claim` polls after a `204`. Must be `> 0`. |
| `WORKER_REQUEST_TIMEOUT_SEC` | `network.request-timeout-sec` | `1800` (30 min) | Bound on the whole llama chat-completion call (a single LLM generation can legitimately take tens of minutes). Must be `> 0`. |
| `WORKER_GATEWAY_TIMEOUT_SEC` | `network.gateway-timeout-sec` | `10` | Read timeout for every Gateway call (claim/heartbeat/result) — deliberately short; Gateway calls must never block as long as an LLM completion. Must be `> 0`. |
| `WORKER_HEARTBEAT_INTERVAL_SEC` | `heartbeat.interval-sec` | `60` | Cadence of `POST /jobs/{id}/heartbeat` while a job is running. **Hard-rejected at startup if `>= 180`** (the Gateway's stale-heartbeat threshold — a misconfiguration here could not otherwise cause every job to self-evict); **logs a WARN if `> 90`** (little margin left). Must also be `> 0`. |
| `WORKER_HTTP_PORT` | `server.port` | `8081` | Port for the embedded server, which hosts **only** Actuator (see [§8](#8-observability)) — the Worker has no business REST endpoints. |

### 5.3 Hardcoded in `application.yml` (no environment-variable placeholder)

These are not `${VAR}`-templated in the shipped `application.yml`; changing them means editing/rebuilding
or supplying an external Spring property source (e.g. a mounted `application.yml`, `-D` system
properties, or `SPRING_APPLICATION_JSON`), not just setting an environment variable:

| Property | Value | Notes |
|---|---|---|
| `worker.version` | `1.0.0` | Bound and validated, but **not currently read anywhere else in the code** (no log line, metric, or Gateway call uses it) — informational/reserved only. |
| `prompt.location` | `classpath:prompts/` | **Must start with `classpath:`** — startup fails otherwise. Templates ship only inside the fat JAR; there is no supported way to point this at an external/operator-writable directory (see [§10](#10-adding-a-prompt-version)). |
| `server.address` | `127.0.0.1` | The whole embedded server (and therefore Actuator, since no distinct `management.server.port` is configured) binds to loopback only. `WorkerProperties.validateServerBinding()` fails startup fast if the address that is *actually* effective for Actuator resolves to anything but loopback — see the inline comment in `application.yml` for why `server.address` (not `management.server.address`) is the property that matters here. |
| `management.endpoints.web.exposure.include` | `health,prometheus` | No other Actuator endpoint (`env`, `heapdump`, `beans`, etc.) is exposed. |
| `management.endpoint.health.probes.enabled` | `true` | Exposes `/actuator/health/liveness` and `/actuator/health/readiness` groups in addition to `/actuator/health`. |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | The graceful-shutdown grace period — see [§9](#9-operational-notes). |

### 5.4 Example minimal launch

```bash
export GATEWAY_URL="https://review-gateway.internal"
export GATEWAY_API_KEY="<the Gateway's WORKER_TOKEN value>"
export WORKER_ID="worker-mac-mini-01"
export BACKEND_ID="mac-mini-01"
export LLAMA_MODEL="qwen2.5-coder"
# LLAMA_URL defaults to http://127.0.0.1:8000; override only if llama-server listens elsewhere.

java -jar worker/target/llm-worker.jar
```

## 6. Deployment

No `Dockerfile`/`docker-compose.yml` and no launchd `.plist`/systemd unit ship in this repository —
`worker/` contains only the Maven project. The original spec doc (`LLM Worker (Executor)_ prompt.md`)
calls for launchd deployment on a Mac mini as the primary target, with Docker mentioned as optional; the
examples below follow that model but are illustrative configuration for an operator to adapt, not files
present in this repository.

### 6.1 systemd (Linux)

```ini
# /etc/systemd/system/llm-worker.service
[Unit]
Description=LLM Worker (Review Gateway)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=llm-worker
Group=llm-worker
EnvironmentFile=/etc/llm-worker/llm-worker.env
ExecStart=/usr/bin/java -XX:-HeapDumpOnOutOfMemoryError -jar /opt/llm-worker/llm-worker.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`/etc/llm-worker/llm-worker.env` holds the variables from [§5.4](#54-example-minimal-launch) (file
permissions restricted to the `llm-worker` user, since `GATEWAY_API_KEY` is a bearer secret).
`-XX:-HeapDumpOnOutOfMemoryError` is the JVM flag `application.yml`'s own comment recommends (an OOM heap
dump could otherwise persist an in-flight diff/LLM response to disk in plaintext) — the Worker only
*checks and warns* if this flag is missing at startup; it cannot enforce it from inside the JVM.

### 6.2 launchd (macOS / Mac mini)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.review.llm-worker</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/java</string>
        <string>-XX:-HeapDumpOnOutOfMemoryError</string>
        <string>-jar</string>
        <string>/opt/llm-worker/llm-worker.jar</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>GATEWAY_URL</key>
        <string>https://review-gateway.internal</string>
        <key>GATEWAY_API_KEY</key>
        <string>REPLACE_ME</string>
        <key>WORKER_ID</key>
        <string>worker-mac-mini-01</string>
        <key>BACKEND_ID</key>
        <string>mac-mini-01</string>
        <key>LLAMA_MODEL</key>
        <string>qwen2.5-coder</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/var/log/llm-worker/stdout.log</string>
    <key>StandardErrorPath</key>
    <string>/var/log/llm-worker/stderr.log</string>
</dict>
</plist>
```

Load it with `launchctl load /Library/LaunchDaemons/com.review.llm-worker.plist`. As with the systemd
unit, run this under a dedicated non-root user and restrict the plist's file permissions, since it embeds
`GATEWAY_API_KEY` in plaintext.

### 6.3 Containerization

No `Dockerfile` exists in this repository (neither at the root nor under `worker/`). Building one
(multi-stage, JRE 21 slim, as the original prompt doc suggests as an *optional* deployment path) is not
something this codebase currently provides — do not assume a container image exists to pull.

## 7. Integration scheme

Slotting a Worker into an existing Review Gateway deployment:

1. **Register a backend in the Gateway.** There is no REST endpoint for this (per the root README's
   [§5 Deployment](../README.md#5-deployment)) — insert a row directly into the Gateway's `backends`
   table:

   ```sql
   INSERT INTO backends (name, url, model, capacity)
   VALUES ('mac-mini-01', 'http://192.168.1.50:8080', 'qwen2.5-coder', 1);
   ```

   The `name` here (`mac-mini-01`) is exactly the value this Worker must be configured with as
   `BACKEND_ID`/`backend.id` — the field is misleadingly called `backendId` in the wire protocol, but it
   carries the backend's **name**, not its numeric database id. Confirm registration with
   `GET /backends` (ADMIN token) on the Gateway.
2. **Issue/confirm the Worker bearer token** on the Gateway side — it is the Gateway's own
   `WORKER_TOKEN` environment variable (see the root README's
   [§4.1](../README.md#41-required-secrets-no-default--startup-fails-without-them)); the same value goes
   into this Worker's `GATEWAY_API_KEY`.
3. **Point the Worker at the Gateway and at its own `llama-server`** via `GATEWAY_URL` and `LLAMA_URL`
   ([§5](#5-configuration-reference)).
4. **Scale by adding more Worker processes, one per `llama-server` host.** Each additional backend gets
   its own `backends` row (a distinct `name`) and its own Worker process configured with that
   `BACKEND_ID` and that host's `LLAMA_URL` — there is no multi-backend or multi-model support within a
   single Worker process (§1's 1:1 pairing).

### 7.1 Troubleshooting curl examples

The three Gateway endpoints this Worker calls, useful for testing a Gateway/Worker pairing by hand
(`$WORKER_TOKEN` below is the Gateway's configured token, i.e. this Worker's `GATEWAY_API_KEY`):

```bash
# 1. Claim
curl -s -X POST "$GATEWAY_URL/jobs/claim" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "backendId": "mac-mini-01", "workerId": "worker-mac-mini-01" }'
# 200 -> {"jobId":456,"reviewId":123,"payload":{"diff":"...","promptVersion":"v1"}}
# 204 -> nothing to claim (empty body)

# 2. Heartbeat (use the jobId and the same workerId from the claim above)
curl -s -X POST "$GATEWAY_URL/jobs/456/heartbeat" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "workerId": "worker-mac-mini-01" }'
# 200 -> {"shouldContinue":true}   (false means: stop generating, abandon the job)
# 404/403 -> unknown job / not this job's owner

# 3. Result
curl -s -X POST "$GATEWAY_URL/jobs/456/result" \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "workerId": "worker-mac-mini-01",
        "rawResponse": "[{\"file\":\"Foo.java\",\"line\":42,\"severity\":\"major\",\"comment\":\"...\"}]",
        "promptTokens": 3200,
        "completionTokens": 180,
        "durationMs": 45000,
        "model": "qwen2.5-coder"
      }'
# 200 -> {"reviewId":123,"status":"COMPLETED"}  (idempotent: safe to resend the exact same body)
```

## 8. Observability

Spring Boot Actuator is exposed on the loopback-bound embedded server only
(`server.address: 127.0.0.1` — see [§5.3](#53-hardcoded-in-applicationyml-no-environment-variable-placeholder)),
at the default `/actuator` base path:

- `GET /actuator/health` (and, since health probes are enabled, `/actuator/health/liveness` /
  `/actuator/health/readiness`).
- `GET /actuator/prometheus` — the six `worker_*` metrics below, in Prometheus exposition format.

### 8.1 Metrics (`metrics.WorkerMetrics`)

| Prometheus name | Micrometer type | Meaning |
|---|---|---|
| `worker_jobs_total` | Counter | Incremented once per job successfully claimed (`POST /jobs/claim` returned `200`), before any inference is attempted. |
| `worker_jobs_completed_total` | Counter | Incremented only once the Gateway has actually acknowledged the result (`POST /jobs/{id}/result` returned `200`/`403`/`404` — all three are terminal, idempotent-acknowledged outcomes from the Worker's perspective). An interrupted/abandoned result redelivery (e.g. shutdown grace period elapsing mid-retry) does **not** increment this. |
| `worker_jobs_failed_total` | Counter | Incremented when a job is abandoned: unknown `promptVersion`, oversized diff, any llama failure (timeout/5xx/malformed/oversize), or a result redelivery that was interrupted/abandoned before the Gateway ever acknowledged it. |
| `worker_llama_duration_seconds_count` / `_sum` | Timer | Latency of successful llama-server chat-completion calls only (a call that fails before a result is parsed is not recorded here). |
| `worker_gateway_errors_total` | Counter | Incremented every time a Gateway call (claim, or result-submission during redelivery) fails with a connection error or `5xx`, i.e. every `GatewayUnavailableException`. Heartbeat failures are **not** counted here (see the log-based signal below instead). |
| `worker_uptime_seconds` | Gauge | Seconds since the `WorkerMetrics` bean was constructed (process start, effectively). |

A job that is aborted mid-flight because the Gateway said `shouldContinue:false`/`403`/`404` on a
heartbeat increments **neither** `worker_jobs_completed_total` nor `worker_jobs_failed_total` — it is
neither a Worker-side success nor a Worker-side failure; the outcome is tracked entirely by the Gateway
(the Review already moved to a state where this job's result would be discarded anyway).

### 8.2 Log patterns worth alerting on

The Worker logs to stdout only (`logback-spring.xml`, no file appender — nothing this process logs is
meant to persist on disk), and never logs the bearer token, the diff, or the raw LLM response content —
only ids, statuses, and sizes. Notable lines (all from `core.WorkerLoop`/`core.HeartbeatScheduler` unless
noted):

| Log line (abbreviated) | Level | Meaning |
|---|---|---|
| `Gateway unavailable while claiming a job; backing off {N} ms` | WARN | Gateway is unreachable/erroring on claim; the loop is backing off (capped at 60s) and will keep retrying — not itself an outage requiring restart, but worth alerting if sustained. |
| `Gateway unavailable while submitting result; retrying in {N} ms` | WARN | Same, but for an already-computed result stuck in redelivery — a sustained run of these means completed work is not reaching the Gateway. |
| `Job abandoned (jobId=…): …` | WARN | A job was abandoned (prompt/llama failure) — expected occasionally, worth alerting on a sustained rate (points at a broken `llama-server` or a bad prompt template). |
| `Result redelivery abandoned before the Gateway ever acknowledged it; counting job as failed` | WARN | A result redelivery was interrupted (typically the shutdown grace period elapsing) before the Gateway confirmed receipt. |
| `Heartbeat tick failed ({N} consecutive) (jobId=…)` | WARN | A single heartbeat attempt failed (e.g. transient Gateway error); the scheduler keeps running. |
| `Heartbeat failed {N} times in a row (jobId=…); aborting fail-safe rather than running blind` | ERROR | Fail-safe abort after 3 consecutive heartbeat failures (`WSR-15`) — the Gateway may believe this job is still running while the Worker has actually given up on it; the Gateway's own heartbeat-timeout sweep will eventually reclaim it. |
| `llama.url host is not loopback … confirm this is intentional` | WARN (startup only) | Non-default, non-loopback `llama.url` without `LLAMA_ALLOW_NON_LOOPBACK` — confirm this is intentional for the deployment. |
| `JVM flag -XX:+HeapDumpOnOutOfMemoryError is enabled` | WARN (startup only) | The recommended `-XX:-HeapDumpOnOutOfMemoryError` launch flag is missing — see [§6](#6-deployment). |

## 9. Operational notes

- **Graceful shutdown.** `lifecycle.GracefulShutdown` (`SmartLifecycle`) stops the loop from claiming any
  *new* job immediately, then waits up to `spring.lifecycle.timeout-per-shutdown-phase` (default `30s`)
  for the current job to finish naturally (llama completes, result gets submitted). If that window
  elapses with a job still running, the job is **force-abandoned**: the in-flight llama call is
  cancelled, and the loop thread is interrupted (which also stops a result-redelivery retry loop that was
  stuck backing off against an unreachable Gateway). Either way, process shutdown itself is bounded — it
  never blocks indefinitely on an LLM generation, which can legitimately run tens of minutes.
- **Gateway outage.** Claim and result-submission calls both retry with capped exponential backoff
  (starting at `network.poll-interval-ms`, doubling, capped at 60s) on connection failure/`5xx`,
  incrementing `worker_gateway_errors_total` each time. The process **never exits** on a Gateway outage —
  it keeps retrying indefinitely.
- **llama-server failure** (timeout, 5xx, malformed body, connection refused, oversized response). The
  job is abandoned — no result is ever submitted for it. The Worker itself does not report this failure
  to the Gateway (there is no `/failed` endpoint to call); the Gateway's own stale-heartbeat sweep
  reclaims the stuck `RUNNING` job once its heartbeat goes stale (see the root README's heartbeat-timeout
  default, `~180s`) and requeues or fails it according to the Gateway's own retry policy.
- **Cancelled/superseded review.** The next heartbeat after an admin cancel (`DELETE /reviews/{id}`) or a
  new push superseding the review (`OBSOLETE`) gets `shouldContinue:false` from the Gateway; the Worker
  aborts the in-flight llama call immediately (rather than waiting for it to finish) and moves on to the
  next claim — no result is submitted, and neither the completed nor the failed metric is incremented for
  that job.

## 10. Adding a prompt version

Templates are resolved **only** from the classpath (`prompt.PromptTemplateService`, backed by
`prompt.location: classpath:prompts/`) — there is no supported way to load one from an external,
operator-writable directory at runtime. To add a new `promptVersion`:

1. Add a file at `worker/src/main/resources/prompts/<name>.yml` and rebuild the fat JAR — the file must
   be baked into the jar at build time.
2. `<name>` must match the allowlist regex `^[A-Za-z0-9._-]{1,64}$` and must not contain the literal
   substring `..` (checked *before* the file is resolved — this is what a Gateway-supplied `promptVersion`
   is validated against on every claimed job; an unmatched value abandons the job rather than falling back
   to any default template).
3. Template format (see the shipped `prompts/v1.yml` for a complete example):

   ```yaml
   system: >
     Optional system-role instructions for the model.
   user: |
     Required user-role instructions. Must contain the literal placeholder {{DIFF}} exactly once,
     which is substituted with the diff text via a single literal String.replace — never re-parsed
     as a template or expression, so it is safe for a diff to contain arbitrary text (including
     things that look like template syntax).
   # Optional overrides; if omitted, the Worker falls back to llama.model/llama.temperature/llama.max-tokens.
   model: some-model-name
   temperature: 0.2
   maxTokens: 2048
   ```

   `user` is required (a template missing it, or that isn't a YAML mapping at all, abandons the job);
   `system` and the three overrides are all optional.
4. The `user` text must instruct the model to emit the exact shape the Gateway's own comment parser
   expects — a JSON array of `{file, line, severity, comment}` objects (see the root README's
   [§6.6](../README.md#66-post-jobsidresult--submit-the-result-worker)) — since the Worker forwards the
   raw model output to the Gateway verbatim, with no parsing or validation of its own. The shipped
   `prompts/v1.yml` does exactly this and is the reference example to copy from.
