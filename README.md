# Review Gateway

Review Gateway is the central service of an AI Code Review Platform: it receives AI-review requests
from GitLab CI for merge requests, owns the full review lifecycle, dispatches long-running LLM jobs to
a pool of stateless Workers via a PostgreSQL-backed queue, stores results, and publishes comments back
to the merge request.

This document is a deployment and integration guide. Everything described here reflects what is
actually implemented in this repository — controllers, DTOs, `application.yml`,
`GatewayProperties`, `SecurityConfig`, the Flyway schema, and the SAST reports under
`docs/security/`. Where the codebase leaves something unimplemented or as an operator responsibility,
this document says so explicitly instead of describing aspirational behavior.

## Table of contents

1. [What it is](#1-what-it-is)
2. [Requirements](#2-requirements)
3. [Build & test](#3-build--test)
4. [Configuration](#4-configuration)
5. [Deployment](#5-deployment)
6. [API reference](#6-api-reference)
7. [GitLab CI integration](#7-gitlab-ci-integration)
8. [Review lifecycle](#8-review-lifecycle)
9. [Worker protocol](#9-worker-protocol)
10. [Operations](#10-operations)
11. [Security](#11-security)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. What it is

```
GitLab CI job  ──POST /reviews──▶  Review Gateway  ──▶  PostgreSQL
 (per MR pipeline)                 (single instance)     (queue, review state,
       ▲                                  │  ▲            results, audit log,
       │                                  │  │            backend registry)
       │                          POST /jobs/claim
       │                          POST /jobs/{id}/heartbeat
       │                          POST /jobs/{id}/result
       │                                  │  │
       │                                  ▼  │
       │                          stateless Worker(s)
       │                                  │
       │                                  ▼
       │                          llama-server backend
       │                       (OpenAI-Chat-Completions-compatible)
       │
       └──────── discussions posted to the Merge Request ◀── GitLab API
                 (Gateway → GitLab, via a configured project/group token)
```

- **GitLab CI** submits a diff for a Merge Request and gets a review id back immediately; it does not
  wait for the LLM to finish (`ReviewController.createReview` returns as soon as the Review is queued).
- **Review Gateway** (this service) is the sole owner of Review business logic and state. No other
  component mutates Review state directly.
- **PostgreSQL is the single source of truth.** The queue, the immutable input payload, results
  (including the raw model response), parsed comments, the audit trail (`review_events`), and the
  backend registry all live in PostgreSQL — nothing is cached in application memory that isn't
  reconstructible from the database after a restart. There is no Redis, Kafka, RabbitMQ, or Prometheus
  in this system by design.
- **Workers are stateless HTTP clients.** A Worker claims a job, calls its local `llama-server`, sends
  heartbeats, and submits the result. It has no GitLab or PostgreSQL credentials and no knowledge of
  retry/dedup/queue logic — that all lives in the Gateway (see [§9](#9-worker-protocol)).
- **llama-server backends** are OpenAI Chat-Completions-compatible HTTP endpoints; the Gateway never
  calls them for inference, only for a lightweight `/health` probe (see [§10](#10-operations)).

Target scale (per the requirements document): **20–30 merge requests/day**, long-running LLM tasks (up
to tens of minutes), and **1–10** backend servers, each typically paired with one Worker on its own host
(e.g. a Mac mini).

## 2. Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 | `pom.xml` targets Java 21; Spring Boot 3.5.16 parent. Virtual threads are enabled (`spring.threads.virtual.enabled: true`). |
| Maven | 3.9+ | Standard Maven build, `spring-boot-maven-plugin` produces an executable jar. |
| PostgreSQL | tested against 14.22 | The only persistence backend (schema in `src/main/resources/db/migration/V1__initial_schema.sql`, applied by Flyway at startup). No specific minimum version is mandated in the requirements document; the test suite runs against PostgreSQL 14.22 via an embedded (Zonky) instance and the schema uses no PostgreSQL-14-specific features (identity columns and `FOR UPDATE SKIP LOCKED` are supported from PostgreSQL 10+/9.5+ respectively), so 12+ is a reasonable practical floor. |
| Docker | **not required, anywhere** | Tests use `io.zonky.test` embedded PostgreSQL (a real Postgres binary run in-process), not Testcontainers. The CI security gate (`.github/workflows/security-gate.yml`) also runs `mvn verify` directly on a GitHub-hosted runner with no Docker step. |

No `Dockerfile`/`docker-compose.yml` exists in this repository — deployment is a plain executable jar
(see [§5](#5-deployment)).

## 3. Build & test

```bash
# JDK 21 and Maven 3.9+ must be on PATH (or point JAVA_HOME/PATH at a local install).
mvn -q compile
mvn -q test        # or: mvn verify (what the CI gate runs)
```

`mvn test`/`mvn verify` spins up an embedded PostgreSQL instance automatically for every test that
needs one (`@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)`) — no external database,
no Docker, and no manual setup is required to run the full suite locally.

## 4. Configuration

All configuration lives in `src/main/resources/application.yml`. Every `${VAR}` / `${VAR:default}`
placeholder below is read from an environment variable at startup (Spring's standard property
resolution — no custom secrets manager).

### 4.1 Required secrets (no default — startup fails without them)

`GatewayProperties.validateOnStartup()` (a `@PostConstruct` hook) refuses to let the application start
if any of the following is missing, blank, or **shorter than 32 characters**, and separately refuses to
start if the GitLab base URL is not `https://`. The exception message never echoes the actual secret
value, only the property name and, for the URL check, the scheme.

| Environment variable | Bound property | Purpose |
|---|---|---|
| `CI_TOKEN` | `gateway.security.ci-token` | Bearer token for GitLab-CI-facing endpoints (`POST /reviews`, `GET /reviews/{id}`). |
| `WORKER_TOKEN` | `gateway.security.worker-token` | Bearer token for Worker-facing endpoints (`POST /jobs/**`). |
| `ADMIN_TOKEN` | `gateway.security.admin-token` | Bearer token for admin endpoints (`DELETE /reviews/{id}`, `GET /backends`, `GET /metrics`). |
| `GITLAB_TOKEN` | `gateway.gitlab.token` | Token the Gateway itself uses to call the GitLab API when publishing comments (`PRIVATE-TOKEN` header). Never exposed to CI or Workers. |
| `DB_USER` | `spring.datasource.username` | PostgreSQL username. No default. |
| `DB_PASSWORD` | `spring.datasource.password` | PostgreSQL password. No default. |

All four bearer/API tokens should be random, high-entropy values (e.g. `openssl rand -hex 32`). The
threat model recommends a least-privilege, expiring GitLab **project or group access token** scoped
only to the projects under review for `GITLAB_TOKEN` — this is an operational choice made when the
token is issued in GitLab, not something the application enforces.

### 4.2 Everything else (has a working default)

| Property | Default | Purpose |
|---|---|---|
| `spring.datasource.url` (`DB_URL`) | `jdbc:postgresql://localhost:5432/review_gateway` | JDBC URL. See [§4.3](#43-deployment-must-dos-from-the-sast-report) for the non-loopback TLS note. |
| `spring.datasource.hikari.maximum-pool-size` | `20` | Connection pool size. |
| `server.port` | `8080` | HTTP listen port. |
| `server.max-http-request-header-size` | `16KB` | Hard cap on request header size. |
| `management.endpoints.web.exposure.include` | `health` | Only `/actuator/health` is exposed; no `/actuator/env`, `/actuator/beans`, etc. Business metrics are the custom `GET /metrics` endpoint, not an actuator/Prometheus endpoint (there is no Micrometer Prometheus registry in this project). |
| `gateway.diff.context-window` | `16384` | Assumed LLM context window, in tokens, for the diff-size budget heuristic. |
| `gateway.diff.prompt-reserve` | `2000` | Tokens reserved for prompt scaffolding. |
| `gateway.diff.answer-reserve` | `4000` | Tokens reserved for the model's answer. |
| `gateway.diff.max-diff-tokens` | `10000` | Explicit cap; the enforced budget is `min(max-diff-tokens, context-window - prompt-reserve - answer-reserve)` — 10000 with the stock defaults. |
| `gateway.diff.chars-per-token` | `4` | Heuristic characters-per-token ratio (no real tokenizer is used); diff size is estimated as `ceil(chars / chars-per-token)`. |
| `gateway.diff.max-request-body-bytes` | `100000` | Hard byte cap on the whole `POST /reviews` body, enforced by a servlet filter **before** Spring/Jackson reads it (see [§6.9](#69-body-size-limits)). |
| `gateway.heartbeat.timeout` | `180s` | A `RUNNING` job is considered stale if `now - heartbeat_at` exceeds this; it is then requeued or failed. |
| `gateway.heartbeat.interval` | `60s` | **Documents** the expected Worker heartbeat cadence; as of this codebase it is not bound to any `GatewayProperties` field (only `gateway.heartbeat.timeout` is read by the application), so changing it has no runtime effect — it exists purely as the value Worker implementations should target for `POST /jobs/{id}/heartbeat` frequency. |
| `gateway.retry.max-attempts` | `3` | Max claim attempts before a Review is marked `FAILED` instead of requeued. |
| `gateway.job.max-duration` | `45m` | Hard backstop: a `RUNNING` job older than this is requeued/failed even if heartbeats are still arriving. |
| `gateway.publish.max-comment-count` | `50` | Max parsed comments kept per Review; excess is dropped. |
| `gateway.publish.max-comment-length` | `4000` | Max characters per parsed comment; excess is truncated. |
| `gateway.publish.max-raw-response-length` | `200000` | Max characters of the raw LLM response actually persisted; oversized responses are truncated (not rejected) before storage and parsing. |
| `gateway.publish.max-request-body-bytes` | `500000` | Hard byte cap on the whole `POST /jobs/{id}/result` body (edge filter, see [§6.9](#69-body-size-limits)). |
| `gateway.scheduler.heartbeat-check-interval` | `30s` | Tick interval for the stale-heartbeat sweep and the max-duration sweep. |
| `gateway.scheduler.backend-health-interval` | `60s` | Tick interval for the backend health probe. |
| `gateway.scheduler.publish-retry-interval` | `60s` | Tick interval for retrying publication of `COMPLETED` reviews. |
| `gateway.gitlab.base-url` (`GITLAB_BASE_URL`) | `https://gitlab.example.com/api/v4` | GitLab API base URL. **Must** start with `https://` or the application refuses to start. |
| `gateway.gitlab.connect-timeout` / `read-timeout` | `5s` / `30s` | Timeouts for the GitLab HTTP client. |
| `gateway.backend.connect-timeout` / `read-timeout` | `3s` / `5s` | Timeouts for the backend `/health` probe client (which also disables following redirects). |
| `gateway.backend.allowed-host-pattern` (`BACKEND_ALLOWED_HOST_PATTERN`) | `.*` (matches any host) | Regex a backend's URL host must match before it is probed, on top of an always-on block of loopback/link-local/any-local/multicast addresses. **See the deployment must-do below — the default is permissive.** |

### 4.3 Deployment must-dos (from `docs/security/feature-03-sast-report.md`)

These are not enforced by the application (no startup check exists for them); they are explicit
operational prerequisites called out by the SAST review before going to production:

1. **Set `BACKEND_ALLOWED_HOST_PATTERN` to your actual backend network.** The `.*` default only blocks
   loopback/link-local/metadata-style addresses; it does **not** restrict backends to a specific
   network. Example: `BACKEND_ALLOWED_HOST_PATTERN='^192\.168\.1\.\d+$'`.
2. **Append `?sslmode=require` to `DB_URL` for any non-loopback PostgreSQL.** The default URL points at
   `localhost`, which is accepted without TLS; a remote database must not be.
3. **Terminate TLS on every inbound hop** (CI → Gateway, Worker → Gateway, Admin → Gateway) at a reverse
   proxy — the application itself does not configure `server.ssl.*` and listens on plain HTTP internally.
4. **Grant the Gateway's database role `INSERT`/`SELECT` only on `review_events`** (no `UPDATE`/`DELETE`)
   so the audit trail cannot be silently rewritten. This is a PostgreSQL grant, not application config.
5. **Enable volume/backup encryption and a retention policy** for `review_inputs.diff` and
   `review_results.raw_response` (they contain proprietary source code and raw model output). Not
   implemented by the application; do this at the database/backup layer.

## 5. Deployment

There is no Dockerfile or install script in this repository; the artifact is a plain executable Spring
Boot jar.

```bash
mvn -q -DskipTests package
java -jar target/review-gateway-1.0.0-SNAPSHOT.jar
```

with the environment variables from [§4.1](#41-required-secrets-no-default--startup-fails-without-them)
set (directly, via a systemd `EnvironmentFile`, or your process manager's equivalent). The requirements
document specifies running the Gateway as a **single instance**, e.g. as a systemd service with
`Restart=always` — this repository does not ship a unit file, but the application's design (see below)
is specifically built to tolerate that restart model.

- **Schema migrations run automatically at startup.** `spring.flyway.enabled: true` applies
  `V1__initial_schema.sql` before the application accepts traffic; `spring.jpa.hibernate.ddl-auto:
  validate` means Hibernate never generates DDL itself — the schema is exclusively Flyway-owned.
- **Backend (llama-server) registration has no REST endpoint.** The `backends` table (created by the
  V1 migration) is the only place backends are registered; there is no `POST /backends` or admin UI —
  register a backend with a direct SQL statement:

  ```sql
  INSERT INTO backends (name, url, model, capacity)
  VALUES ('mac-mini-01', 'http://192.168.1.50:8080', 'llama-3.1-8b-instruct', 1);
  -- status defaults to 'ACTIVE'; capacity is the max concurrent RUNNING jobs on this backend.
  ```

  The `name` you choose here (`mac-mini-01` above) is exactly the string a Worker must send as
  `backendId` in `POST /jobs/claim` (see [§9](#9-worker-protocol)) — despite the field being named
  `backendId`, it carries the backend's **name**, not its numeric database id.
- **`RUNNING` jobs are never reset on Gateway restart.** There is no startup reconciliation step that
  touches `RUNNING` reviews. The only mechanism that reclaims a stuck job is the heartbeat sweep
  (`gateway.scheduler.heartbeat-check-interval`, default every 30s): a `RUNNING` job whose
  `heartbeat_at` is older than `gateway.heartbeat.timeout` (default 180s / ~3 minutes) is requeued (if
  attempts remain) or failed. A Worker that is still alive and heartbeating is completely unaffected by
  a Gateway restart in between its heartbeats.

## 6. API reference

Every response body below is exactly the corresponding `record` in `com.review.gateway.dto`, serialized
by Jackson with default (camelCase) field naming — no custom naming strategy is configured.

### Role matrix

| Endpoint | Method | Required role | 
|---|---|---|
| `/reviews` | `POST` | `CI` |
| `/reviews/{id}` | `GET` | `CI` |
| `/reviews/{id}` | `DELETE` | `ADMIN` |
| `/jobs/claim`, `/jobs/{id}/heartbeat`, `/jobs/{id}/result` | `POST` | `WORKER` |
| `/backends` | `GET` | `ADMIN` |
| `/metrics` | `GET` | `ADMIN` |
| `/health` | `GET` | none (public) |

Every protected request must carry `Authorization: Bearer <token>`, matching exactly one of
`CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN`. There is no "CI or ADMIN" overlap — each path requires exactly
one role. A request with no token, or a token that matches none of the three roles, gets `401`; a
request with a valid token for the *wrong* role gets `403`. Both bodies are generic (see
[§6.8](#68-error-format)).

### 6.1 `POST /reviews` — create a Review

```bash
curl -s -X POST http://localhost:8080/reviews \
  -H "Authorization: Bearer $CI_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "projectId": 42,
        "mergeRequestId": 7,
        "headSha": "a1b2c3d4e5f6",
        "baseSha": "0f1e2d3c4b5a",
        "diff": "diff --git a/Foo.java b/Foo.java\n...",
        "promptVersion": "v1",
        "priority": 10
      }'
```

Request fields (`CreateReviewRequest`): `projectId` (positive `Long`, required), `mergeRequestId`
(positive `Long`, required — this is the **MR IID**, not GitLab's global MR id), `headSha` (non-blank
`String`, required), `baseSha` (non-blank `String`, required), `diff` (non-blank `String`, required —
raw diff text, not structured), `promptVersion` (non-blank `String`, required), `priority` (`Integer`,
optional — defaults to `10`; higher values are claimed first).

- **`201 Created`** — a genuinely new Review was queued:
  ```json
  { "reviewId": 123, "status": "QUEUED" }
  ```
- **`200 OK`** — an existing, still-active Review for the same `(projectId, mergeRequestId, headSha)`
  key was returned instead (dedup, [§8](#8-review-lifecycle)); the body has the same shape, with
  whatever the existing Review's current status is (e.g. `"RUNNING"`).
- **`422 Unprocessable Entity`** — the diff's estimated size exceeds the configured token budget
  (`gateway.diff.*`, default ~10,000 tokens ≈ 40,000 characters at the default 4-chars/token estimate):
  ```json
  { "error": "DIFF_TOO_LARGE", "message": "Diff too large: estimated 12500 tokens exceeds budget of 10000 tokens" }
  ```
- **`400 Bad Request`** — a required field is missing/blank (e.g. empty `diff`):
  ```json
  { "error": "VALIDATION_ERROR", "message": "diff: must not be blank" }
  ```
- **`413 Payload Too Large`** — the whole request body exceeds `gateway.diff.max-request-body-bytes`
  (default 100,000 bytes); see [§6.9](#69-body-size-limits).

### 6.2 `GET /reviews/{id}` — status

```bash
curl -s http://localhost:8080/reviews/123 -H "Authorization: Bearer $CI_TOKEN"
```

- **`200 OK`**:
  ```json
  {
    "reviewId": 123,
    "status": "COMPLETED",
    "attempts": 1,
    "createdAt": "2026-07-13T10:00:00Z",
    "updatedAt": "2026-07-13T10:04:32Z",
    "commentCount": 4
  }
  ```
  `status` is one of `NEW`, `QUEUED`, `RUNNING`, `COMPLETED`, `PUBLISHED`, `FAILED`, `CANCELLED`,
  `OBSOLETE` ([§8](#8-review-lifecycle)). The diff and the raw model response are **never** included in
  this response.
- **`404 Not Found`**:
  ```json
  { "error": "NOT_FOUND", "message": "Review not found: id=123" }
  ```
- **`400 Bad Request`** — a non-numeric id (e.g. `GET /reviews/abc`):
  ```json
  { "error": "VALIDATION_ERROR", "message": "id: must be a valid Long" }
  ```

### 6.3 `DELETE /reviews/{id}` — admin cancel

```bash
curl -s -X DELETE http://localhost:8080/reviews/123 -H "Authorization: Bearer $ADMIN_TOKEN"
```

- **`200 OK`** — cancelled (only `NEW`/`QUEUED`/`RUNNING`/`COMPLETED` reviews are cancellable); body is
  the same shape as `GET /reviews/{id}` with `"status": "CANCELLED"`. A Worker currently running this
  job learns to stop via its next heartbeat response (`shouldContinue: false`), not via a direct call.
- **`409 Conflict`** — the Review is already in a terminal state (`PUBLISHED`/`FAILED`/`CANCELLED`/`OBSOLETE`):
  ```json
  { "error": "INVALID_STATE_TRANSITION", "message": "Illegal Review state transition: PUBLISHED -> CANCELLED" }
  ```
- **`404 Not Found`** — same shape as §6.2.
- **`403 Forbidden`** — a `CI` (or `WORKER`) token was used instead of `ADMIN`.

### 6.4 `POST /jobs/claim` — claim the next queued job (Worker)

```bash
curl -s -X POST http://localhost:8080/jobs/claim \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "backendId": "mac-mini-01", "workerId": "worker-mac-mini-01" }'
```

Request fields (`ClaimJobRequest`): `backendId` (non-blank `String` — the backend's **name** as
registered in the `backends` table, e.g. `"mac-mini-01"`), `workerId` (non-blank `String` — any
identifier the Worker chooses to identify itself; it must reuse the *same* value on every subsequent
heartbeat/result call for this job).

- **`200 OK`** — a job was claimed:
  ```json
  {
    "jobId": 456,
    "reviewId": 123,
    "payload": { "diff": "diff --git a/Foo.java b/Foo.java\n...", "promptVersion": "v1" }
  }
  ```
- **`204 No Content`** — nothing to claim right now. This covers three indistinguishable situations by
  design: the queue is empty, the named backend is not `ACTIVE`, or the backend is already at capacity.
  A Worker should treat `204` as "wait and poll again" in all three cases.

### 6.5 `POST /jobs/{id}/heartbeat` — liveness ping (Worker)

```bash
curl -s -X POST http://localhost:8080/jobs/456/heartbeat \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "workerId": "worker-mac-mini-01" }'
```

- **`200 OK`**:
  ```json
  { "shouldContinue": true }
  ```
  `shouldContinue: false` means the Review has gone `OBSOLETE` or `CANCELLED` (or otherwise left
  `RUNNING`) — the Worker must abort generation and move on to the next job.
- **`404 Not Found`** — unknown `jobId` (empty body).
- **`403 Forbidden`** — `workerId` does not match the worker that actually claimed this job (empty
  body — the Gateway does not reveal the job's real state to a non-owner).

### 6.6 `POST /jobs/{id}/result` — submit the result (Worker)

```bash
curl -s -X POST http://localhost:8080/jobs/456/result \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "workerId": "worker-mac-mini-01",
        "rawResponse": "[{\"file\":\"Foo.java\",\"line\":42,\"severity\":\"MAJOR\",\"comment\":\"Null check missing\"}]",
        "promptTokens": 3200,
        "completionTokens": 180,
        "durationMs": 45000,
        "model": "llama-3.1-8b-instruct"
      }'
```

Request fields (`SubmitResultRequest`): `workerId` (non-blank, required), `rawResponse` (non-blank,
required — the model's raw text output; the Gateway tries to parse it as a JSON array of
`{file, line, severity, comment}` objects and falls back to treating the whole response as a single
comment if it isn't), `promptTokens`/`completionTokens` (`Integer`, optional), `durationMs` (`Long`,
optional), `model` (`String`, optional).

- **`200 OK`** — accepted, whether this is the first delivery or a retried one (idempotent):
  ```json
  { "reviewId": 123, "status": "COMPLETED" }
  ```
  `status` will be `"COMPLETED"` on success or `"FAILED"` if the result could not be processed at all;
  if the Review had already left `RUNNING` before this call arrived (e.g. a duplicate delivery after it
  was already completed, or it went `OBSOLETE` in the meantime), the response reflects that current
  status with no further state change.
- **`404 Not Found`** / **`403 Forbidden`** — same semantics as heartbeat.

### 6.7 `GET /backends` / `GET /metrics` (Admin)

```bash
curl -s http://localhost:8080/backends -H "Authorization: Bearer $ADMIN_TOKEN"
```
```json
[
  {
    "id": 1,
    "name": "mac-mini-01",
    "model": "llama-3.1-8b-instruct",
    "capacity": 1,
    "status": "ACTIVE",
    "running": 0,
    "lastSeen": "2026-07-13T10:05:00Z"
  }
]
```
`running` is derived live from the count of currently-`RUNNING` jobs on that backend — there is no
separate counter to drift out of sync. The backend's URL is deliberately **not** included in this view.

```bash
curl -s http://localhost:8080/metrics -H "Authorization: Bearer $ADMIN_TOKEN"
```
```json
{
  "total": 87,
  "byStatus": { "QUEUED": 2, "RUNNING": 1, "COMPLETED": 3, "PUBLISHED": 78, "FAILED": 3 },
  "avgQueueMs": 4210.5,
  "avgRunMs": 96340.2,
  "totalComments": 214,
  "retries": 5
}
```

### 6.8 Error format

`GlobalExceptionHandler` (and, for the two cases below it, `SecurityConfig`/`RequestBodySizeLimitFilter`
writing the same JSON shape directly) always returns `{ "error": "<CODE>", "message": "<text>" }` — a
short machine-readable code plus a human-readable message, never a stack trace or exception class name.

| HTTP status | `error` code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | A `@NotBlank`/`@NotNull`/`@Positive` field failed, or a path variable couldn't be parsed (e.g. non-numeric `{id}`). |
| 400 | `MALFORMED_REQUEST` | The request body is missing or is not valid JSON. |
| 401 | `UNAUTHORIZED` | No/unrecognized bearer token. (Written by `SecurityConfig`, not `GlobalExceptionHandler`.) |
| 403 | `FORBIDDEN` | Valid token, wrong role for this endpoint. (Written by `SecurityConfig`.) |
| 404 | `NOT_FOUND` | Unknown review id. |
| 409 | `INVALID_STATE_TRANSITION` | Admin cancel on an already-terminal Review. |
| 413 | `PAYLOAD_TOO_LARGE` | Request body exceeds the configured edge cap. (Written by `RequestBodySizeLimitFilter`, not `GlobalExceptionHandler`.) |
| 422 | `DIFF_TOO_LARGE` | Diff exceeds the token budget. |
| 500 | `INTERNAL_ERROR` | Anything unmapped; the real exception is logged server-side only. |

`GlobalExceptionHandler` also maps a `JOB_NOT_CLAIMABLE` (409) code, but as of this codebase **nothing
throws it** — `POST /jobs/claim` always responds `200`/`204`, never an error, for an unknown/inactive/
at-capacity backend. This mapping is dead code kept for forward-compatibility; do not expect to see it.

### 6.9 Body size limits

A servlet filter (`RequestBodySizeLimitFilter`, registered ahead of Spring Security) rejects an
oversized body based on the `Content-Length` header, before authentication or JSON parsing: `100,000`
bytes for `POST /reviews` (`gateway.diff.max-request-body-bytes`) and `500,000` bytes for
`POST /jobs/{id}/result` (`gateway.publish.max-request-body-bytes`). `POST /jobs/claim` and
`POST /jobs/{id}/heartbeat` bodies are not size-capped (their DTOs are small and Worker-authenticated
only — a documented, accepted low-risk gap, see the SAST report's F03-03 finding). This
`Content-Length`-based check does not catch a client that both omits `Content-Length` and streams an
unbounded chunked body; that residual gap is accepted at this project's scale (internal CI/Worker
clients, not a public API).

## 7. GitLab CI integration

A working `.gitlab-ci.yml` job. It uses only fields that exist on `CreateReviewRequest` and GitLab's own
predefined merge-request-pipeline variables (`CI_PROJECT_ID`, `CI_MERGE_REQUEST_IID`, `CI_COMMIT_SHA`,
`CI_MERGE_REQUEST_DIFF_BASE_SHA`). Configure `REVIEW_GATEWAY_URL` and `REVIEW_GATEWAY_CI_TOKEN` as
masked/protected CI/CD variables in the GitLab project (or group) settings — `REVIEW_GATEWAY_CI_TOKEN`
must equal the Gateway's configured `CI_TOKEN`.

```yaml
ai-review:
  stage: review
  image: alpine:3.20
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  before_script:
    - apk add --no-cache git curl jq
  script:
    # The diff between the MR's merge-base and the current head commit.
    - git diff "$CI_MERGE_REQUEST_DIFF_BASE_SHA" "$CI_COMMIT_SHA" > diff.txt

    # Build the request body with jq (safe JSON string escaping for a multi-line diff).
    - |
      jq -n \
        --argjson projectId "$CI_PROJECT_ID" \
        --argjson mergeRequestId "$CI_MERGE_REQUEST_IID" \
        --arg headSha "$CI_COMMIT_SHA" \
        --arg baseSha "$CI_MERGE_REQUEST_DIFF_BASE_SHA" \
        --arg promptVersion "v1" \
        --rawfile diff diff.txt \
        '{projectId:$projectId, mergeRequestId:$mergeRequestId, headSha:$headSha,
          baseSha:$baseSha, diff:$diff, promptVersion:$promptVersion, priority:10}' \
        > request.json

    - |
      http_code=$(curl -s -o response.json -w "%{http_code}" -X POST "$REVIEW_GATEWAY_URL/reviews" \
        -H "Authorization: Bearer $REVIEW_GATEWAY_CI_TOKEN" \
        -H "Content-Type: application/json" \
        --data @request.json)
      echo "HTTP $http_code:"; cat response.json

      if [ "$http_code" = "422" ]; then
        echo "Diff too large for the configured LLM context budget — review not queued."
        exit 0   # informational only; change to `exit 1` to make an oversized diff a hard pipeline failure
      elif [ "$http_code" != "200" ] && [ "$http_code" != "201" ]; then
        echo "Review Gateway returned an unexpected status."
        exit 1
      fi

      REVIEW_ID=$(jq -r '.reviewId' response.json)
      echo "Review $REVIEW_ID queued (or already active for this head_sha)."
```

Notes on dedup/obsolete semantics, so the pipeline author doesn't need to add any special-casing:

- **Re-running the same pipeline on the same commit does not create a duplicate Review.** The dedup key
  is `(projectId, mergeRequestId, headSha)`; a second `POST /reviews` for the same triple while a prior
  Review is still `NEW`/`QUEUED`/`RUNNING`/`COMPLETED`/`PUBLISHED` just returns that existing
  `reviewId` (HTTP `200` instead of `201`).
- **A new push (new `head_sha`) automatically supersedes the previous Review for the same MR.** The
  Gateway marks every prior non-terminal, non-`PUBLISHED` Review of that MR `OBSOLETE` as part of
  handling the new `POST /reviews` call — the CI job does not need to cancel anything itself.
- **The job does not need to wait for the review to finish.** `POST /reviews` returns as soon as the
  Review is queued; the Gateway posts comments to the MR on its own schedule (via `GITLAB_TOKEN`),
  independent of the CI job's lifetime.

An **optional** polling step, if a pipeline wants to gate on the review actually completing:

```yaml
    - |
      for i in $(seq 1 30); do
        status=$(curl -s "$REVIEW_GATEWAY_URL/reviews/$REVIEW_ID" \
          -H "Authorization: Bearer $REVIEW_GATEWAY_CI_TOKEN" | jq -r '.status')
        echo "Review $REVIEW_ID status: $status"
        case "$status" in
          PUBLISHED|FAILED|CANCELLED|OBSOLETE) break ;;
        esac
        sleep 20
      done
```

## 8. Review lifecycle

```
NEW ──▶ QUEUED ──▶ RUNNING ──▶ COMPLETED ──▶ PUBLISHED
                       │
                       ├──▶ FAILED
                       │
        (from any non-terminal state, on admin cancel)
                       └──▶ CANCELLED

        (from NEW/QUEUED/RUNNING/COMPLETED, on a new head_sha for the same MR)
                       ──▶ OBSOLETE
```

Terminal states (no outgoing transitions): `PUBLISHED`, `FAILED`, `CANCELLED`, `OBSOLETE`. Every
transition is validated and applied in exactly one place (`StateMachine`) and writes an audit row to
`review_events`.

| Transition | Trigger |
|---|---|
| `NEW → QUEUED` | `POST /reviews` (same transaction as the initial insert). |
| `QUEUED → RUNNING` | A Worker successfully claims the job (`POST /jobs/claim`). |
| `RUNNING → COMPLETED` | `POST /jobs/{id}/result` is processed successfully. |
| `RUNNING → QUEUED` | Heartbeat timeout or max-duration backstop, **and** attempts remaining (retry). |
| `RUNNING → FAILED` | Heartbeat timeout/max-duration with attempts exhausted, **or** the result could not be parsed at all. |
| `COMPLETED → PUBLISHED` | All parsed comments were successfully posted to the MR. |
| `COMPLETED → COMPLETED` | A transient GitLab API failure during publish — stays `COMPLETED`, retried later; not a state change, no new event. |
| `(NEW/QUEUED/RUNNING/COMPLETED) → OBSOLETE` | A new `head_sha` arrives for the same `(projectId, mergeRequestId)`. |
| `(NEW/QUEUED/RUNNING/COMPLETED) → CANCELLED` | `DELETE /reviews/{id}` (admin). |

Retry and timeout parameters (defaults; see [§4](#4-configuration) to change them):

- **Up to 3 attempts** (`gateway.retry.max-attempts`) before a Review is marked `FAILED` instead of
  requeued.
- **Heartbeat timeout ~3 minutes** (`gateway.heartbeat.timeout: 180s`): a `RUNNING` job whose heartbeat
  is missing or stale by more than this is requeued or failed. This sweep runs every 30 seconds
  (`gateway.scheduler.heartbeat-check-interval`).
- **Max-duration backstop, 45 minutes** (`gateway.job.max-duration`): a hard cap beyond heartbeat
  monitoring, in case a Worker keeps heartbeating a job that will never finish.

`GET /reviews/{id}` exposes exactly the current `status` value from this table, plus `attempts` (so a
caller can tell "will retry" from "exhausted retries" once a Review reaches `FAILED`).

## 9. Worker protocol

This section is a guide for implementing a Worker (this repository does not ship a Worker
implementation — only the Gateway). A Worker is a stateless HTTP client with no GitLab or PostgreSQL
access at all.

1. **Claim.** `POST /jobs/claim` with your registered backend's `name` (as `backendId`) and a
   self-chosen `workerId` string.
   - `200` → you have `jobId`, `reviewId`, and a `payload` with `diff` + `promptVersion`. Build your
     prompt from these two fields only.
   - `204` → nothing to claim right now (empty queue, or your backend isn't `ACTIVE`/is at capacity).
     Wait (e.g. a few seconds) and poll again.
2. **Run inference** against your local `llama-server` using the claimed `diff`/`promptVersion`.
3. **Heartbeat roughly every 60 seconds** while generating: `POST /jobs/{id}/heartbeat` with the *same*
   `workerId` you claimed with.
   - `shouldContinue: false` → **stop generating immediately** and move on to claiming the next job; the
     Review has gone `OBSOLETE` or `CANCELLED` (or otherwise left `RUNNING`).
   - `404`/`403` → something is wrong with your job/worker id; stop working this job.
   - If you stop heartbeating (crash, network partition), the Gateway's heartbeat sweep reclaims the job
     on its own after ~3 minutes — you do not need to do anything to "release" a job you can't finish.
4. **Submit the result.** `POST /jobs/{id}/result` with the same `workerId`, the raw model text as
   `rawResponse`, and whatever token/duration/model metadata you have. This call is **idempotent** — if
   your process crashes after a successful submission and you (or a retry mechanism) resend the exact
   same result, the Gateway detects the job is no longer `RUNNING` and returns the current status
   without any further state change or duplicate data.

What a Worker deliberately does **not** need to know or have access to: GitLab (no API calls, no
token), PostgreSQL (no driver, no credentials, no schema knowledge), retry counting or the max-attempts
limit, deduplication, or how the raw response gets parsed into structured comments — all of that is the
Gateway's responsibility.

## 10. Operations

### Backend registry & health

Backend status (`GET /backends`, ADMIN) is one of:

| Status | Meaning |
|---|---|
| `ACTIVE` | Eligible to be assigned new jobs. |
| `SUSPECT` | Failed its last health probe; excluded from new assignments. Auto-recovers to `ACTIVE` the next time its `/health` probe succeeds. |
| `MAINTENANCE` | Operator-set; excluded from new assignments. The health checker never touches a `MAINTENANCE`/`OFFLINE` backend's status — there is no endpoint to set these, only a direct `UPDATE backends SET status = 'MAINTENANCE' WHERE name = '...'`. |
| `OFFLINE` | Same as `MAINTENANCE` — operator-managed, ignored by the automatic health checker. |

The health checker probes every `ACTIVE`/`SUSPECT` backend's `{url}/health` on the
`gateway.scheduler.backend-health-interval` tick (default 60s), flipping `ACTIVE → SUSPECT` on failure
and `SUSPECT → ACTIVE` on recovery, and updates `last_seen`. Capacity for claim purposes is always the
live count of currently-`RUNNING` jobs on that backend versus its configured `capacity` — there is no
separate counter to go stale.

### Scheduled jobs

All run on the single Gateway instance (no distributed lock needed — see the requirements document's
single-instance deployment model) and are individually try/caught so one failing tick never stops the
others or de-schedules itself:

| Job | Interval (config key) | Action |
|---|---|---|
| Stale-heartbeat sweep | `gateway.scheduler.heartbeat-check-interval` (30s) | Requeue/fail `RUNNING` jobs past `gateway.heartbeat.timeout`. |
| Max-duration sweep | same interval as above | Requeue/fail `RUNNING` jobs past `gateway.job.max-duration`. |
| Backend health probe | `gateway.scheduler.backend-health-interval` (60s) | `ACTIVE ⇄ SUSPECT` transitions. |
| Publish retry | `gateway.scheduler.publish-retry-interval` (60s) | Re-attempt posting unpublished comments for `COMPLETED` reviews. |

### Audit trail

Every state transition (and every heartbeat) writes an append-only row to `review_events`
(`CREATED`, `CLAIMED`, `RUNNING`, `HEARTBEAT`, `RETRY`, `COMPLETED`, `PUBLISHED`, `FAILED`, `OBSOLETE`,
`CANCELLED`), with the worker/backend attribution when applicable. There is no query endpoint for this
table in the current API surface — inspect it directly in PostgreSQL for incident investigation. Its
`details` column is deliberately scrubbed of anything that looks like a token/secret and hard-capped in
length; it never contains a diff or raw model response.

### Cancelling a review

`DELETE /reviews/{id}` with the `ADMIN` token ([§6.3](#63-delete-reviewsid--admin-cancel)).

## 11. Security

- **Three static bearer tokens** (`CI_TOKEN`, `WORKER_TOKEN`, `ADMIN_TOKEN`), one role each, no overlap
  ([§6](#6-api-reference) role matrix). There is no token rotation mechanism (a single value per role) —
  rotating a token requires a config change and restart.
- **Constant-time comparison.** Both the presented and the configured token are SHA-256-hashed to a
  fixed-length digest, then compared with `MessageDigest.isEqual` — never `String.equals`/`==` — so a
  timing side channel cannot be used to recover a token byte-by-byte.
- **Generic error bodies everywhere** ([§6.8](#68-error-format)): no stack trace, exception class name,
  or internal identifier ever reaches a client, including on the two failure paths that are handled
  outside `GlobalExceptionHandler` (401/403/413).
- **Startup fail-fast** for missing/short secrets and a non-HTTPS GitLab base URL
  ([§4.1](#41-required-secrets-no-default--startup-fails-without-them)).
- **SSRF guard on backend probing.** Every backend URL is re-validated on every probe (not just when
  registered): scheme must be `http`/`https`, loopback/link-local/any-local/multicast addresses are
  always rejected, an unresolvable host is treated as unsafe, and the host must also match
  `gateway.backend.allowed-host-pattern` (default permissive — **must** be tightened for production, see
  [§4.3](#43-deployment-must-dos-from-the-sast-report)). The probe client disables redirects.
- **CI security gate** (this project's own build, not the product's GitLab integration):
  `.github/workflows/security-gate.yml` runs on every PR and push to `master` — `gitleaks` (secret
  scanning, full git history), `osv-scanner` over a CycloneDX SBOM (blocks on High/Critical CVEs),
  `semgrep` (`p/java`, `p/spring`, `p/sql-injection`, `p/secrets`, blocks on ERROR-severity), and
  `mvn verify`. Details in `docs/security/sr-23-ci-gate.md`.
- Known, explicitly accepted residual gaps (not implemented, documented in
  `docs/security/feature-03-sast-report.md`): worker identity in `POST /jobs/*` is self-declared under a
  single shared `WORKER_TOKEN` (no per-worker lease token or per-backend token binding); no in-memory
  rate limiting on any endpoint; a single Gateway instance is a deliberate availability trade-off, not a
  bug.

## 12. Troubleshooting

- **Application won't start, logs an `IllegalStateException` mentioning a token or `gateway.gitlab.base-url`.**
  One of the four required secrets is missing or shorter than 32 characters, or the GitLab base URL
  doesn't start with `https://`. See [§4.1](#41-required-secrets-no-default--startup-fails-without-them).
- **`401 Unauthorized`.** No `Authorization` header, or the bearer token doesn't exactly match any of
  the three configured values. Check for accidental whitespace/truncation when the token is injected
  from a CI/CD variable or secret store.
- **`403 Forbidden` on a CI/Worker/Admin call.** Either the token is valid but for the wrong role (e.g.
  a `CI` token calling `DELETE /reviews/{id}`, which needs `ADMIN`), or — for `/jobs/{id}/heartbeat` and
  `/jobs/{id}/result` specifically — the `workerId` in the request body doesn't match the `workerId` that
  originally claimed that job.
- **`413 Payload Too Large`.** The request body exceeds `gateway.diff.max-request-body-bytes` (100,000
  bytes default, `POST /reviews`) or `gateway.publish.max-request-body-bytes` (500,000 bytes default,
  `POST /jobs/{id}/result`). Either the diff/response is genuinely too large (consider sending only
  changed hunks, not whole files) or raise the corresponding property (together with the matching
  token-budget property, so the two limits stay consistent).
- **`422 DIFF_TOO_LARGE`.** The diff's estimated token count exceeds the configured budget. Either
  reduce the diff, or raise `gateway.diff.max-diff-tokens`/`context-window` (and, if you do, the edge
  byte cap in the point above should be raised to match).
- **A Review ends up `FAILED`.** Either it exhausted `gateway.retry.max-attempts` (default 3) after
  repeated heartbeat timeouts / a backend that keeps dying mid-job, or the Worker's submitted
  `rawResponse` could not be processed at all (a genuinely unexpected error, not just "wasn't valid
  JSON" — the comment parser already falls back to a single plain-text comment for that case). Check
  `review_events` for this Review's row history and `attempts` via `GET /reviews/{id}`.
  A `COMPLETED` review's Worker will not see it become `PUBLISHED`; that's on the Gateway's own
  publish-retry cycle — check GitLab connectivity/`GITLAB_TOKEN` validity if a `COMPLETED` review stays
  that way for longer than a few `gateway.scheduler.publish-retry-interval` ticks.
- **A backend stays `SUSPECT`.** Its `{url}/health` is failing the probe — check the URL is reachable
  from the Gateway host, matches `gateway.backend.allowed-host-pattern`, and that `llama-server` is
  actually up and answering `/health`.
- **`POST /jobs/claim` keeps returning `204`.** Confirm the backend `name` you're sending as `backendId`
  exists in the `backends` table, is `ACTIVE`, and isn't already at its configured `capacity` (check
  `GET /backends`'s `running`/`capacity` fields) — all three collapse into the same `204`, by design.
- **`500 INTERNAL_ERROR`.** The response body never explains why (SR-17: no internal detail leaks to
  the client) — check the Gateway's own logs, where the actual exception is logged server-side.
