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
       │                          POST /jobs/{id}/fail  (best-effort)
       │                                  │  │
       │                                  ▼  │
       │                          stateless Worker(s)
       │                                  │
       │                                  ▼
       │                          llama-server backend
       │                       (OpenAI-Chat-Completions-compatible)
       │
       └──────── discussions posted to the Merge Request ◀── GitLab API
                 (Gateway → GitLab, via a configured project/group token; comments with a resolvable
                 file+line are anchored to that diff position — native diff threads, not just top-level
                 notes — Diff Position Anchoring, see `gateway.publish.position-anchoring-enabled` in §4.2
                 and `docs/diff-position-anchoring-architecture.md`)
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
- **Prompt Manager (V3, optional, off by default)** lets the system prompt come from Git instead of only
  the Worker's own jar: mandatory corporate rules from one org-wide GitLab repo, plus optional
  per-project architecture/code-style rules from the reviewed project itself (or an operator-configured
  override repo) — see [§4.4](#44-prompt-manager-v3-optional) and
  [§6.1b](#61b-prompt-manager-and-system-prompt-assembly).

Target scale (per the requirements document): **20–30 merge requests/day**, long-running LLM tasks (up
to tens of minutes), and **1–10** backend servers, each typically paired with one Worker on its own host
(e.g. a Mac mini).

## 2. Requirements

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 21 | `pom.xml` targets Java 21; Spring Boot 3.5.16 parent. Virtual threads are enabled (`spring.threads.virtual.enabled: true`). |
| Maven | 3.9+ | Standard Maven build, `spring-boot-maven-plugin` produces an executable jar. |
| PostgreSQL | tested against 14.22 | The only persistence backend (schema in `src/main/resources/db/migration/V1__initial_schema.sql`, applied by Flyway at startup). No specific minimum version is mandated in the requirements document; the test suite runs against PostgreSQL 14.22 via an embedded (Zonky) instance and the schema uses no PostgreSQL-14-specific features (identity columns and `FOR UPDATE SKIP LOCKED` are supported from PostgreSQL 10+/9.5+ respectively), so 12+ is a reasonable practical floor. |
| Docker | **not required to build or test** | Tests use `io.zonky.test` embedded PostgreSQL (a real Postgres binary run in-process), not Testcontainers. The CI security gate (`.github/workflows/security-gate.yml`) also runs `mvn verify` directly on a GitHub-hosted runner with no Docker step. |

A root `Dockerfile`, `worker/Dockerfile`, and a `docker-compose.yml` wiring Postgres + both images together
are provided as an *optional* containerized deployment path — see [§5](#5-deployment) and
[DEPLOYMENT.md §11](DEPLOYMENT.md#11-docker-deployment-verified-both-images). The plain-jar path below
remains the primary one this document describes in detail.

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
if any of the following is missing or blank, and separately refuses to start if the GitLab base URL is
not `https://`. The three bearer tokens (`CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN`) additionally must be
**at least 32 characters** — `GITLAB_TOKEN` is checked for presence only, not length, since it is issued
by GitLab itself in a fixed format (a project/group access token is exactly 26 characters, `glpat-` + 20)
that the operator does not control; applying the same 32-character floor to it would reject every real
GitLab token. The exception message never echoes the actual secret value, only the property name and,
for the URL check, the scheme.

| Environment variable | Bound property | Purpose |
|---|---|---|
| `CI_TOKEN` | `gateway.security.ci-token` | Bearer token for GitLab-CI-facing endpoints (`POST /reviews`, `GET /reviews/{id}`). **≥32 chars.** |
| `WORKER_TOKEN` | `gateway.security.worker-token` | Bearer token for Worker-facing endpoints (`POST /jobs/**`). **≥32 chars.** |
| `ADMIN_TOKEN` | `gateway.security.admin-token` | Bearer token for admin endpoints (`DELETE /reviews/{id}`, `GET /backends`, `GET /metrics`). **≥32 chars.** |
| `GITLAB_TOKEN` | `gateway.gitlab.token` | Token the Gateway itself uses to call the GitLab API when publishing comments (`PRIVATE-TOKEN` header). Never exposed to CI or Workers. **Presence-only check — a real `glpat-...` token (26 chars) is expected and accepted.** **Diff Position Anchoring** (`gateway.publish.position-anchoring-enabled`, default `true`) additionally uses this token for a *read* call (`GET /projects/{id}/merge_requests/{iid}`, to fetch `diff_refs`) — it now needs **Reporter+/`api`** access to the reviewed MRs, not just write access to post discussions. A token that lacks this read scope degrades **silently** to plain (non-anchored) notes; set `gateway.publish.position-anchoring-enabled=false` if you cannot widen the token's scope. |
| `DB_USER` | `spring.datasource.username` | PostgreSQL username. No default. |
| `DB_PASSWORD` | `spring.datasource.password` | PostgreSQL password. No default. |

The three bearer/API tokens above should be random, high-entropy values (e.g. `openssl rand -hex 32`). The
threat model recommends a least-privilege, expiring GitLab **project or group access token** scoped
only to the projects under review for `GITLAB_TOKEN` — this is an operational choice made when the
token is issued in GitLab, not something the application enforces.

**A conditional fifth secret, `GITLAB_PROMPT_TOKEN`, only if you opt into Prompt Manager** (V3,
`gateway.prompt.enabled`, default `false`) — see [§4.4](#44-prompt-manager-v3-optional). With the
kill-switch at its default, this token is never required and `validatePromptOnStartup()` skips all
`gateway.prompt.*` validation entirely.

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
| `gateway.diff.max-diff-tokens` | `10000` | Explicit cap; the enforced budget is `min(max-diff-tokens, context-window - prompt-reserve - answer-reserve)` — 10000 with the stock defaults. **As of diff chunking (V2), this is the per-chunk budget**, not a whole-diff reject threshold — see [§6.1a](#61a-diff-chunking). |
| `gateway.diff.chars-per-token` | `4` | Heuristic characters-per-token ratio (no real tokenizer is used); diff size is estimated as `ceil(chars / chars-per-token)`. |
| `gateway.diff.max-chunks` | `5` | **V2.** Maximum number of chunks `DiffChunker` may split one diff into; a diff needing more is rejected with `422 DIFF_TOO_LARGE` rather than dispatched. Deliberately conservative (see the javadoc on `GatewayProperties.Diff.maxChunks` for the pool-starvation compute-cost math: `max-chunks × job.max-duration × retry.max-attempts` is the worst-case aggregate Worker time one MR can occupy). |
| `gateway.diff.chunk-header-reserve-tokens` | `256` | **V2.** Tokens reserved per chunk for the injected cross-chunk context header (§6.1a); subtracted from the per-chunk budget during bin-packing. |
| `gateway.diff.max-chunk-context-chars` | `1000` | **V2.** Hard cap (characters) on the rendered cross-chunk context header text; excess other-file paths collapse to `"... and N more"`. |
| `gateway.diff.max-request-body-bytes` | `320000` | Hard byte cap on the whole `POST /reviews` body, enforced by a servlet filter **before** Spring/Jackson reads it (see [§6.9](#69-body-size-limits)). **V2:** derived from `max-chunks × max-diff-tokens × chars-per-token × 1.5 (JSON-escaping safety factor) + 20000 (fixed overhead)` — see `GatewayProperties.Diff.maxRequestBodyBytes` javadoc (CSR-02); if you change `max-chunks`/`max-diff-tokens`/`chars-per-token`, recompute and update this value too, it is not auto-derived. |
| `gateway.heartbeat.timeout` | `180s` | A `RUNNING` job is considered stale if `now - heartbeat_at` exceeds this; it is then requeued or failed. |
| `gateway.heartbeat.interval` | `60s` | **Documents** the expected Worker heartbeat cadence; as of this codebase it is not bound to any `GatewayProperties` field (only `gateway.heartbeat.timeout` is read by the application), so changing it has no runtime effect — it exists purely as the value Worker implementations should target for `POST /jobs/{id}/heartbeat` frequency. |
| `gateway.retry.max-attempts` | `3` | Max claim attempts before a Review is marked `FAILED` instead of requeued. |
| `gateway.retry.requeue-delay` | `90s` | **Worker Observability & Claim Latency.** Delay before a requeued job becomes claimable again (`review_jobs.not_before`). **90s, not the more obvious-looking 30s** — a startup check enforces `requeue-delay × (max-attempts − 1) ≥ gateway.backend.failure-grace`, so a dead/restarting backend's attempt budget can never be exhausted faster than it can be detected and demoted (see §10). `0` disables the mechanism entirely (immediate requeue, today's pre-this-feature behavior) and **must** be paired with `gateway.backend.failure-grace: 0` — neither may be zeroed alone. |
| `gateway.job.max-duration` | `45m` | Hard backstop: a `RUNNING` job older than this is requeued/failed even if heartbeats are still arriving. |
| `gateway.job.max-fail-body-bytes` | `4096` | Hard byte cap on the whole `POST /jobs/{id}/fail` body (edge filter, see [§6.9](#69-body-size-limits)). |
| `gateway.publish.max-comment-count` | `50` | Max parsed comments kept per Review; excess is dropped. |
| `gateway.publish.max-comment-length` | `4000` | Max characters per parsed comment; excess is truncated. |
| `gateway.publish.max-raw-response-length` | `200000` | Max characters of the raw LLM response actually persisted; oversized responses are truncated (not rejected) before storage and parsing. |
| `gateway.publish.max-request-body-bytes` | `500000` | Hard byte cap on the whole `POST /jobs/{id}/result` body (edge filter, see [§6.9](#69-body-size-limits)). |
| `gateway.publish.position-anchoring-enabled` | `true` | **Diff Position Anchoring.** Anchors published comments that have a resolvable `(file, line)` to that diff position (native GitLab diff thread) instead of a top-level note. Every failure mode (flag off, stale/short `headSha`, GitLab unreachable, unresolvable line) degrades silently to today's plain-note behavior — never fails a Review. Requires `GITLAB_TOKEN` to additionally have read access to the reviewed MRs (see [§4.1](#41-required-secrets-no-default--startup-fails-without-them)); a token lacking that scope also degrades silently to plain notes. `false` is the supported kill switch. |
| `gateway.scheduler.heartbeat-check-interval` | `30s` | Tick interval for the stale-heartbeat sweep and the max-duration sweep. |
| `gateway.scheduler.backend-health-interval` | `60s` | Tick interval for the backend health probe. |
| `gateway.scheduler.publish-retry-interval` | `60s` | Tick interval for retrying publication of `COMPLETED` reviews. |
| `gateway.gitlab.base-url` (`GITLAB_BASE_URL`) | `https://gitlab.example.com/api/v4` | GitLab API base URL. **Must** start with `https://` or the application refuses to start. |
| `gateway.gitlab.connect-timeout` / `read-timeout` | `5s` / `30s` | Timeouts for the GitLab HTTP client. |
| `gateway.backend.connect-timeout` / `read-timeout` | `3s` / `10s` | Timeouts for the backend `/health` probe client (which also disables following redirects). `read-timeout` was raised from `5s` — safe because the probe's HTTP call no longer runs inside a database transaction (see §10). |
| `gateway.backend.allowed-host-pattern` (`BACKEND_ALLOWED_HOST_PATTERN`) | `.*` (matches any host) | Regex a backend's URL host must match before it is probed, on top of an always-on block of loopback/link-local/any-local/multicast addresses. **See the deployment must-do below — the default is permissive.** |
| `gateway.backend.failure-grace` | `180s` | **Worker Observability & Claim Latency.** A backend flips `ACTIVE → SUSPECT` only after this many seconds of *continuous* failed health probes (fail-slow); recovery (`SUSPECT → ACTIVE`) stays single-success (recover-fast). Must be `≥ gateway.scheduler.backend-health-interval`; see the `requeue-delay` coupling above. |
| `gateway.backend.defer-demotion-while-busy` | `true` | A failed probe does not demote a backend that is at capacity with at least one `RUNNING` job whose heartbeat is still fresh (dispatch-neutral: an at-capacity backend is already unclaimable). |
| `gateway.backend.defer-demotion-max` | `45m` | Upper bound on how long the deferral above may postpone a demotion; past it, demotion proceeds regardless of capacity/heartbeat freshness. |
| `worker.log.idle-summary-interval-sec` (`WORKER_IDLE_SUMMARY_INTERVAL_SEC`, **Worker module**) | `300` | At most one INFO idle-liveness summary line per this many seconds while a Worker finds no job to claim; `0` disables it. See [§9](#9-worker-protocol). |

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

### 4.4 Prompt Manager (V3, optional)

**Off by default** (`gateway.prompt.enabled: ${PROMPT_MANAGER_ENABLED:false}`) — a stock or freshly
upgraded Gateway keeps booting with today's Worker-JAR-only system prompt and never touches GitLab for
prompts, byte-identical to pre-V3 behavior. This is a deliberate safe-by-default choice (see
`docs/security/feature-prompt-manager-sast-report.md`, finding F-PM-02): defaulting to `true` would have
broken every existing deployment's restart until it was fully configured.

When enabled, the Gateway assembles the LLM's system prompt from Git-hosted content instead of the
Worker's own bundled template, resolved once per Review at `POST /reviews` and persisted immutably —
see [§6.1b](#61b-prompt-manager-and-system-prompt-assembly) for the full model. Full design docs:
[`docs/prompt-manager-architecture.md`](docs/prompt-manager-architecture.md) and
[`docs/prompt-manager-threat-model.md`](docs/prompt-manager-threat-model.md) (PMT-01..25/PMR-01..30).

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `gateway.prompt.enabled` | `PROMPT_MANAGER_ENABLED` | `false` | Master kill-switch. `false` = zero GitLab calls for prompts, exactly today's behavior. |
| `gateway.gitlab.prompt-token` | `GITLAB_PROMPT_TOKEN` | none — required only if enabled | Separate, **read-only** GitLab token (`read_api`/`read_repository`, never `api`/write) used exclusively for prompt fetches, via a dedicated `gitLabPromptRestClient` — never shares a request with the write-scoped `GITLAB_TOKEN`. Presence-only check, same rationale as `GITLAB_TOKEN`. |
| `gateway.prompt.corporate.project` | `PROMPT_CORPORATE_PROJECT` | none — required if enabled | Numeric id or `group/project` path of the **one**, org-wide corporate prompt repo. Always a project reference on the existing `gateway.gitlab.base-url` host — never a URL field (no SSRF sink). |
| `gateway.prompt.corporate.ref` | `PROMPT_CORPORATE_REF` | `main` | Branch of the corporate repo. |
| `gateway.prompt.corporate.base-prompt-path` | `PROMPT_CORPORATE_BASE_PROMPT_PATH` | `prompts/base-system-prompt.md` | Mandatory: task/rules/response-format section. |
| `gateway.prompt.corporate.review-rules-path` | `PROMPT_CORPORATE_REVIEW_RULES_PATH` | `prompts/review-rules.md` | Mandatory: issue-classification/verdict rules section. |
| `gateway.prompt.project.enabled` | — | `true` | Whether to also look for optional per-project sections at all. |
| `gateway.prompt.project.architecture-path` | — | `.ai-review/architecture.md` | Optional: read from the **reviewed project's own default branch** (never the MR's own branch — see [§6.1b](#61b-prompt-manager-and-system-prompt-assembly)), unless overridden per-project. |
| `gateway.prompt.project.code-rules-path` | — | `.ai-review/code-rules.md` | Optional, same sourcing rule. |
| `gateway.prompt.project.overrides.<project_id>` | — | *(empty map)* | Per-project override: point a specific `project_id` at a different repo/ref/paths (e.g. a dedicated subteam prompt repo). Centrally administered in this YAML — not self-declared by the project being reviewed. Several `project_id`s may point at the same override repo. |
| `gateway.prompt.error-handling.on-error` | `PROMPT_ON_ERROR` | `FAIL` | `FAIL` \| `SKIP_OPTIONAL`. Applies only to *optional* project-section failures (network/oversize/invalid content) — corporate-section failures are **always** `FAIL`, not configurable. A missing optional file at a *default* path (plain `404`) is never an error either way. |
| `gateway.prompt.message-format` | — | `MULTI` | `MULTI` (one `ChatMessage` per section) \| `SINGLE` (all sections concatenated). Per-backend override via the `backends.prompt_message_format` column. |
| `gateway.prompt.limits.max-system-prompt-tokens` | — | `6000` | Aggregate cap over all assembled sections; exceeding it is `422 PROMPT_TOO_LARGE`, distinct from `DIFF_TOO_LARGE`. Subtracted from the diff's own token budget dynamically, per Review — `gateway.diff.prompt-reserve` no longer covers the whole system prompt, only the Worker's fixed `user`-template wrapper text. |
| `gateway.prompt.limits.max-file-bytes` | — | `262144` | Per-file streaming read bound (enforced while reading, not after buffering). |
| `gateway.prompt.total-timeout` | — | `20s` | Wall-clock deadline across all GitLab calls for one Review's resolution; a bounded concurrency permit (`max-concurrent-resolutions`, default `4`) additionally caps how many resolutions run at once, so a slow/unavailable GitLab can never exhaust the Gateway's request-handling threads. |

## 5. Deployment

There is no install script in this repository; the primary artifact is a plain executable Spring Boot
jar.

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

### 5.1 Docker / Docker Compose (optional alternative)

A multi-stage root `Dockerfile` (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-jammy`,
non-root user, `HEALTHCHECK` against `GET /health`) and a matching `worker/Dockerfile` are provided,
plus a `docker-compose.yml` that wires up Postgres + both images + a one-shot backend-registration job
in one command:

```bash
export DB_PASSWORD=... CI_TOKEN=$(openssl rand -hex 32) WORKER_TOKEN=$(openssl rand -hex 32) \
       ADMIN_TOKEN=$(openssl rand -hex 32) GITLAB_TOKEN=... LLAMA_MODEL=qwen2.5-coder
docker compose up --build
```

Every environment variable from [§4](#4-configuration) is read by the images the same way as by the bare
jar — no Docker-specific configuration exists. See
[DEPLOYMENT.md §11](DEPLOYMENT.md#11-docker-deployment-verified-both-images) for the full reference
(production topology behind a reverse proxy, the `docker-compose.yml` walkthrough, and a manual
`docker run` recipe) and [worker/README.md §6.3](worker/README.md#63-containerization) for the Worker
image specifically.

## 6. API reference

Every response body below is exactly the corresponding `record` in `com.review.gateway.dto`, serialized
by Jackson with default (camelCase) field naming — no custom naming strategy is configured.

### Role matrix

| Endpoint | Method | Required role | 
|---|---|---|
| `/reviews` | `POST` | `CI` |
| `/reviews/{id}` | `GET` | `CI` |
| `/reviews/{id}` | `DELETE` | `ADMIN` |
| `/jobs/claim`, `/jobs/{id}/heartbeat`, `/jobs/{id}/result`, `/jobs/{id}/fail` | `POST` | `WORKER` |
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
  { "reviewId": 123, "status": "QUEUED", "chunkCount": 1 }
  ```
  `chunkCount` (additive, V2) is the number of file-based chunks the diff was split into — `1` for the
  overwhelming majority of MRs, see [§6.1a](#61a-diff-chunking).
- **`200 OK`** — an existing, still-active Review for the same `(projectId, mergeRequestId, headSha)`
  key was returned instead (dedup, [§8](#8-review-lifecycle)); the body has the same shape, with
  whatever the existing Review's current status is (e.g. `"RUNNING"`).
- **`422 Unprocessable Entity`** — one of three things: the diff couldn't be split into `gateway.diff.max-chunks`
  chunks or fewer, one file's diff is too large to fit a chunk even split at hunk boundaries, or the diff
  is absurdly large (bigger than `max-chunks` chunks could ever hold, rejected before any chunking is
  attempted, CSR-01) — all three use the same response shape:
  ```json
  { "error": "DIFF_TOO_LARGE", "message": "Diff requires 7 chunks, exceeding gateway.diff.max-chunks=5" }
  ```
  Also returned (unchanged from pre-chunking behavior) when `promptVersion` isn't one the Gateway
  recognizes as chunk-context-aware and the diff needs more than one chunk — see
  [§6.1a](#61a-diff-chunking):
  ```json
  { "error": "PROMPT_VERSION_INCOMPATIBLE_WITH_CHUNKING", "message": "promptVersion 'v1' is not chunk-context-aware; this diff requires chunking. Use one of [v2] or submit a smaller diff." }
  ```
- **`400 Bad Request`** — a required field is missing/blank (e.g. empty `diff`):
  ```json
  { "error": "VALIDATION_ERROR", "message": "diff: must not be blank" }
  ```
- **`413 Payload Too Large`** — the whole request body exceeds `gateway.diff.max-request-body-bytes`
  (default 320,000 bytes); see [§6.9](#69-body-size-limits).

### 6.1a Diff chunking

**A diff that exceeds `gateway.diff.max-diff-tokens` is no longer rejected outright — it is split into
file-based chunks and dispatched as one `review_jobs` row per chunk**, each independently claimable by a
Worker/backend, up to `gateway.diff.max-chunks` (default 5) chunks. This closes the gap where ~41% of
real feature-branch MRs used to get `422 DIFF_TOO_LARGE` outright.

- **Splitting** (`DiffChunker`) is file-based: it groups by `diff --git` sections (falling back to plain
  `--- `/`+++ ` unified-diff markers, or treating the whole diff as one indivisible unit if neither is
  present) and bin-packs them next-fit, preserving original file order. A single file whose own diff
  exceeds one chunk's budget is split further at `@@` hunk boundaries, with that file's header replayed
  at the top of each piece.
- **A single-chunk Review (the common case) behaves byte-for-byte identically to pre-chunking releases**:
  same request/response shapes, same prompt, same state-transition timeline, same retry/publish
  behavior. `chunkCount` in the create-Review response is the only new, additive signal.
- **`reviews.status` is derived, not directly set, once a Review has more than one chunk**: the Gateway
  computes it from the set of the Review's job statuses (any chunk `FAILED` → Review `FAILED`; all
  `COMPLETED` → Review `COMPLETED`; any `RUNNING` (or some `COMPLETED`, rest `QUEUED`) → Review
  `RUNNING`; all `QUEUED` → Review `QUEUED`). If one chunk exhausts its retries and fails permanently,
  every other non-terminal sibling chunk is cancelled in the same step — a Worker still running one
  learns to stop via its next heartbeat (`shouldContinue: false`), within `gateway.heartbeat.timeout`.
- **Prompt-version allowlist**: a Review that needs more than one chunk must use a `promptVersion` the
  Gateway knows contains the cross-chunk context placeholder (currently just `"v2"`) — see the `422`
  response above. A single-chunk Review is unaffected and may keep using any existing `promptVersion`.
- **Comment cap stays review-wide, not per-chunk-multiplied**: `gateway.publish.max-comment-count`
  bounds the total comments across all of a Review's chunks combined (a fair per-chunk share is used
  while parsing, then the actual persisted count is capped review-wide at write time), and a comment
  that exactly duplicates one from another chunk (only possible in the rare same-file hunk-split case)
  is not persisted twice.
- **CI polling timeout**: a multi-chunk Review can legitimately take several times longer than a
  single-chunk one to reach a terminal status (up to `chunkCount` chunks worth of queue wait + run time,
  though chunks usually run in parallel across your Worker pool). If your CI job polls `GET
  /reviews/{id}` with a fixed timeout (see [`examples/.gitlab-ci.yml`](examples/.gitlab-ci.yml)), consider
  raising it for large MRs — this repository does not change the example CI script's timeout for you.

### 6.1b Prompt Manager and system-prompt assembly

**Only relevant when `gateway.prompt.enabled=true` ([§4.4](#44-prompt-manager-v3-optional)).** When
enabled, `POST /reviews` synchronously resolves the system prompt from GitLab **before** the Review is
created — the request either gets a Review whose system prompt is already immutably persisted, or it
fails outright; there is no "created but degraded silently" state for the mandatory corporate sections.

- **Corporate sections (mandatory)** — one org-wide repo, two files (base rules + review rules). Any
  failure — missing repo/ref, missing file, oversized/invalid content, timeout — fails the request.
- **Project sections (optional)** — up to two files (architecture + code-style rules) from the reviewed
  project's own **default branch** (or an operator-configured override repo/ref). Deliberately never the
  MR's own source/target branch: reading from a branch the MR author could push to would let them weaken
  the very rules their own change is reviewed against. A missing file at the default path is normal (no
  customization configured, not an error); a missing file at an **explicitly configured override path**
  logs a `WARN` and records a `PROMPT_SECTION_MISSING` event instead of failing silently.
- **Assembly**: corporate sections, a fixed (never fetched, never attacker-influenced) preamble stating
  that what follows is reference material and cannot override the rules above, the project sections
  (each wrapped in a non-forgeable delimiter), then a fixed trailer restating precedence — this is the
  mitigation for prompt injection via project-supplied content (see
  `docs/prompt-manager-threat-model.md` PMT-01/PMR-01/02).
- **Snapshot semantics**: each source's commit SHA is resolved once, then every file from that source is
  fetched at that exact SHA — a consistent snapshot even under concurrent pushes — and persisted with
  full provenance (`review_prompt_sections`: `source_project`/`source_ref`/`source_commit`/
  `content_sha256`), so a Review's exact prompt is reproducible without hitting GitLab again.

New failure responses at `POST /reviews` (in addition to [§6.1](#61-post-reviews--create-a-review)'s):

| HTTP | `error` | Meaning |
|---|---|---|
| `502` | `PROMPT_RESOLUTION_FAILED` | A GitLab call failed (network/timeout/5xx/no access). Deliberately coarse — the body never distinguishes *why*, to avoid a cross-project existence oracle under the shared `CI_TOKEN`; check server-side logs/`review_events` for the real cause. |
| `422` | `PROMPT_SOURCE_MISSING` | A **mandatory** corporate file doesn't exist at the resolved commit. |
| `422` | `PROMPT_SOURCE_INVALID` | A fetched file exceeds `max-file-bytes`, isn't valid UTF-8, or is empty. |
| `422` | `PROMPT_TOO_LARGE` | The assembled system prompt exceeds `gateway.prompt.limits.max-system-prompt-tokens`, or leaves too little diff budget (`min-diff-budget-tokens`). |
| `503` | `PROMPT_RESOLUTION_SATURATED` | Too many concurrent resolutions in flight (`max-concurrent-resolutions`); retry shortly — this bounds worst-case load rather than queuing requests indefinitely. |

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
  Also `409` (different body) if a lock-timeout occurred acquiring the Review row (CSR-17, contended
  with a concurrent claim/sweep) — `{"error": "LOCK_TIMEOUT", "message": "..."}`; retry shortly.
- **`404 Not Found`** — same shape as §6.2.
- **`403 Forbidden`** — a `CI` (or `WORKER`) token was used instead of `ADMIN`.

Cancelling a multi-chunk Review (V2) cascades: every non-terminal chunk job is cancelled in the same
step, and a Worker running any of them learns to stop via its next heartbeat, same as the single-chunk
case.

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
    "payload": { "diff": "diff --git a/Foo.java b/Foo.java\n...", "promptVersion": "v1", "chunkContext": null, "systemMessages": null }
  }
  ```
  As of diff chunking (V2), `jobId` identifies one **chunk** of the Review (`review_jobs` is now 1:N per
  Review), and `payload.diff` is that chunk's slice of the original diff, not necessarily the whole
  thing — see [§6.1a](#61a-diff-chunking). `payload.chunkContext` (additive) is the rendered cross-chunk
  header text (`null` for the common single-chunk case); the Worker substitutes it into the resolved
  prompt template's `{{CHUNK_CONTEXT}}` placeholder exactly like `diff`/`{{DIFF}}`, with no other
  chunk-awareness required on its side (it stays a fully stateless, chunk-ignorant HTTP client).
  `payload.systemMessages` (V3, Prompt Manager) is `null` for a legacy Review or when
  `gateway.prompt.enabled=false` (use the Worker's own bundled template, exactly pre-V3 behavior) — a
  non-null value is one or more already-fully-assembled system-prompt strings to wrap verbatim into
  `ChatMessage(role=system, ...)`, **never** run through the `{{DIFF}}`/`{{CHUNK_CONTEXT}}` substitution
  logic (that stays scoped to the `user`-role template only). See
  [§6.1b](#61b-prompt-manager-and-system-prompt-assembly) and [§9](#9-worker-protocol).
- **`204 No Content`** — nothing to claim right now. This covers five indistinguishable situations by
  design: the queue is empty, the named backend is not `ACTIVE`, the backend is already at capacity, a
  claimed job turned out to belong to a Review that had already gone `CANCELLED`/`OBSOLETE` moments
  earlier (the lock-free "doomed job" courtesy check, CSR-17 — best-effort only; a genuinely in-flight
  claim that misses this check still gets cancelled via its next heartbeat, at worst one heartbeat
  interval later), or the claim's job-row lock timed out waiting on a concurrent claim/cancel (mapped to
  `204`, not an error, since it means exactly the same thing to the Worker as "nothing available right
  now"). A Worker should treat `204` as "wait and poll again" in all cases.

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

### 6.6a `POST /jobs/{id}/fail` — report a Worker-side failure (Worker)

**Worker Observability & Claim Latency.** Best-effort, latency-only: it lets the Gateway requeue/fail a
job in well under a second instead of waiting out the passive stale-heartbeat sweep (up to ~210s). The
Worker sends this **once**, synchronously, right after it gives up on a job (`AbandonJobException`/
`LlamaException`) and **before** it claims the next one — never as a retry loop, and never for a job that
was cancelled/superseded via heartbeat (that case needs no report: the Gateway already owns that
transition).

```bash
curl -s -X POST http://localhost:8080/jobs/456/fail \
  -H "Authorization: Bearer $WORKER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "workerId": "worker-mac-mini-01", "reason": "LLM_TIMEOUT", "detail": "llama-server did not respond within the configured timeout" }'
```

Request fields (`FailJobRequest`): `workerId` (non-blank, `≤64` chars, `^[A-Za-z0-9._:-]{1,64}$`),
`reason` (non-blank, `≤32` chars — one of `LLM_EMPTY_RESPONSE`/`LLM_ERROR`/`LLM_TIMEOUT`/
`LLM_RESPONSE_TOO_LARGE`/`PROMPT_INVALID`/`WORKER_ERROR`; the Gateway whitelist-parses this and treats
any other value as `UNKNOWN` — it **never** rejects an unrecognized code with `400`, so an
independently-versioned Worker fleet degrades safely), `detail` (optional, `≤500` chars — a fixed,
Worker-side-constant description **never** derived from an exception message; sanitized and truncated to
200 chars server-side before it can reach a log line, `review_events.details`, or
`review_jobs.last_error`).

- **`200 OK`** — `{ "accepted": true }`, identical whether the report was actually applied (job was
  `RUNNING` and owned — it is now `QUEUED`/`FAILED`) or was an idempotent no-op (job already left
  `RUNNING`). The Worker has no use for the distinction.
- **`404 Not Found`** / **`403 Forbidden`** — same opaque semantics as heartbeat/result (unknown `jobId` /
  `workerId` does not match the current owner). Unlike `/result`, the `200` response **never** carries a
  `reviewId` or Review status.
- `reason`/`detail` are audit-only: they are recorded on the resulting `RETRY`/`FAILED` `review_events`
  row (`details` prefixed `"worker-reported: reason=<CODE>[; detail=<sanitized>]"`) and never influence
  the requeue-vs-fail decision, which stays exactly `attempts >= gateway.retry.max-attempts` — "retry
  logic lives only in the Gateway" holds even though the Worker now speaks about failures.
- Delivery is best-effort: a single attempt, no retry/backoff of its own. If this call fails or an older
  Gateway build returns `404` for the path itself, the Worker logs a `WARN`, counts it, and moves on — the
  passive stale-heartbeat sweep remains the correctness backstop regardless.
- `review_jobs.not_before` (set on the requeue branch to `now() + gateway.retry.requeue-delay`, computed
  against the database clock) prevents a fast-fail storm: without it, a deterministic worker-side failure
  could burn all `gateway.retry.max-attempts` attempts within seconds. See §4.2's note on
  `gateway.retry.requeue-delay` for the coupling to `gateway.backend.failure-grace`.

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
    "lastSeen": "2026-07-13T10:05:00Z",
    "probeFailedSince": null
  }
]
```
`running` is derived live from the count of currently-`RUNNING` jobs on that backend — there is no
separate counter to drift out of sync. The backend's URL is deliberately **not** included in this view.
`lastSeen` now means "last time this backend answered a health probe **successfully**" (previously
written on every probe pass, success or failure). `probeFailedSince` (Worker Observability & Claim
Latency) is non-`null` while a continuous failed-probe streak is in progress but hasn't yet reached
`gateway.backend.failure-grace` (still `ACTIVE`) or while a failed-but-at-capacity backend's demotion is
being deferred (§10) — an operator can read "failing for 2 of 3 minutes' grace" directly from this field
without querying PostgreSQL.

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
  "retries": 5,
  "ownershipMismatches": { "heartbeat": 0, "result": 0, "fail": 0 },
  "workerFailureReportsIgnored": 0,
  "positionAnchoringEnabled": true,
  "positionsAnchored": 142,
  "positionsUnresolved": 9,
  "diffRefsUnavailable": 0,
  "positionRejectedByGitLab": 1
}
```
`ownershipMismatches` (broken down by endpoint), `workerFailureReportsIgnored` (Worker Observability &
Claim Latency, WOR-03), and the four Diff Position Anchoring counters — `positionsAnchored` (a comment was
successfully anchored to a diff position), `positionsUnresolved` (a comment had a `file`/`line` but no
matching diff line was found), `diffRefsUnavailable` (`fetchDiffRefs` came back empty — network, stale MR
state, or an insufficiently-scoped token), `positionRejectedByGitLab` (GitLab 400'd a positioned POST and
the position-less fallback ran) — are all process-local, in-memory counters that reset on a Gateway
restart, unlike every other field on this endpoint, which is derived from PostgreSQL. This is deliberate
for the same reason in both cases: writing a `review_events` row for every one of these would turn the
endpoint into an authenticated, unbounded `INSERT` primitive (for a worker-token holder in the first case,
for anyone triggering a publish pass in the second). Every failure mode of Diff Position Anchoring is
silent by design (it always falls back to a plain note, never fails a Review) — these four counters are
the only way to notice "anchoring stopped working" without reading logs.

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
| 413 | `PAYLOAD_TOO_LARGE` | Request body exceeds the configured edge cap (`POST /reviews`, `POST /jobs/{id}/result`, or `POST /jobs/{id}/fail`). (Written by `RequestBodySizeLimitFilter`, not `GlobalExceptionHandler`.) |
| 422 | `DIFF_TOO_LARGE` | Diff exceeds the token budget. |
| 500 | `INTERNAL_ERROR` | Anything unmapped; the real exception is logged server-side only. |

`GlobalExceptionHandler` also maps a `JOB_NOT_CLAIMABLE` (409) code, but as of this codebase **nothing
throws it** — `POST /jobs/claim` always responds `200`/`204`, never an error, for an unknown/inactive/
at-capacity backend. This mapping is dead code kept for forward-compatibility; do not expect to see it.

### 6.9 Body size limits

A servlet filter (`RequestBodySizeLimitFilter`, registered ahead of Spring Security) rejects an
oversized body based on the `Content-Length` header, before authentication or JSON parsing: `320,000`
bytes for `POST /reviews` (`gateway.diff.max-request-body-bytes`, CSR-02 — see [§4.2](#42-everything-else-has-a-working-default)),
`500,000` bytes for
`POST /jobs/{id}/result` (`gateway.publish.max-request-body-bytes`), and `4,096` bytes for
`POST /jobs/{id}/fail` (`gateway.job.max-fail-body-bytes`). `POST /jobs/claim` and
`POST /jobs/{id}/heartbeat` bodies are not size-capped (their DTOs are small and Worker-authenticated
only — a documented, accepted low-risk gap, see the SAST report's F03-03 finding). This
`Content-Length`-based check does not catch a client that both omits `Content-Length` and streams an
unbounded chunked body; that residual gap is accepted at this project's scale (internal CI/Worker
clients, not a public API).

The filter matches on the **decoded, normalized** request path (not the raw, possibly percent-encoded
`request.getRequestURI()`), so a percent-encoded path segment (e.g. `POST /jobs/1/%66ail` decoding to
`/jobs/1/fail`) cannot bypass any of the three caps above — this mirrors exactly how Spring MVC itself
resolves the path it ultimately routes on.

## 7. GitLab CI integration

A working `.gitlab-ci.yml` job. It uses only fields that exist on `CreateReviewRequest` and GitLab's own
predefined merge-request-pipeline variables (`CI_PROJECT_ID`, `CI_MERGE_REQUEST_IID`, `CI_COMMIT_SHA`,
`CI_MERGE_REQUEST_DIFF_BASE_SHA`). Configure `REVIEW_GATEWAY_URL` and `REVIEW_GATEWAY_CI_TOKEN` as
masked/protected CI/CD variables in the GitLab project (or group) settings — `REVIEW_GATEWAY_CI_TOKEN`
must equal the Gateway's configured `CI_TOKEN`. A ready-to-copy version of this file (plus an optional
job that blocks the pipeline until the review is actually `PUBLISHED`) is at
[`examples/.gitlab-ci.yml`](examples/.gitlab-ci.yml) — copy it into the *target* project being
reviewed, not into this repository.

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
| `RUNNING → QUEUED` | Heartbeat timeout, max-duration backstop, **or** a Worker-reported failure (`POST /jobs/{id}/fail`) — any of the three, **and** attempts remaining (retry). |
| `RUNNING → FAILED` | Any of the three triggers above with attempts exhausted, **or** the result could not be parsed at all. |
| `COMPLETED → PUBLISHED` | All parsed comments were successfully posted to the MR (as native diff threads where a comment's `file`/`line` could be resolved against the stored diff — Diff Position Anchoring, `gateway.publish.position-anchoring-enabled` — otherwise as a plain top-level note, identical to pre-feature behavior). |
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
- **Worker-reported failure** (`POST /jobs/{id}/fail`, [§6.6a](#66a-post-jobsidfail--report-a-worker-side-failure-worker)):
  best-effort, latency-only — collapses the above sweeps' passive wait (up to ~210s) down to well under a
  second for the common case of a Worker that is still alive enough to report its own failure.
  `gateway.retry.requeue-delay` (default 90s) then bounds how soon the requeued job becomes claimable
  again, preventing a fast-fail storm.

`GET /reviews/{id}` exposes exactly the current `status` value from this table, plus `attempts` (so a
caller can tell "will retry" from "exhausted retries" once a Review reaches `FAILED`).

**V2 (diff chunking) note:** the table above describes per-**job** transitions (each chunk job has its
own independent `QUEUED → RUNNING → COMPLETED/FAILED` lifecycle, including its own retry/timeout
handling). The **Review's** `status` — what `GET /reviews/{id}` actually returns — is *derived* from the
set of its jobs' statuses (any `FAILED` → `FAILED`; all `COMPLETED` → `COMPLETED`; any `RUNNING` (or some
`COMPLETED`, rest `QUEUED`) → `RUNNING`; all `QUEUED` → `QUEUED`), applied through the same
`StateMachine`/audit-event mechanism. `attempts` in the response is the max across the Review's jobs. For
the common single-chunk case this collapses exactly onto the table above — one job, one status, no
observable difference from pre-chunking behavior.

## 9. Worker protocol

This section is a guide for implementing a Worker (this repository does not ship a Worker
implementation — only the Gateway). A Worker is a stateless HTTP client with no GitLab or PostgreSQL
access at all.

1. **Claim.** `POST /jobs/claim` with your registered backend's `name` (as `backendId`) and a
   self-chosen `workerId` string.
   - `200` → you have `jobId`, `reviewId`, and a `payload` with `diff` + `promptVersion` +
     `chunkContext` (V2, diff chunking — `null` unless this job is one chunk of a larger, split diff) +
     `systemMessages` (V3, Prompt Manager — `null` unless the Gateway has one enabled and resolved).
     Substitute `chunkContext` into the resolved template's `{{CHUNK_CONTEXT}}` placeholder exactly like
     `diff`/`{{DIFF}}` if the template has one and it's non-null. If `systemMessages` is non-null, use
     each string **verbatim** as its own `system`-role chat message (or concatenate them if your backend
     only accepts one `system` message) instead of your own bundled system template — do **not** run
     these strings through any placeholder-substitution logic; they are already fully assembled. If it's
     `null`, fall back to your own bundled system template exactly as before V3. You never need to know
     whether `jobId` represents a whole Review or one chunk of it — treat every claimed job identically.
   - `204` → nothing to claim right now (empty queue, your backend isn't `ACTIVE`/is at capacity, or a
     transient lock contention). Wait (e.g. a few seconds) and poll again.
2. **Run inference** against your local `llama-server` using the claimed
   `diff`/`promptVersion`/`chunkContext`/`systemMessages`.
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
5. **On abandonment, report it.** If you give up on a job (llama-server error/timeout/oversized response,
   an invalid `promptVersion`, etc.) **without** ever calling step 4, send a single best-effort
   `POST /jobs/{id}/fail` ([§6.6a](#66a-post-jobsidfail--report-a-worker-side-failure-worker)) with the
   same `workerId`, a `reason` code, and — optionally — a short, **fixed, non-sensitive** `detail` string
   you chose ahead of time per failure class. **Never** derive `detail` from an exception message: a JSON
   parse failure on a malformed llama-server response can quote that response's content verbatim, and
   that must never leave your process. Do **not** report if the job was aborted via a `shouldContinue:
   false`/`403`/`404` heartbeat response — the Gateway already owns that transition. One attempt only, no
   retry/backoff of your own; swallow any failure and move on to the next claim.

What a Worker deliberately does **not** need to know or have access to: GitLab (no API calls, no
token), PostgreSQL (no driver, no credentials, no schema knowledge), retry counting or the max-attempts
limit, deduplication, or how the raw response gets parsed into structured comments — all of that is the
Gateway's responsibility.

### Worker log visibility (implementation guidance, non-normative)

The reference Worker implementation in `worker/` (see [worker/README.md](worker/README.md)) emits one
INFO line per heartbeat tick while a job is in progress (`"Job in progress (jobId=..., elapsedSec=...,
heartbeats=...)"`), one INFO line at the start of inference (sizes/counts only — never diff/prompt/
response content), and an INFO only when a job is actually claimed (the empty-poll case drops to DEBUG),
plus a rate-limited idle-liveness summary (`worker.log.idle-summary-interval-sec`, default 300s) so a
genuinely idle Worker still proves liveness without spamming one line per poll. If you implement your own
Worker, matching this log shape is recommended but not required by the API contract.

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
`gateway.scheduler.backend-health-interval` tick (default 60s). Capacity for claim purposes is always the
live count of currently-`RUNNING` jobs on that backend versus its configured `capacity` — there is no
separate counter to go stale.

**Fail-slow / recover-fast (Worker Observability & Claim Latency).** A single failed probe no longer
demotes a backend: `ACTIVE → SUSPECT` now requires a **continuous** failed-probe streak of at least
`gateway.backend.failure-grace` (default 180s), tracked in the restart-safe `backends.probe_failed_since`
column so the streak survives a Gateway restart. `SUSPECT → ACTIVE` stays **single-success**
(recover-fast, unchanged). `last_seen` is now updated only on a *successful* probe (previously written
unconditionally). Two independent safeguards work together here:

- **At-capacity deferral.** A failed probe does not demote a backend that is at capacity with at least
  one `RUNNING` job whose heartbeat is still fresh (`gateway.backend.defer-demotion-while-busy`, default
  `true`) — dispatch-neutral by construction, since an at-capacity backend is already unclaimable, and
  kept dispatch-neutral *over time* too: the claim path independently declines any backend whose
  `probe_failed_since` streak is already past `failure-grace`, regardless of its persisted `status`.
  Capped by `gateway.backend.defer-demotion-max` (default 45m) so a backend held busy indefinitely (e.g.
  by a misbehaving Worker) cannot defer demotion forever.
- **Probe I/O runs outside any database transaction** (previously it ran inside one), which is what makes
  raising `gateway.backend.read-timeout` from `5s` to `10s` safe — a slow/hanging probe no longer pins a
  Hikari connection.

If `gateway.scheduler.heartbeat-check-interval`/`backend-health-interval` findings ever WARN
`"Queue stalled: N job(s) QUEUED but 0 eligible ACTIVE backend(s)"`, that condition now fires whenever
there is no backend that is both `ACTIVE` **and** not past a grace-elapsed failure streak — not merely "no
`ACTIVE` backend" — so a deferred-but-effectively-dead backend can't silently suppress the alarm.

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
length; it never contains a diff, chunk-context text, or raw model response.

**V2 (diff chunking):** `review_events` gained nullable `chunk_index`/`job_id` columns (audit/debug
only, no constraint). A chunked Review now typically has *two* rows per lifecycle event of the same
type — one attributed to the specific job/chunk (`chunk_index`/`job_id` set) and one for the Review's
own derived-status transition (`chunk_index`/`job_id` null) — for the still-common single-chunk case
this is unchanged in spirit but does mean two `COMPLETED`/`FAILED` rows (one per level) rather than one;
both are expected, not a bug. **Exception:** `GET /metrics`'s `retries` count deliberately counts only
the job-level `RETRY` event (`job_id IS NOT NULL`), not both — counting both would double-report the
retry count for the common single-chunk case, since a retry always writes both a job-level and a
review-level `RETRY` row together. If you query `review_events` directly for retry analysis, filter on
`job_id IS NOT NULL` for the same reason.

### Cancelling a review

`DELETE /reviews/{id}` with the `ADMIN` token ([§6.3](#63-delete-reviewsid--admin-cancel)). Cancels every
non-terminal chunk job in the same step for a multi-chunk Review.

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
- **Prompt Manager (V3, optional) — split GitLab credentials and prompt-injection defenses.** When
  enabled, prompt fetches use a **separate, read-only** GitLab token (`GITLAB_PROMPT_TOKEN`) over a
  dedicated HTTP client, never the write-scoped `GITLAB_TOKEN` used for publishing comments — a leak of
  one cannot be used to do the other's job. Project-supplied prompt content (untrusted by design, since
  it's controlled by the same population whose code is under review) is read only from a project's own
  default branch, wrapped in a non-forgeable delimiter, and framed by a fixed preamble/trailer the LLM
  is told takes precedence. Full threat model: `docs/prompt-manager-threat-model.md` (PMT-01..25,
  PMR-01..30) and its companion `docs/security/feature-prompt-manager-sast-report.md`.
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
- **`413 Payload Too Large`.** The request body exceeds `gateway.diff.max-request-body-bytes` (320,000
  bytes default, `POST /reviews`, CSR-02) or `gateway.publish.max-request-body-bytes` (500,000 bytes
  default, `POST /jobs/{id}/result`). Either the diff/response is genuinely too large (consider sending only
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
- **Application won't start after setting `PROMPT_MANAGER_ENABLED=true`.** Provision the other two
  required Prompt Manager variables too — `GITLAB_PROMPT_TOKEN` and `PROMPT_CORPORATE_PROJECT` have no
  default once the feature is enabled (see [§4.4](#44-prompt-manager-v3-optional)).
- **`502 PROMPT_RESOLUTION_FAILED` / `422 PROMPT_SOURCE_MISSING` / `422 PROMPT_SOURCE_INVALID` / `422
  PROMPT_TOO_LARGE` / `503 PROMPT_RESOLUTION_SATURATED` on `POST /reviews`.** Only possible with Prompt
  Manager enabled — see the table in [§6.1b](#61b-prompt-manager-and-system-prompt-assembly) for what
  each one means and check server-side logs/`review_events` for the underlying cause (the response body
  is deliberately generic, PMR-26).
