# Deployment Runbook — Review Gateway + LLM Worker

This is a single, end-to-end runbook for standing up the whole AI Code Review Platform from zero:
PostgreSQL, the Review Gateway, one Worker/`llama-server` pair, and the GitLab CI integration that
triggers reviews and receives the resulting MR comments.

Every property name, endpoint, table/column, and CLI flag below is taken directly from this
repository's code and existing docs — `src/main/java/com/review/gateway/**`, `worker/src/main/java/
com/review/worker/**`, `src/main/resources/application.yml`, `worker/src/main/resources/
application.yml`, `src/main/resources/db/migration/V1__initial_schema.sql`, the root
[`README.md`](README.md), [`worker/README.md`](worker/README.md), and `docs/implementation-architecture.md`
/ `docs/worker-architecture.md`. Where an integration point the platform needs does **not** exist in the
developed code, this document says so explicitly in a clearly marked `STUB` block, with the manual
mechanism to use instead — nothing here papers over a gap.

**Example addresses used throughout** (substitute your own): GitLab instance `https://gitlab.local`;
`llama-server` host `http://192.168.1.101` (see the one-line port note in
[§2](#2-prerequisites)); Gateway host `gateway.internal`; PostgreSQL host `db.internal`. These are
illustrative hostnames chosen for this runbook, not values baked into the code.

## Table of contents

1. [Architecture overview](#1-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Step 1: PostgreSQL](#3-step-1-postgresql)
4. [Step 2: Deploy the Gateway](#4-step-2-deploy-the-gateway)
5. [Step 3: Register the llama backend](#5-step-3-register-the-llama-backend)
6. [Step 4: Deploy the Worker](#6-step-4-deploy-the-worker)
7. [Step 5: GitLab integration](#7-step-5-gitlab-integration)
8. [Step 6: End-to-end smoke test](#8-step-6-end-to-end-smoke-test)
9. [Operations quick reference](#9-operations-quick-reference)
10. [Config file appendix](#10-config-file-appendix)

---

## 1. Architecture overview

```
                          Authorization: Bearer $CI_TOKEN
  ┌───────────────┐       POST /reviews, GET /reviews/{id}        ┌─────────────────────────┐
  │  GitLab CI job │ ─────────────────────────────────────────▶  │                         │
  │ (MR pipeline)  │                                              │                         │
  └───────────────┘                                              │                         │
                                                                   │                         │
  ┌───────────────┐   Authorization: Bearer $ADMIN_TOKEN          │      Review Gateway     │
  │  Admin/operator│ ─────────────────────────────────────────▶  │      (single instance)  │
  │ GET /backends  │   DELETE /reviews/{id}, GET /metrics         │                         │
  └───────────────┘                                              │                         │
                                                                   │   JDBC (DB_USER/PASS)   │
                          Authorization: Bearer $WORKER_TOKEN      │           │             │
  ┌───────────────┐       POST /jobs/claim                        │           ▼             │
  │   LLM Worker   │ ◀────────────────────────────────────────▶  │      PostgreSQL          │
  │  (1:1 with one │       POST /jobs/{id}/heartbeat              │  reviews, review_inputs, │
  │  llama-server) │       POST /jobs/{id}/result                 │  review_jobs,            │
  └───────┬───────┘                                                │  review_results,         │
          │                                                        │  review_comments,        │
          │ POST /v1/chat/completions                              │  review_events, backends │
          ▼                                                        └───────────┬──────────────┘
  ┌───────────────┐                                                            │
  │  llama-server  │                                          PRIVATE-TOKEN: $GITLAB_TOKEN
  │ (OpenAI-Chat-  │                                          POST /projects/{id}/merge_requests/
  │  Completions-  │                                          {iid}/discussions
  │  compatible)   │                                                            │
  └───────────────┘                                                            ▼
                                                                       ┌─────────────────┐
                                                                       │  GitLab (MR)     │
                                                                       │  discussion/     │
                                                                       │  comment posted  │
                                                                       └─────────────────┘
```

Who talks to whom, and with which credential:

| From | To | Credential |
|---|---|---|
| GitLab CI job | Gateway (`POST /reviews`, `GET /reviews/{id}`) | `CI_TOKEN` (bearer) |
| Admin/operator | Gateway (`DELETE /reviews/{id}`, `GET /backends`, `GET /metrics`) | `ADMIN_TOKEN` (bearer) |
| Worker | Gateway (`POST /jobs/claim`, `/jobs/{id}/heartbeat`, `/jobs/{id}/result`) | `WORKER_TOKEN` (bearer) — this is the Worker's own `GATEWAY_API_KEY` |
| Worker | `llama-server` (`POST /v1/chat/completions`) | none (unauthenticated OpenAI-compatible API, loopback/private-network by convention) |
| Gateway | PostgreSQL | `DB_USER` / `DB_PASSWORD` |
| Gateway | GitLab API (`POST /projects/{id}/merge_requests/{iid}/discussions`) | `GITLAB_TOKEN` (`PRIVATE-TOKEN` header) |
| Gateway | `llama-server` (`GET {backend.url}/health`, health probe only — **never** for inference) | none |

The Gateway is the sole owner of Review state and business logic (queue, retry, dedup, timeout,
publishing); PostgreSQL is the single source of truth; the Worker is a stateless HTTP client with no
GitLab or database access of its own. See the root [README.md §1](README.md#1-what-it-is) and
[worker/README.md §1](worker/README.md#1-overview) for the full architectural rationale.

## 2. Prerequisites

| Component | Requirement | Source |
|---|---|---|
| Java | 21 | Both `pom.xml` (root, Gateway) and `worker/pom.xml` target Java 21, on the Spring Boot `3.5.16` parent (pinned to the same line on both). |
| Maven | 3.9+ | Both projects build with `spring-boot-maven-plugin`. |
| PostgreSQL | Tested against 14.22; 12+ is a reasonable practical floor (no Postgres-14-specific SQL features are used) | Root [README §2](README.md#2-requirements). Only the Gateway touches PostgreSQL — the Worker has no JDBC dependency at all. |
| `llama-server` (llama.cpp) | An already-running, OpenAI-Chat-Completions-compatible HTTP server | **Out of scope for this repository** — installing/running `llama.cpp` itself is not something either component's code does. This runbook only documents the exact API path the Worker calls against it (`POST /v1/chat/completions`, verified from `worker/src/main/java/com/review/worker/llama/LlamaClient.java`) and the health path the Gateway probes it on (`GET {url}/health`, verified from `BackendProberImpl`). |
| Docker | **Not required anywhere**, for either component | Both test suites use in-process fakes (Zonky embedded PostgreSQL for the Gateway, real-socket `okhttp3:mockwebserver` for the Worker) — no Testcontainers, no external services needed to build or test. |

**Port assumption for the `llama-server` example address.** The Worker's own `llama.url` property
defaults to `http://127.0.0.1:8000` (`worker/src/main/resources/application.yml` /
`WorkerProperties.Llama.url`) — i.e. **this codebase's own convention is port 8000**, not `llama.cpp`'s
upstream server default of 8080. Since `192.168.1.101` is a remote host in this runbook (not the
Worker-default loopback), the operator must set `LLAMA_URL` explicitly regardless of any default; this
runbook uses `http://192.168.1.101:8000` throughout to match the Worker's own documented convention —
**substitute `:8080` (or whatever port you actually start `llama-server` on) if your instance uses
`llama.cpp`'s own default instead.**

### Network matrix

| Source | Destination | Port / protocol | Purpose |
|---|---|---|---|
| GitLab CI runner | Gateway | `443/HTTPS` via a reverse proxy in front of the Gateway's plain-HTTP `server.port` (`8080` default) | `POST /reviews`, `GET /reviews/{id}` |
| Admin operator | Gateway | `443/HTTPS` via the same reverse proxy | `DELETE /reviews/{id}`, `GET /backends`, `GET /metrics`, `GET /health` |
| Worker | Gateway | `443/HTTPS` via the same reverse proxy (or loopback plain HTTP only in dev, via `worker.allow-insecure-gateway=true`) | `POST /jobs/claim`, `/jobs/{id}/heartbeat`, `/jobs/{id}/result` |
| Gateway | PostgreSQL | `5432/TCP` (JDBC) | `spring.datasource.url` |
| Gateway | GitLab API | `443/HTTPS` (`gateway.gitlab.base-url` — startup fails if not `https://`) | Posting MR discussions |
| Gateway | `llama-server` | operator-configured port / plain HTTP | Health probe only (`GET {backend.url}/health`); the Gateway never calls the chat-completions endpoint itself |
| Worker | `llama-server` | operator-configured port / plain HTTP (example: `192.168.1.101:8000`, see the port note above) | `POST /v1/chat/completions` |
| (nothing external) | Worker's own Actuator | `127.0.0.1:8081` (loopback only, hardcoded `server.address`) | `/actuator/health`, `/actuator/prometheus` — not reachable off the Worker's own host by design |

> **Reverse proxy / TLS note.** Neither the Gateway nor the Worker configures `server.ssl.*` — both
> listen on plain HTTP internally (root [README §4.3](README.md#43-deployment-must-dos-from-docssecurityfeature-03-sast-reportmd)).
> Every inbound hop that isn't loopback (CI → Gateway, Worker → Gateway, Admin → Gateway) must have TLS
> terminated by a reverse proxy in front of the Gateway; this runbook assumes `https://gateway.internal`
> reaches such a proxy.

### Token generation

The Gateway requires **four** secrets, each independently checked by `GatewayProperties.validateOnStartup()`
to be **at least 32 characters** (startup fails otherwise, matching the code's own `MIN_SECRET_LENGTH = 32`):

```bash
export CI_TOKEN=$(openssl rand -hex 32)
export WORKER_TOKEN=$(openssl rand -hex 32)
export ADMIN_TOKEN=$(openssl rand -hex 32)
```

The fourth, `GITLAB_TOKEN`, is **not generated locally** — it is a GitLab project or group access token
you create in GitLab itself (`https://gitlab.local` → project/group → **Settings → Access Tokens**),
scoped to the `api` scope (needed to call the Discussions API the Gateway uses —
`GitLabClientImpl.postDiscussion`) with at least the **Developer** role on the projects under review (the
minimum GitLab role that can create MR discussions). The root README's own recommendation (from the
threat model) is to make this token **project- or group-scoped and expiring**, not a full personal access
token — this is an operational choice made when the token is issued in GitLab, not something the
application enforces.

**How the Worker gets the `WORKER_TOKEN` value.** There is no token-exchange mechanism — the Worker's own
`GATEWAY_API_KEY` environment variable must simply be set to the **exact same value** as the Gateway's
`WORKER_TOKEN` (verified: `worker.gateway.api-key` is sent as `Authorization: Bearer` on every Worker→Gateway
call, and the Gateway compares it against its single configured `WORKER_TOKEN`). Copy the value out-of-band
(a secrets manager, or manually) when provisioning each Worker host.

## 3. Step 1: PostgreSQL

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE review_gateway WITH LOGIN PASSWORD 'change-me-to-a-real-secret';
CREATE DATABASE review_gateway OWNER review_gateway;
SQL
```

**What Flyway does on first start.** The Gateway has `spring.flyway.enabled: true` and
`spring.flyway.locations: classpath:db/migration` (`src/main/resources/application.yml`); on its very
first successful connection it automatically applies `V1__initial_schema.sql`, which creates:

| Table | Purpose (from the migration's own comments) |
|---|---|
| `reviews` | Aggregate root / queue owner — sole source of truth for `status` (`NEW/QUEUED/RUNNING/COMPLETED/PUBLISHED/FAILED/CANCELLED/OBSOLETE`), plus `project_id`, `merge_request_id`, `head_sha`, `base_sha`, `prompt_version`, `priority`, `attempts`. |
| `review_inputs` | Immutable input payload (`diff`, `prompt_version`, SHAs) — 1:1 with `reviews`. |
| `backends` | The `llama-server` registry: `name` (unique — **this is the value a Worker sends as `backendId`**), `url`, `model`, `capacity`, `status` (`ACTIVE/SUSPECT/MAINTENANCE/OFFLINE`), `last_seen`. |
| `review_jobs` | Current execution record, 1:1 with `reviews`: `backend_id`, `worker_id`, `heartbeat_at`, `claimed_at`, `started_at`, `finished_at`, `last_error`. |
| `review_results` | Raw model response (mandatory) + token/duration/model metadata, 1:1 with `reviews`. |
| `review_comments` | Parsed `{file_path, line_number, severity, comment}` rows + `discussion_id`/`published_at` for idempotent GitLab publishing. |
| `review_events` | Append-only audit trail (`CREATED/CLAIMED/RUNNING/HEARTBEAT/RETRY/COMPLETED/PUBLISHED/FAILED/OBSOLETE/CANCELLED`). |

`spring.jpa.hibernate.ddl-auto: validate` means Hibernate never generates DDL of its own — the schema is
exclusively Flyway-owned; nothing further to run by hand beyond granting the role above access.

**Grants** (per the SAST report's recommendation, root [README §4.3](README.md#43-deployment-must-dos-from-docssecurityfeature-03-sast-reportmd)
item 4 — restrict `review_events` to append-only so the audit trail cannot be silently rewritten):

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON
    reviews, review_inputs, backends, review_jobs, review_results, review_comments
    TO review_gateway;
GRANT SELECT, INSERT ON review_events TO review_gateway;   -- no UPDATE/DELETE
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO review_gateway;
```

(The tables use `GENERATED BY DEFAULT AS IDENTITY`, which Postgres backs with a sequence per table —
grant `USAGE`/`SELECT` on those too, or simply own the tables as the same role that ran the migration, in
which case no explicit grant is needed.)

**Exact connection properties** the Gateway reads (`spring.datasource.*`, `application.yml`):

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/review_gateway` |
| `spring.datasource.username` | `DB_USER` | none — required |
| `spring.datasource.password` | `DB_PASSWORD` | none — required |
| `spring.datasource.hikari.maximum-pool-size` | *(no env var — hardcoded)* | `20` |

For a non-loopback PostgreSQL host, append `?sslmode=require` to `DB_URL` (SAST-report must-do — the
default `localhost` URL is accepted without TLS, a remote one must not be):

```
DB_URL=jdbc:postgresql://db.internal:5432/review_gateway?sslmode=require
```

## 4. Step 2: Deploy the Gateway

```bash
mvn -q -DskipTests package
# artifact: target/review-gateway-1.0.0-SNAPSHOT.jar
```

### 4.1 Environment file

`/etc/review-gateway/review-gateway.env` (full production set — see
[§10](#10-config-file-appendix) for a copy-pasteable block):

```bash
# --- Database (Step 1) ---
DB_URL=jdbc:postgresql://db.internal:5432/review_gateway?sslmode=require
DB_USER=review_gateway
DB_PASSWORD=change-me-to-a-real-secret

# --- Bearer tokens (§2 Token generation) ---
CI_TOKEN=<32+ char random value>
WORKER_TOKEN=<32+ char random value>
ADMIN_TOKEN=<32+ char random value>

# --- GitLab (§2 Token generation; publishing MR comments) ---
GITLAB_BASE_URL=https://gitlab.local/api/v4
GITLAB_TOKEN=<GitLab project/group access token, api scope>

# --- Backend network restriction (SAST-report must-do #1): tighten from the
# permissive ".*" default to the actual llama-server network. The Gateway only
# reaches this host for health probes (GET {url}/health), never for inference. ---
BACKEND_ALLOWED_HOST_PATTERN=^192\.168\.1\.101$
```

`GatewayProperties.validateOnStartup()` will refuse to start if any of the four secrets above is missing
or under 32 characters, or if `GITLAB_BASE_URL` doesn't start with `https://`.

### 4.2 systemd unit

No unit file ships in this repository (root [README §5](README.md#5-deployment) confirms: "this
repository does not ship a unit file"); the requirements document specifies a single Gateway instance
with `Restart=always`, which the example below follows:

```ini
# /etc/systemd/system/review-gateway.service
[Unit]
Description=Review Gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=review-gateway
Group=review-gateway
EnvironmentFile=/etc/review-gateway/review-gateway.env
ExecStart=/usr/bin/java -jar /opt/review-gateway/review-gateway-1.0.0-SNAPSHOT.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`RUNNING` jobs are never reset on a Gateway restart (no startup reconciliation touches them) — a Worker
that is still alive and heartbeating is unaffected by this service bouncing between one of its
heartbeats and the next (root [README §5](README.md#5-deployment)).

### 4.3 Health check verification

```bash
curl -s http://localhost:8080/health
# {"status":"UP"}  -- public, custom endpoint (HealthController), no token needed

curl -s http://localhost:8080/actuator/health
# Spring Boot Actuator's own check, reports DB connectivity separately from the endpoint above
```

## 5. Step 3: Register the llama backend

> **STUB — not implemented: no admin API for backend registration.** `AdminController` exposes only
> `GET /backends` and `GET /metrics` — there is no `POST /backends` (or any other write) endpoint
> anywhere in the codebase. The **only** way to register a backend is a direct SQL insert into the
> `backends` table created in [§3](#3-step-1-postgresql):

```sql
INSERT INTO backends (name, url, model, capacity)
VALUES ('llama-01', 'http://192.168.1.101:8000', 'qwen2.5-coder', 1);
-- status defaults to 'ACTIVE'; capacity is the max concurrent RUNNING jobs on this backend.
```

The `name` you choose here (`llama-01`) is exactly the string the Worker must be configured with as
`BACKEND_ID` in [§6](#6-step-4-deploy-the-worker) — despite the field being named `backendId` on the
wire (`POST /jobs/claim`), it carries this **name**, not the numeric `id` column.

Verify registration:

```bash
curl -s http://gateway.internal/backends -H "Authorization: Bearer $ADMIN_TOKEN"
```
```json
[
  {
    "id": 1,
    "name": "llama-01",
    "model": "qwen2.5-coder",
    "capacity": 1,
    "status": "ACTIVE",
    "running": 0,
    "lastSeen": null
  }
]
```

`lastSeen`/`status` update once the Gateway's own backend-health scheduler
(`gateway.scheduler.backend-health-interval`, default 60s) successfully probes `GET
http://192.168.1.101:8000/health` — confirm your `llama-server` build actually answers that path (the
Gateway assumes it does; running/configuring `llama-server` itself is out of this repository's scope, see
[§2](#2-prerequisites)). Until the first successful probe, `status` stays whatever it was inserted as
(`ACTIVE` by the statement above) and `last_seen` stays `NULL`; a **failed** probe flips `ACTIVE →
SUSPECT` (auto-recovers back to `ACTIVE` on the next successful probe).

## 6. Step 4: Deploy the Worker

```bash
mvn -q -f worker/pom.xml verify
# artifact: worker/target/llm-worker.jar
```

### 6.1 Environment file

```bash
GATEWAY_URL=https://gateway.internal
GATEWAY_API_KEY=<same value as the Gateway's WORKER_TOKEN above>
WORKER_ID=worker-llama-01
BACKEND_ID=llama-01
LLAMA_URL=http://192.168.1.101:8000
LLAMA_MODEL=qwen2.5-coder
```

`BACKEND_ID` must be exactly the `name` registered in [§5](#5-step-3-register-the-llama-backend); a
mismatch means `POST /jobs/claim` will simply never find work for this backend (the Gateway never errors
on an unknown `backendId`, it just returns `204` — see [§8](#8-step-6-end-to-end-smoke-test) for
troubleshooting). See `worker/README.md` [§5](worker/README.md#5-configuration-reference) for every other
Worker property/env var and its default/validation rule (heartbeat cadence, diff/response size caps,
timeouts, etc.) — not repeated here to avoid the two docs drifting apart.

### 6.2 systemd unit

Reused verbatim from `worker/README.md` [§6.1](worker/README.md#61-systemd-linux) for consistency (no
unit file ships in this repository either — same caveat as [§4.2](#42-systemd-unit)):

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

### 6.3 Startup verification

Expected log lines (stdout only, `worker/src/main/resources/logback-spring.xml` has no file appender):

```
Starting worker-loop
Claimed job (jobId=none)      <-- normal: 204, nothing queued yet, polling
```

(or, once a review is queued, `Claimed job (jobId=<n>)` followed by `llama-server completion received
...` and `Result delivered (jobId=<n>, status=ACCEPTED)`). Actuator is loopback-only by design
(`server.address: 127.0.0.1`, WSR-12/FW-01 — see `worker/README.md`
[§8](worker/README.md#8-observability)), so verify **from the Worker's own host**:

```bash
curl -s http://127.0.0.1:8081/actuator/health
curl -s http://127.0.0.1:8081/actuator/prometheus | grep worker_
# worker_jobs_total, worker_jobs_completed_total, worker_jobs_failed_total,
# worker_llama_duration_seconds_*, worker_gateway_errors_total, worker_uptime_seconds
```

## 7. Step 5: GitLab integration

> **STUB — not implemented: no GitLab webhook receiver.** The Gateway has no webhook/event endpoint of
> any kind (its only inbound endpoints are `/reviews*`, `/jobs/*`, `/backends`, `/metrics`, `/health`,
> confirmed by listing every `@RestController` in the codebase). Integration is **entirely CI-initiated**:
> a `.gitlab-ci.yml` job explicitly calls `POST /reviews`. There is nothing to configure on GitLab's
> "Webhooks" settings page for this integration — only CI/CD variables (below) and the pipeline job
> itself.

### 7.1 The CI/CD variables

In the GitLab project (or group) → **Settings → CI/CD → Variables**, define (masked + protected):

| Variable | Value |
|---|---|
| `REVIEW_GATEWAY_URL` | `https://gateway.internal` |
| `REVIEW_GATEWAY_CI_TOKEN` | the Gateway's `CI_TOKEN` value from [§2](#2-prerequisites) |

(These are the exact variable names used in the root README's own verified `.gitlab-ci.yml` example,
reused as-is below.)

### 7.2 The pipeline job

Taken verbatim from the root README's [§7](README.md#7-gitlab-ci-integration) (already verified against
`CreateReviewRequest` and GitLab's predefined MR-pipeline variables), pointed at `https://gitlab.local`:

```yaml
ai-review:
  stage: review
  image: alpine:3.20
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  before_script:
    - apk add --no-cache git curl jq
  script:
    - git diff "$CI_MERGE_REQUEST_DIFF_BASE_SHA" "$CI_COMMIT_SHA" > diff.txt
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
        exit 0
      elif [ "$http_code" != "200" ] && [ "$http_code" != "201" ]; then
        echo "Review Gateway returned an unexpected status."
        exit 1
      fi
      REVIEW_ID=$(jq -r '.reviewId' response.json)
      echo "Review $REVIEW_ID queued (or already active for this head_sha)."
```

The job does not need to wait for the review to finish — `POST /reviews` returns as soon as the review is
queued (`201 Created`, or `200 OK` if an active review for the same `(projectId, mergeRequestId,
headSha)` already existed — dedup, root [README §8](README.md#8-review-lifecycle)).

### 7.3 What the Gateway needs from GitLab, and the resulting MR flow

The Gateway calls exactly one GitLab API endpoint, via `GitLabClientImpl.postDiscussion`:

```
POST {GITLAB_BASE_URL}/projects/{projectId}/merge_requests/{mergeRequestIid}/discussions
Header: PRIVATE-TOKEN: <GITLAB_TOKEN>
Body:   { "body": "<one parsed comment's text>" }
```

— once per parsed review comment (not one combined comment), each in its own request, each tracked by
the returned discussion `id` (stored in `review_comments.discussion_id`) for idempotent retry. This is
the **only** direction of GitLab traffic; the Gateway never reads anything else from the GitLab API
(no MR metadata fetch, no repository access).

End-to-end MR flow: CI job posts the diff (`POST /reviews`, `202`-equivalent `201`/`200` + `reviewId`) →
Gateway queues it (`QUEUED`) → a Worker claims and runs it (`RUNNING`) → the parsed comments are stored
and the review flips `COMPLETED` → the Gateway's own publish cycle (`gateway.scheduler.publish-retry-interval`,
60s) posts each comment as a GitLab discussion and, once all are posted, flips the review to `PUBLISHED` —
this is when comments actually appear on the Merge Request. None of this depends on the original CI job
still running.

## 8. Step 6: End-to-end smoke test

Ordered checklist, using the tokens/hosts from the sections above:

1. **Health.**
   ```bash
   curl -s http://gateway.internal/health                                     # {"status":"UP"}
   curl -s http://gateway.internal/actuator/health                            # DB connectivity
   curl -s http://127.0.0.1:8081/actuator/health   # (on the Worker's own host)
   ```
2. **Backend registered and reachable.**
   ```bash
   curl -s http://gateway.internal/backends -H "Authorization: Bearer $ADMIN_TOKEN"
   # status should be "ACTIVE" (or check back after one backend-health-interval tick, 60s default)
   ```
3. **Create a test review with a small diff.**
   ```bash
   curl -s -X POST http://gateway.internal/reviews \
     -H "Authorization: Bearer $CI_TOKEN" -H "Content-Type: application/json" \
     -d '{
           "projectId": 1, "mergeRequestId": 1,
           "headSha": "smoketest-head", "baseSha": "smoketest-base",
           "diff": "diff --git a/Foo.java b/Foo.java\n+System.out.println(1);\n",
           "promptVersion": "v1", "priority": 10
         }'
   # 201 Created -> {"reviewId": <N>, "status": "QUEUED"}
   ```
4. **Watch it progress.**
   ```bash
   watch -n 5 "curl -s http://gateway.internal/reviews/<N> -H 'Authorization: Bearer $CI_TOKEN'"
   # status: QUEUED -> RUNNING -> COMPLETED -> PUBLISHED
   ```
   `RUNNING` should appear within one Worker poll interval (`network.poll-interval-ms`, default 3s) of a
   Worker being up and pointed at the right `BACKEND_ID`; `COMPLETED` once `llama-server` answers;
   `PUBLISHED` within one `gateway.scheduler.publish-retry-interval` tick (default 60s) after that — this
   step needs a real GitLab project/MR to actually post to (`projectId`/`mergeRequestId` above are
   placeholders; use a real project/MR IID pair, or expect a `GitLabPublishException`-driven retry loop
   that never finishes if they don't exist).
5. **If it doesn't move:**
   - **Stuck in `QUEUED`.** No Worker is claiming: check the Worker's own logs for `Claimed job
     (jobId=none)` (it's polling but the queue is empty — confirms it's alive) vs. no claim log activity
     at all (Worker down, misconfigured `GATEWAY_URL`, or wrong `WORKER_TOKEN`/`GATEWAY_API_KEY`).
     Confirm the backend used for registration is `ACTIVE`, not at capacity, and that `BACKEND_ID` on the
     Worker matches the registered `name` exactly (see [§6](#6-step-4-deploy-the-worker)).
   - **Stuck in `RUNNING`.** Check the Worker's logs for `llama-server` errors (`Job abandoned
     (jobId=…)`), or wait for the Gateway's stale-heartbeat sweep (`gateway.scheduler.heartbeat-check-interval`,
     30s tick; `gateway.heartbeat.timeout`, 180s staleness) to requeue/fail it if the Worker died mid-job.
   - **Stuck in `COMPLETED`** (never reaches `PUBLISHED`). GitLab connectivity/`GITLAB_TOKEN` problem —
     check the Gateway's own logs for `Transient GitLab publish failure` (`GitLabPublisher`).
   - **Any state, for the full history:** query `review_events` directly in PostgreSQL —
     `SELECT * FROM review_events WHERE review_id = <N> ORDER BY created_at;` — every transition (and
     every heartbeat) is recorded there, including `worker_id`/`backend_id` attribution where applicable.

## 9. Operations quick reference

| Task | How |
|---|---|
| Cancel a review | `curl -X DELETE http://gateway.internal/reviews/<id> -H "Authorization: Bearer $ADMIN_TOKEN"` — only `NEW/QUEUED/RUNNING/COMPLETED` reviews are cancellable; a currently-running Worker learns to stop via its next heartbeat response (`shouldContinue:false`), never via a direct call to the Worker. |
| A backend goes `SUSPECT` | Automatic: the Gateway's health-check scheduler (`gateway.scheduler.backend-health-interval`, 60s) flips `ACTIVE → SUSPECT` on a failed `GET {url}/health` probe and excludes it from new claims; it auto-recovers to `ACTIVE` on the next successful probe. No admin action needed unless the underlying `llama-server` itself needs fixing. |
| Worker restart semantics | `RUNNING` jobs are **never** reset on a Gateway restart (no startup reconciliation touches them). If the *Worker* process restarts/crashes mid-job instead, the Gateway's stale-heartbeat sweep reclaims it once `heartbeat_at` is older than `gateway.heartbeat.timeout` (default 180s), checked every `gateway.scheduler.heartbeat-check-interval` (default 30s). |
| Retry limits | `gateway.retry.max-attempts` (default 3): a `RUNNING` job that times out (heartbeat staleness or the `gateway.job.max-duration` 45-minute backstop) is requeued if attempts remain, else marked `FAILED`. |
| Inspect audit trail | `SELECT * FROM review_events WHERE review_id = <id> ORDER BY created_at;` — no query endpoint exists for this table in the API surface. |

## 10. Config file appendix

Complete, copy-pasteable versions of everything above, in one place.

### 10.1 Gateway environment file (`/etc/review-gateway/review-gateway.env`)

```bash
DB_URL=jdbc:postgresql://db.internal:5432/review_gateway?sslmode=require
DB_USER=review_gateway
DB_PASSWORD=change-me-to-a-real-secret

CI_TOKEN=<32+ char random value, e.g. `openssl rand -hex 32`>
WORKER_TOKEN=<32+ char random value>
ADMIN_TOKEN=<32+ char random value>

GITLAB_BASE_URL=https://gitlab.local/api/v4
GITLAB_TOKEN=<GitLab project/group access token, api scope, Developer+ role>

BACKEND_ALLOWED_HOST_PATTERN=^192\.168\.1\.101$
```

### 10.2 Gateway systemd unit (`/etc/systemd/system/review-gateway.service`)

```ini
[Unit]
Description=Review Gateway
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=review-gateway
Group=review-gateway
EnvironmentFile=/etc/review-gateway/review-gateway.env
ExecStart=/usr/bin/java -jar /opt/review-gateway/review-gateway-1.0.0-SNAPSHOT.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

### 10.3 Worker environment file (`/etc/llm-worker/llm-worker.env`)

```bash
GATEWAY_URL=https://gateway.internal
GATEWAY_API_KEY=<same value as the Gateway's WORKER_TOKEN above>
WORKER_ID=worker-llama-01
BACKEND_ID=llama-01
LLAMA_URL=http://192.168.1.101:8000
LLAMA_MODEL=qwen2.5-coder
```

(See `worker/README.md` [§5.2](worker/README.md#52-everything-else-has-a-working-default) for every
optional override — poll/heartbeat/timeout intervals, diff/response size caps, etc.)

### 10.4 Worker systemd unit (`/etc/systemd/system/llm-worker.service`)

```ini
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

### 10.5 `.gitlab-ci.yml` snippet

```yaml
ai-review:
  stage: review
  image: alpine:3.20
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
  before_script:
    - apk add --no-cache git curl jq
  script:
    - git diff "$CI_MERGE_REQUEST_DIFF_BASE_SHA" "$CI_COMMIT_SHA" > diff.txt
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
        exit 0
      elif [ "$http_code" != "200" ] && [ "$http_code" != "201" ]; then
        echo "Review Gateway returned an unexpected status."
        exit 1
      fi
      REVIEW_ID=$(jq -r '.reviewId' response.json)
      echo "Review $REVIEW_ID queued (or already active for this head_sha)."
```

CI/CD variables to define in GitLab (project or group → **Settings → CI/CD → Variables**, masked +
protected): `REVIEW_GATEWAY_URL=https://gateway.internal`, `REVIEW_GATEWAY_CI_TOKEN=<the Gateway's
CI_TOKEN>`.

### 10.6 Backend registration SQL

```sql
INSERT INTO backends (name, url, model, capacity)
VALUES ('llama-01', 'http://192.168.1.101:8000', 'qwen2.5-coder', 1);
```
