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
   - [8a. Upgrading to V2 (diff chunking)](#8a-upgrading-to-v2-diff-chunking)
   - [8b. Upgrading to V4 (Worker Observability & Claim Latency)](#8b-upgrading-to-v4-worker-observability--claim-latency)
   - [8c. Upgrading to V5 (Structured Review Output)](#8c-upgrading-to-v5-structured-review-output)
9. [Operations quick reference](#9-operations-quick-reference)
10. [Config file appendix](#10-config-file-appendix)
11. [Docker deployment (verified, both images)](#11-docker-deployment-verified-both-images)

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
| Docker | **Not required to build or test**, for either component | Both test suites use in-process fakes (Zonky embedded PostgreSQL for the Gateway, real-socket `okhttp3:mockwebserver` for the Worker) — no Testcontainers, no external services needed to build or test. A `Dockerfile` at the repo root and `worker/Dockerfile` provide an *optional* containerized deployment path for both components — see [§11](#11-docker-deployment-verified-both-images). |

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

The Gateway requires **four** secrets, checked by `GatewayProperties.validateOnStartup()`. Three of them
(`CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN`) are self-issued bearer tokens and must independently be **at
least 32 characters** (startup fails otherwise, matching the code's own `MIN_SECRET_LENGTH = 32`):

```bash
export CI_TOKEN=$(openssl rand -hex 32)
export WORKER_TOKEN=$(openssl rand -hex 32)
export ADMIN_TOKEN=$(openssl rand -hex 32)
```

The fourth, `GITLAB_TOKEN`, is **not generated locally** — it is a GitLab project or group access token
you create in GitLab itself (`https://gitlab.local` → project/group → **Settings → Access Tokens**),
scoped to the `api` scope (needed to call the Discussions API the Gateway uses —
`GitLabClientImpl.postDiscussion`) with at least the **Developer** role on the projects under review (the
minimum GitLab role that can create MR discussions). Unlike the three tokens above, `GITLAB_TOKEN` is only
checked for presence, not length: a real GitLab project/group access token is a fixed 26 characters
(`glpat-` + 20), below the 32-character floor, so the Gateway does not — and cannot — apply the same check
to it. The root README's own recommendation (from the threat model) is to make this token **project- or
group-scoped and expiring**, not a full personal access token — this is an operational choice made when
the token is issued in GitLab, not something the application enforces.

**How the Worker gets the `WORKER_TOKEN` value.** There is no token-exchange mechanism — the Worker's own
`GATEWAY_API_KEY` environment variable must simply be set to the **exact same value** as the Gateway's
`WORKER_TOKEN` (verified: `worker.gateway.api-key` is sent as `Authorization: Bearer` on every Worker→Gateway
call, and the Gateway compares it against its single configured `WORKER_TOKEN`). Copy the value out-of-band
(a secrets manager, or manually) when provisioning each Worker host.

**Prompt Manager (V3): a fifth secret, `GITLAB_PROMPT_TOKEN`, only if you opt in.** Repo-sourced
corporate/project system prompts are **off by default** (`gateway.prompt.enabled` defaults to
`${PROMPT_MANAGER_ENABLED:false}` — see `gateway.prompt.*` in [§10](#10-config-file-appendix)); a
stock or freshly-upgraded Gateway boots with today's Worker-JAR-only behavior and never requires this
token. This is a deliberate safe-by-default choice recorded in
`docs/security/feature-prompt-manager-sast-report.md` (F-PM-02): the alternative (defaulting to
`true`) would mean every existing deployment fails to restart on upgrade until this whole section is
carried out, and the realistic operator response to an un-bootable Gateway — hard-coding the
kill-switch off in `application.yml` — would durably disable the control by accident instead of by a
deliberate decision (`GatewayProperties.validateOnStartup()`/`validatePromptOnStartup()`).

To opt in, set `PROMPT_MANAGER_ENABLED=true` and provision:

- `GITLAB_PROMPT_TOKEN` — a **separate, read-only** GitLab project/group access token
  (`read_api`/`read_repository` scope — never `api`/write), used exclusively for the three Prompt
  Manager reads (`GitLabClientImpl.resolveCommitSha`/`fetchRawFile`/`resolveDefaultBranch`) via the
  dedicated `gitLabPromptRestClient` bean — the write-scoped `GITLAB_TOKEN` above is never sent on a
  read call and vice versa (PMR-15). For the corporate prompt repo specifically, issue a **project
  access token scoped to that one project**; for project reads, prefer a **group access token with
  `read_repository` only** over an `api`-scoped personal token. Like `GITLAB_TOKEN`, this is checked
  only for presence (not the 32-character floor), for the same reason — it is GitLab's own fixed token
  format, not an operator-chosen secret.
- `PROMPT_CORPORATE_PROJECT` — the numeric id or `group/project` path of the corporate prompt repo
  (mandatory once enabled; `gateway.prompt.corporate.project` has no default and startup fails without
  it). `PROMPT_CORPORATE_REF` (default `main`), `PROMPT_CORPORATE_BASE_PROMPT_PATH` (default
  `prompts/base-system-prompt.md`) and `PROMPT_CORPORATE_REVIEW_RULES_PATH` (default
  `prompts/review-rules.md`) are optional overrides of the corporate source paths.
- `PROMPT_ON_ERROR` (default `FAIL`) — set to `SKIP_OPTIONAL` to let an unusable/oversized *optional*
  `PROJECT_*` section degrade the Review rather than block `POST /reviews`; corporate sections are
  always `FAIL`, not configurable.

With the kill-switch at its default (`false`), `GatewayProperties.validateOnStartup()` skips all
`gateway.prompt.*` validation, including `GITLAB_PROMPT_TOKEN`'s presence check, and behavior is
byte-identical to pre-V3.

**Structured Review Output (V5): no new secret, but a hard deployment-order prerequisite.** `v3` support
adds no credential of its own — it reuses the existing `CI_TOKEN`/`WORKER_TOKEN` and three optional,
purely additive env vars (`ALLOWED_PROMPT_VERSIONS`, `STRUCTURED_OUTPUT_ENABLED`,
`STRUCTURED_OUTPUT_DEFAULT_MODE`, all with working defaults — see [§10](#10-config-file-appendix)). What
it does add is an ordering requirement: **every Worker in the fleet must already be running a build that
ships `worker/src/main/resources/prompts/v3.yml` before `v3` is ever added to
`gateway.review.allowed-prompt-versions`** (Workers first, Gateway second — the same precedent as
Prompt Manager's own template rollout). An old Worker that claims a `v3` job it doesn't recognize
abandons it immediately (`POST /jobs/{id}/fail`, `PROMPT_INVALID`) and the Review burns its retry budget
for nothing — allowlisting `v3` before the fleet is ready turns every `v3` submission into a guaranteed,
avoidable failure. See [§8c](#8c-upgrading-to-v5-structured-review-output) for the full upgrade
procedure and the rollout ladder.

**Capability verification: does a given `llama-server` build actually honor a JSON Schema constraint?**
Before setting a backend's `structured_output_mode` to anything but `OFF`
([§8c](#8c-upgrading-to-v5-structured-review-output)), verify it directly against **that** backend — this
repository pins no `llama.cpp`/`llama-server` version, and structured-output support (and, worse, silent
*fail-open* on an unparseable grammar — a documented `llama.cpp` behavior, not a bug in this codebase) is
a per-build capability, not something safe to assume from a version number. The recipe needs **two**
calls, not one — a schema the model conforms to easily proves nothing about a fail-open backend, which
passes that call by luck:

```bash
# 1. Positive check: a trivial schema most models satisfy even unprompted. A conforming response alone
#    does NOT prove the constraint is enforced -- see the negative check below.
curl -sS "http://192.168.1.101:8000/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "qwen2.5-coder",
        "messages": [{"role": "user", "content": "Reply with a JSON object."}],
        "response_format": {
          "type": "json_schema",
          "json_schema": { "name": "toy", "schema": {
            "type": "object", "additionalProperties": false,
            "required": ["ok"], "properties": { "ok": { "type": "boolean" } }
          }}
        }
      }' | jq '.choices[0].message.content'

# 2. Negative control (the one most guides skip): a schema the model would NOT satisfy unprompted --
#    an enum of nonsense values it has no reason to pick on its own. If the response still "conforms"
#    (picks one of the listed nonsense values), the constraint is genuinely enforced. If it answers with
#    something else entirely (ignores the schema) or the call errors out, the backend is failing open or
#    rejecting the schema -- do NOT enable structured_output_mode for it.
curl -sS "http://192.168.1.101:8000/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{
        "model": "qwen2.5-coder",
        "messages": [{"role": "user", "content": "Reply with a JSON object."}],
        "response_format": {
          "type": "json_schema",
          "json_schema": { "name": "negative_control", "schema": {
            "type": "object", "additionalProperties": false,
            "required": ["verdict"], "properties": {
              "verdict": { "type": "string", "enum": ["xqzplorf", "zbrknarv", "qwyzfelt"] }
            }
          }}
        }
      }' | jq '.choices[0].message.content'
```

Also check the `llama-server` process's own log output for `failed to parse grammar` (or similar) around
the time of either call — this is the only client-visible symptom of the fail-open bug on some builds,
and it can appear even when the HTTP response still looks superficially fine. If either check fails, or
the log line appears, leave that backend's `structured_output_mode` at `OFF` (or `NULL`, which uses
`gateway.structured.default-mode`, itself `OFF` by default) — the feature still works for that backend,
just without a decoder-level guarantee (the coverage list in the prompt and the Gateway's own strict
response validation are unaffected either way).

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

**Worker Observability & Claim Latency (V4):** `V4__worker_failure_reporting_and_backend_health.sql` adds
two additive, nullable columns — `backends.probe_failed_since` and `review_jobs.not_before` — no new
table, no grant change (the role above already has `UPDATE` on both tables). See
[§8b](#8b-upgrading-to-v4-worker-observability--claim-latency) before deploying it against a live system.

**Grants** (per the SAST report's recommendation, root [README §4.3](README.md#43-deployment-must-dos-from-docssecurityfeature-03-sast-reportmd)
item 4 — restrict `review_events` to append-only so the audit trail cannot be silently rewritten):

```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON
    reviews, review_inputs, backends, review_jobs, review_results, review_comments
    TO review_gateway;
GRANT SELECT, INSERT ON review_events TO review_gateway;   -- no UPDATE/DELETE
-- Prompt Manager (V3, PMR-07): same append-only contract as review_events -- the resolved sections
-- are an immutable audit/provenance record, never rewritten after insert.
GRANT SELECT, INSERT ON review_prompt_sections TO review_gateway;   -- no UPDATE/DELETE
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

# --- Prompt Manager (V3, optional -- omit entirely to stay on today's Worker-JAR-only prompts) ---
# PROMPT_MANAGER_ENABLED=true
# GITLAB_PROMPT_TOKEN=<separate, read-only GitLab token -- see §2>
# PROMPT_CORPORATE_PROJECT=group/ai-review-prompts
```

`GatewayProperties.validateOnStartup()` will refuse to start if any of the four secrets above is missing,
if `CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN` is under 32 characters, or if `GITLAB_BASE_URL` doesn't start
with `https://`. `GITLAB_TOKEN` itself is not length-checked — see [§2, Token generation](#2-prerequisites).
The three commented-out `PROMPT_*` lines are optional: Prompt Manager defaults to disabled
(`PROMPT_MANAGER_ENABLED` defaults to `false`), so the stock env file above boots without them.

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

### 4.4 Docker alternative

Instead of [§4](#4-step-2-deploy-the-gateway)'s jar+systemd path, build and run the root `Dockerfile`
(multi-stage `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-jammy`, non-root user, built-in
`HEALTHCHECK` against `GET /health`):

```bash
docker build -t review-gateway:latest .

docker run -d --name review-gateway \
  --env-file /etc/review-gateway/review-gateway.env \
  -p 8080:8080 \
  review-gateway:latest
```

The env file is exactly the one from [§4.1](#41-environment-file)/[§10.1](#101-gateway-environment-file-etcreview-gatewayreview-gatewayenv)
— every property the image reads is a plain environment variable, nothing Docker-specific. The image sets
no placeholder values for the six required secrets (`DB_USER`, `DB_PASSWORD`, `CI_TOKEN`, `WORKER_TOKEN`,
`ADMIN_TOKEN`, `GITLAB_TOKEN`), so a container started without them fails fast in its logs exactly like
the bare-jar deployment. See [§11](#11-docker-deployment-verified-both-images) for a fully worked,
verified example (Postgres + Gateway + Worker, all containerized, exercised end-to-end).

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

### 6.4 Docker alternative

Instead of [§6](#6-step-4-deploy-the-worker)'s jar+systemd/launchd path, build and run `worker/Dockerfile`
(same base-image pattern as the Gateway's, `HEALTHCHECK` against `GET /actuator/health`):

```bash
docker build -t llm-worker:latest worker/

docker run -d --name llm-worker \
  --env-file /etc/llm-worker/llm-worker.env \
  llm-worker:latest
```

The env file is the one from [§6.1](#61-environment-file)/[§10.3](#103-worker-environment-file-etcllm-workerllm-workerenv).
**Do not** publish the Worker's port with `-p`: `server.address: 127.0.0.1` is hardcoded in the Worker's
own `application.yml` (actuator must never be reachable off the Worker's own host,
[worker/README.md §8](worker/README.md#8-observability)) — check it with `docker exec` instead of a
mapped host port:

```bash
docker exec llm-worker curl -s http://127.0.0.1:8081/actuator/health
docker inspect --format '{{.State.Health.Status}}' llm-worker
```

**Gotcha carried over from containerizing the Gateway too:** `GATEWAY_URL` must be `https://` unless it
resolves to an actual loopback address (worker/README.md's WSR-09 check) — in a normal deployment where
the Worker reaches the Gateway through the reverse-proxy `https://gateway.internal` endpoint, this needs
no special handling. It only becomes relevant for a same-host dev/smoke-test setup, worked through fully
in [§11](#11-docker-deployment-verified-both-images).

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
`CreateReviewRequest` and GitLab's predefined MR-pipeline variables), pointed at `https://gitlab.local`.
A ready-to-copy version (plus an optional job that blocks the pipeline until the review is actually
`PUBLISHED`) is at [`examples/.gitlab-ci.yml`](examples/.gitlab-ci.yml) — copy it into the *target*
project being reviewed, not into this repository:

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

## 8a. Upgrading to V2 (diff chunking)

`V2__diff_chunking.sql` moves queue ownership from `reviews` to `review_jobs` (1:N per review, one row
per diff chunk) and adds the `review_chunks` table. Read this section fully before deploying it against
a live system.

### Forward-only — no rollback path

**Do not roll back the application JAR after this migration has run.** The pre-V2 Gateway code claims
jobs by querying `reviews.status` directly; it has no notion of `review_jobs.status` at all. If you
deploy the old JAR against a V2-migrated database, any `RUNNING` chunk job would be silently invisible
to the old claim query's assumptions, and — worse — a review whose `reviews.status` the old code still
reads as `QUEUED`/`RUNNING` could be re-claimed and **double-executed** by the old code path racing
against jobs the new code already dispatched. If you need to revert, revert forward (fix and re-deploy
V2-aware code), not backward to a pre-V2 JAR.

### Recommended downtime budget: **< 150 seconds**

This is derived from the two liveness timers already in the system, not an arbitrary number:

- The Worker's `HeartbeatScheduler` aborts a job after **3 consecutive heartbeat failures** at its
  default 60s interval → ≈180s before a Worker gives up on a Gateway that's unreachable mid-deploy.
- The Gateway's own `gateway.heartbeat.timeout` (default 180s) is the outer bound before a stuck
  `RUNNING` job is swept and requeued/failed.

Staying under ~150s of Gateway unavailability during the deploy means neither of those failure paths
triggers spuriously just because the Gateway was momentarily down for the migration, not because
anything is actually wrong.

### Recommended procedure

1. **If feasible, drain to zero `RUNNING` reviews before deploying.** Stop accepting new CI-triggered
   reviews (or just let the queue drain naturally — `NEW`/`QUEUED` reviews are unaffected either way,
   they aren't mid-flight) and wait for in-flight `RUNNING` reviews to finish. This is the safest path:
   the backfill has nothing in-flight to reconcile.
2. **If you can't drain (long-running reviews, time pressure):** the backfill is still safe to run
   against a live database with `RUNNING` reviews in flight — `review_jobs.id` is always preserved
   (the migration only ever `UPDATE`s existing job rows, never deletes and re-inserts them), so an
   in-flight job's identity survives the migration. The Worker executing it keeps heartbeating the same
   `jobId` throughout; it will simply start seeing `review_jobs.status`-aware behavior (parent-derived
   `reviews.status`, chunk-level fields) on its *next* claim, not the in-flight one.
3. Run the migration (automatic at Gateway startup, same as any other Flyway migration — see
   [§4](#4-step-2-deploy-the-gateway)). Expect it to run in well under a second for realistic data
   volumes at this project's scale (20-30 MRs/day).
4. Deploy the V2-aware Gateway JAR. Workers need **no changes** to keep working with single-chunk
   Reviews (byte-identical behavior, §8 of `README.md`); upgrade Workers whenever convenient to pick up
   `chunkContext` support (`v2.yml` prompt template) for chunked Reviews. An un-upgraded Worker is still
   safe against a chunked Review: it would fail `promptVersion` resolution for `v2` (unknown template)
   the same way it would for any other unrecognized version, abandoning that one job cleanly rather than
   mishandling it — see architecture D6 ("no synthetic error result is ever submitted").

### What NOT to do

- Do not attempt to skip straight from a pre-V1... this is the first migration after V1, so this is the
  only upgrade path — there is no "V1.5" to worry about.
- Do not manually edit `review_jobs`/`review_chunks` rows mid-migration; let the Flyway-managed SQL run
  uninterrupted (it is ordinary transactional DDL/DML, no `CREATE INDEX CONCURRENTLY`, so it runs inside
  one Flyway transaction and will cleanly roll back if interrupted before commit).

## 8b. Upgrading to V4 (Worker Observability & Claim Latency)

`V4__worker_failure_reporting_and_backend_health.sql` adds two additive, nullable columns —
`backends.probe_failed_since` and `review_jobs.not_before` — with no backfill and no constraint change.
Unlike V2, this migration is **rollback-tolerant**: an older JAR simply ignores both columns and degrades
to today's (pre-V4) behavior.

### Rollback tolerance (unlike V2)

- An older Gateway JAR ignoring `not_before` claims a requeued job **immediately** — exactly today's
  behavior, safe.
- An older Gateway JAR ignoring `probe_failed_since` demotes a backend on a **single** failed probe —
  exactly today's behavior, safe.
- Neither can double-execute a job the way a V2 rollback could (§8a) — `review_jobs.id`/`status` semantics
  are completely unchanged by V4; it only adds two columns nothing pre-V4 code reads or writes.

### Deployment notes

- `ALTER TABLE review_jobs ADD COLUMN not_before ...` still takes an `ACCESS EXCLUSIVE` lock on the queue
  table for the (metadata-only, sub-second) duration of the `ALTER`. The migration sets an explicit
  `lock_timeout` so a stuck `ALTER` fails fast instead of silently blocking every claim behind it. In
  practice this runs at Gateway startup while the Gateway is the only writer and is momentarily down; the
  Worker fleet is **not** down (by design — `RUNNING` jobs survive Gateway restarts) and simply sees `204`
  responses until the new Gateway is up.
- **New required arithmetic at startup:** `gateway.retry.requeue-delay × (gateway.retry.max-attempts − 1)`
  must be `≥ gateway.backend.failure-grace`, or the Gateway refuses to start (see root
  [README §4.2](README.md#42-everything-else-has-a-working-default)). The shipped defaults
  (`requeue-delay=90s`, `max-attempts=3`, `failure-grace=180s`) already satisfy this; only a concern if you
  override any of the three.
- **New env var:** `WORKER_IDLE_SUMMARY_INTERVAL_SEC` (Worker module, default `300`) — purely additive,
  optional, no migration/coordination needed with the Gateway.
- No grant changes (§3) and no new table — the existing `review_gateway` role already has `UPDATE` on both
  `backends` and `review_jobs`.

### What NOT to do

- Do not set `gateway.retry.requeue-delay: 0` or `gateway.backend.failure-grace: 0` **individually** —
  they are a paired escape hatch (both-or-neither) back to pre-V4 behavior; setting only one fails startup.
- Do not manually edit `review_jobs.not_before`/`backends.probe_failed_since` outside the application —
  both are written exclusively by `RetryManager`/`BackendHealthChecker` under their own lock discipline.

## 8c. Upgrading to V5 (Structured Review Output)

`V5__structured_review_output.sql` adds two additive, nullable columns — `backends.structured_output_mode`
and `review_results.finish_reason` — plus one `CHECK` constraint on the new `backends` column. Like V4
(and unlike V2), this migration is **rollback-tolerant**: an older Gateway JAR simply ignores both new
columns and degrades to today's (pre-V5) behavior — it never reads or writes either one, so the `CHECK`
can never be violated by old code, and `v3` traffic is impossible before this migration anyway (the
allowlist gate is application-level, not a schema constraint).

### Rollback tolerance

- An older Gateway JAR ignoring `backends.structured_output_mode` never attaches a decoder constraint —
  exactly today's (pre-V5) behavior, safe.
- An older Gateway JAR ignoring `review_results.finish_reason` never classifies a `TRUNCATED` structured
  failure from it — moot, since an old JAR also has no `StructuredResponseParser` to classify anything
  for in the first place.
- Rolling back **does not** un-allowlist `v3` by itself — `gateway.review.allowed-prompt-versions` is a
  Gateway config value, not a DB row. If you roll back the JAR, also remove `v3` from that allowlist (or
  set `ALLOWED_PROMPT_VERSIONS` back to `v1,v2`), or `POST /reviews` with `promptVersion: v3` will 500 on
  the old JAR (it has no code path for that value at all) instead of failing cleanly.

### Deployment notes

- `ALTER TABLE review_results ADD COLUMN finish_reason ...` takes an `ACCESS EXCLUSIVE` lock on
  `review_results` — the largest table in the schema — for the (metadata-only, sub-second) duration of the
  `ALTER`. The migration sets an explicit `lock_timeout = '5s'` so a stuck `ALTER` fails fast instead of
  silently blocking every result submission behind it. As with V4, this runs at Gateway startup while it
  is the only writer and is momentarily down; the Worker fleet is not affected (`RUNNING` jobs survive
  Gateway restarts) and simply sees `204`/retries until the new Gateway is up.
- **`ck_backends_structured_output_mode` and a future fifth mode.** The `CHECK` constraint enumerates the
  four `StructuredOutputMode` values (`OFF`, `RESPONSE_FORMAT_JSON_SCHEMA`, `RESPONSE_FORMAT_SCHEMA`,
  `TOP_LEVEL_JSON_SCHEMA`). If a future release adds a fifth wire mode, the constraint **must be relaxed
  first**, in its own migration, before any row can be set to the new value:
  ```sql
  ALTER TABLE backends DROP CONSTRAINT ck_backends_structured_output_mode;
  ALTER TABLE backends ADD CONSTRAINT ck_backends_structured_output_mode
      CHECK (structured_output_mode IS NULL OR structured_output_mode IN
          ('OFF', 'RESPONSE_FORMAT_JSON_SCHEMA', 'RESPONSE_FORMAT_SCHEMA', 'TOP_LEVEL_JSON_SCHEMA', '<NEW_MODE>'));
  ```
- **Enabling a backend, once its capability is verified** ([§2](#2-prerequisites)'s `curl` recipe):
  ```sql
  UPDATE backends SET structured_output_mode = 'RESPONSE_FORMAT_JSON_SCHEMA' WHERE name = 'mac-mini-01';
  -- Rollback for one backend:
  UPDATE backends SET structured_output_mode = 'OFF' WHERE name = 'mac-mini-01';
  ```
  A single `UPDATE`, no restart — this is deliberately a data change, not a config/redeploy, so a canary
  rollout (see the rollout ladder below) never needs a Gateway restart between stages.
- **Cross-module coupling, both silent on mismatch (no startup check spans both processes):**
  - `gateway.diff.answer-reserve` (Gateway — used for both v1/v2 and structured/v3 since
    `chore/answer-reserve-consolidation`, see the table below) must stay `≥` `v3.yml`'s `maxTokens`
    minus some margin, ideally equal to it — a mismatch doesn't fail startup on either side; it just
    risks a truncated completion under a large chunk if the Gateway's budget assumption is smaller than
    what the Worker actually requests.
  - `gateway.structured.max-schema-bytes` (Gateway, default `65536`) must stay **below**
    `worker.limits.max-constraint-bytes` (Worker, default `69632`) by at least the largest wire-wrapper
    overhead — the shipped defaults already satisfy this (69632 = 65536 + 4096 headroom). If you change
    one, recompute the other; a mismatch here doesn't fail startup on either process, it produces a
    fleet-wide `CONSTRAINT_INVALID` abandonment loop the first time a schema near the Gateway's own limit
    is claimed.
- **Workers-first is a hard prerequisite, not a suggestion** ([§2](#2-prerequisites)) — deploy every Worker
  with `v3.yml` before adding `v3` to `gateway.review.allowed-prompt-versions`, never the other way round.
- No new grant is needed (§3) — the existing `review_gateway` role already has `UPDATE` on `backends` and
  `INSERT` on `review_results`.

### Бюджет LLM-токенов: сводная таблица

Gateway и Worker — два независимых процесса (`chore/config-consolidation`: не обязательно даже на одной
машине — см. [§1](#1-architecture-overview)), поэтому у них физически не может быть одного общего файла
конфигурации. Все параметры ниже вместе формируют **один** бюджет контекстного окна модели, но живут в
разных файлах: `src/main/resources/application.yml` (Gateway, блок `gateway.diff.*`/`gateway.structured.*`/
`gateway.prompt.limits.*` — секция "§B Бюджет LLM-токенов" в файле), `worker/src/main/resources/
application.yml` (блок `llama.*`/`worker.limits.*` — секция "§B" там же) и `worker/src/main/resources/
prompts/*.yml` (`maxTokens` на шаблон). Эта таблица — единственное место, где вся картина собрана вместе;
при изменении любого значения ниже сверяйтесь с ней целиком, а не только с локальным комментарием в
одном файле.

**Нет автоматической проверки, которая охватывала бы оба процесса сразу** — Gateway не знает во время
своего старта, какой `maxTokens` реально настроен на удалённых Worker'ах (и наоборот). Единственный способ
свериться сегодня — сравнить два независимых лога:
- Gateway при каждом успешном старте пишет INFO-строку `"Structured Review Output budget check passed:
  ..."` (`GatewayProperties.validateStructuredOnStartup`) с разбивкой всей формулы бюджета.
- Worker при каждом старте пишет по одной INFO-строке `"Prompt template '<version>': effective
  maxTokens=..."` на каждый найденный шаблон (`PromptTemplateService.logResolvedTemplateBudgetsOnStartup`).

| Параметр | Процесс / файл | Дефолт | За что отвечает | С чем связан |
|---|---|---|---|---|
| `gateway.diff.context-window` | Gateway | 16384 | Общий размер контекстного окна модели — база для всех расчётов ниже | Должен совпадать с реальным контекстным окном модели на `llama-server` |
| `gateway.diff.prompt-reserve` | Gateway | 2000 | Резерв под системный промпт для v1/v2 (и как база, когда Prompt Manager выключен) | Вычитается из `context-window` |
| `gateway.diff.answer-reserve` | Gateway | 4000 | Резерв под ответ модели — ОДНО значение для v1/v2 И для structured (v3); раньше был отдельный `gateway.structured.answer-reserve`, объединены (`chore/answer-reserve-consolidation`) после повторных ошибок рассинхрона между ними | Должен расти вместе с `v3.yml`'s `maxTokens` (Worker) |
| `gateway.diff.max-diff-tokens` | Gateway | 10000 | Потолок на diff в одном чанке | Независимый potолок поверх расчёта по окну (см. CSR-02 в комментарии рядом с параметром) |
| `gateway.diff.chars-per-token` | Gateway | 4 | Эвристика перевода символов diff'а в токены (нет настоящего токенизатора) | Используется во всех формулах ниже, включая `coverageReserveTokens` |
| `gateway.diff.max-paths-per-section` | Gateway | 64 | Верхняя граница путей, извлекаемых из ОДНОЙ секции diff (memory-safety, SRO-66a) | Должен быть ≥ `gateway.structured.max-files-per-chunk` (проверяется на старте) |
| `gateway.prompt.limits.max-system-prompt-tokens` | Gateway | 6000 | Потолок размера промпта, собираемого Prompt Manager'ом из Git | Учитывается в формуле бюджета, только если `gateway.prompt.enabled=true` |
| `gateway.prompt.limits.min-diff-budget-tokens` | Gateway | 1000 | Минимальный порог остатка бюджета под сам diff — если меньше, Gateway отказывается стартовать | Правая часть неравенства формулы бюджета (см. §8c выше) |
| `gateway.structured.max-files-per-chunk` | Gateway | 40 | Верхняя граница файлов в одном структурированном чанке | Входит в формулу `coverageReserveTokens` |
| `gateway.structured.max-path-chars` | Gateway | 256 | Максимальная длина одного пути-ключа схемы | Входит в формулу `coverageReserveTokens`; должен быть ≤ 300 |
| `gateway.structured.max-schema-bytes` | Gateway | 65536 | Backstop-потолок размера самой JSON-схемы | Должен быть **меньше** `worker.limits.max-constraint-bytes` с запасом на wire-обёртку |
| `gateway.structured.max-findings-per-file` | Gateway | 20 | Потолок числа находок на файл | Влияет на реальный размер ответа v3 (не входит в формулу бюджета напрямую) |
| `gateway.structured.max-comment-chars` | Gateway | 1200 | `maxLength` поля `comment` одной находки | Влияет на реальный размер ответа v3 |
| `gateway.structured.max-suggestion-chars` | Gateway | 2000 | `maxLength` поля `suggestion` одной находки | Влияет на реальный размер ответа v3 |
| `llama.max-tokens` | Worker | 4096 | Глобальный дефолт `max_tokens`, если конкретный шаблон промпта его не переопределяет | Используется `v1.yml`/`v2.yml` (своего значения не задают) |
| `v3.yml` → `maxTokens` | Worker (файл шаблона) | 8192 | Реальный потолок токенов, которые модель может сгенерировать для v3-ответа | Должен быть ≥ `gateway.diff.answer-reserve` — иначе Gateway резервирует бюджет под ответ длиннее, чем Worker реально позволит модели сгенерировать |
| `worker.limits.max-diff-bytes` | Worker | 262144 | Байтовый потолок на diff + chunkContext + systemMessages суммарно | Независим от токен-формулы Gateway'я, отдельная защита на стороне Worker'а (WSR-03) |
| `worker.limits.max-response-bytes` | Worker | 200000 | Байтовый потолок на ответ LLM, который Worker готов принять | Тот же порядок величины, что и `gateway.publish.max-raw-response-length` (200000, §E в `application.yml`) — оба независимо ограничивают одно и то же на разных концах |
| `worker.limits.max-system-messages` | Worker | 8 | Потолок числа system-сообщений от Prompt Manager'а | Независим от `gateway.prompt.limits.max-sections` (WSR-03 sibling) |
| `worker.limits.max-constraint-bytes` | Worker | 69632 | Байтовый потолок на присланную Gateway'ем JSON-схему/constraint | Обязан **превышать** `gateway.structured.max-schema-bytes` на размер самой большой wire-обёртки (~70 байт) |

**Формула итогового бюджета** (то же самое, что печатает Gateway в лог при старте):

```
context-window − prompt-reserve − answer-reserve − coverageReserveTokens(max-files-per-chunk, max-path-chars, chars-per-token)
  − (prompt.enabled ? max-system-prompt-tokens : 0)  ≥  min-diff-budget-tokens
```

Если меняете любой параметр слева — сверяйтесь с этой таблицей на предмет других параметров, которые с ним связаны, ДО перезапуска, а не после отказа стартовать.

### Monitored residual: LLM-compute amplification on validation failure

`gateway.structured.max-validation-attempts` (a per-Review override for the retry-attempt budget on
structured-validation failures specifically) is **not implemented** — a structured job whose response
fails validation reuses the same `gateway.retry.max-attempts` (default 3) as any infrastructure failure,
and each retry re-runs LLM inference at full compute cost. Because the trigger (any of the
`STRUCTURED_OUTPUT_UNSUPPORTED` conditions in [§6.1](README.md#61-post-reviews--create-a-review) is
already closed at `POST /reviews`; what's left is genuine model non-conformance) is not attacker-forceable
in the same way the pre-fix edge gaps were, and because a permanently-failing chunk cascades `CANCELLED`
to its successful sibling chunks (`ChunkCoordinator`), this is accepted as a monitored residual
(`SOR-INH-1`) rather than built out further. **The one available lever today is
`gateway.structured.enabled=false`** (the kill switch, [§4.5](README.md#45-structured-review-output-v5-optional))
— it disables the feature wholesale (falls back to `CommentParser` parsing for all `v3` traffic) rather
than tuning the attempt budget for structured failures alone. Watch `structuredValidationFailures` (`GET
/metrics`) for a sustained rate as the early signal; if a specific project/MR is triggering it
repeatedly, ask that project to resubmit with `promptVersion: v2` while investigating.

### What NOT to do

- Do not add `v3` to `gateway.review.allowed-prompt-versions` before every Worker in the fleet ships
  `v3.yml` — see [§2](#2-prerequisites).
- Do not set a backend's `structured_output_mode` to anything but `OFF`/`NULL` without first running the
  capability-verification recipe ([§2](#2-prerequisites)) against **that specific** backend — a
  fail-open `llama-server` build looks identical to a working one on a single happy-path request.
- Do not relax `ck_backends_structured_output_mode` casually — only when actually adding a fifth mode,
  in its own migration.

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

# Prompt Manager (V3, optional -- defaults to disabled; uncomment all three to opt in, see §2)
# PROMPT_MANAGER_ENABLED=true
# GITLAB_PROMPT_TOKEN=<separate, read-only GitLab project/group access token, read_api/read_repository scope>
# PROMPT_CORPORATE_PROJECT=<numeric project id or "group/project" path -- never a URL>

# Structured Review Output (V5, optional -- all three have working defaults, uncomment only to change
# them; adding "v3" here is a hard no-op until every Worker in the fleet ships v3.yml, see §2/§8c)
# ALLOWED_PROMPT_VERSIONS=v1,v2,v3
# STRUCTURED_OUTPUT_ENABLED=true
# STRUCTURED_OUTPUT_DEFAULT_MODE=OFF
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

Same content as [`examples/.gitlab-ci.yml`](examples/.gitlab-ci.yml) (which also has the optional
publish-wait job) — copy into the *target* project being reviewed, not into this repository.

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

## 11. Docker deployment (verified, both images)

Both components ship a `Dockerfile` — root (Gateway) and `worker/Dockerfile` (Worker) — each a
multi-stage build (`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-jammy`, non-root user, a
built-in `curl`-based `HEALTHCHECK`). Neither image is published anywhere; build locally from the repo:

```bash
docker build -t review-gateway:latest .
docker build -t llm-worker:latest worker/
```

Every environment variable documented in this runbook ([§4.1](#41-environment-file)/[§10.1](#101-gateway-environment-file-etcreview-gatewayreview-gatewayenv)
for the Gateway, [§6.1](#61-environment-file)/[§10.3](#103-worker-environment-file-etcllm-workerllm-workerenv)
for the Worker) is read by the image the same way as by the bare jar — plain `${VAR}` Spring property
resolution, nothing Docker-specific. Neither image bakes in a placeholder for a required secret
(`DB_USER`/`DB_PASSWORD`/`CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN`/`GITLAB_TOKEN` for the Gateway;
`GATEWAY_URL`/`GATEWAY_API_KEY`/`WORKER_ID`/`BACKEND_ID`/`LLAMA_MODEL` for the Worker) — a container
started without one fails fast in its own logs, same as [§4](#4-step-2-deploy-the-gateway)/[§6](#6-step-4-deploy-the-worker).

### 11.1 Production topology: reverse proxy, no special flags needed

In a real deployment the Gateway sits behind the same TLS-terminating reverse proxy this runbook already
assumes ([§2, network matrix](#2-prerequisites)), so a containerized Worker reaching it via
`GATEWAY_URL=https://gateway.internal` needs no Docker-specific handling at all — it behaves exactly like
[§6.4](#64-docker-alternative) describes. `docker run -p 8080:8080` for the Gateway and normal container
networking (a Docker network, a service mesh, or just routable hosts) is all that's required; the Worker
container never publishes any port (its Actuator is loopback-only by design, checked via `docker exec`,
see [§6.4](#64-docker-alternative)).

### 11.2 Local single-host smoke test (this is the exact recipe used to verify both images)

This is the full topology actually exercised end-to-end against both images on this project: one Docker
network for Postgres↔Gateway, and `--network host` for the Worker so its one hard requirement —
`gateway.url` must resolve to a **real** loopback address to use the `WORKER_ALLOW_INSECURE_GATEWAY`
dev escape hatch (worker/README.md's WSR-09 check) — is satisfiable without a reverse proxy. A
container-to-container hostname on a bridge network (e.g. `http://review-gateway:8080`) does **not**
count as loopback, so this networking choice is not just convenience; it is what makes the Worker start
at all without TLS in front of the Gateway.

```bash
# 1. Network + Postgres
docker network create airg-test
docker run -d --name airg-postgres --network airg-test \
  -e POSTGRES_DB=review_gateway -e POSTGRES_USER=review_gateway -e POSTGRES_PASSWORD=change-me \
  postgres:14-alpine
until docker exec airg-postgres pg_isready -U review_gateway >/dev/null 2>&1; do sleep 1; done

# 2. Gateway (Flyway migration runs automatically on first connection, per §3/§4)
docker run -d --name airg-gateway --network airg-test \
  -e DB_URL="jdbc:postgresql://airg-postgres:5432/review_gateway" \
  -e DB_USER="review_gateway" \
  -e DB_PASSWORD="change-me" \
  -e CI_TOKEN="$(openssl rand -hex 32)" \
  -e WORKER_TOKEN="$(openssl rand -hex 32)" \
  -e ADMIN_TOKEN="$(openssl rand -hex 32)" \
  -e GITLAB_BASE_URL="https://gitlab.example.com/api/v4" \
  -e GITLAB_TOKEN="$(openssl rand -hex 32)" \
  -p 18080:8080 \
  review-gateway:latest
# Save the actual generated WORKER_TOKEN value somewhere if you typed it above instead of
# piping openssl directly -- the Worker below must be given the exact same value.

# Wait for the container's own HEALTHCHECK to go green (Flyway + JPA startup takes ~30s):
until [ "$(docker inspect --format '{{.State.Health.Status}}' airg-gateway)" = "healthy" ]; do sleep 3; done
curl -s http://localhost:18080/health   # {"status":"UP"}

# 3. Register a backend (§5 -- no REST endpoint, direct SQL only)
docker exec airg-postgres psql -U review_gateway -d review_gateway -c \
  "INSERT INTO backends (name, url, model, capacity) VALUES ('llama-01', 'http://127.0.0.1:8000', 'qwen2.5-coder', 1);"

# 4. Worker -- --network host so GATEWAY_URL can be a real loopback address
docker run -d --name airg-worker --network host \
  -e GATEWAY_URL="http://127.0.0.1:18080" \
  -e GATEWAY_API_KEY="<the WORKER_TOKEN value from step 2>" \
  -e WORKER_ID="worker-test-1" \
  -e BACKEND_ID="llama-01" \
  -e LLAMA_MODEL="qwen2.5-coder" \
  -e WORKER_ALLOW_INSECURE_GATEWAY="true" \
  llm-worker:latest

until [ "$(docker inspect --format '{{.State.Health.Status}}' airg-worker)" = "healthy" ]; do sleep 3; done
docker exec airg-worker curl -s http://127.0.0.1:8081/actuator/prometheus | grep '^worker_'
```

What this actually proves (verified on this machine, not just healthchecks passing): a review created via
`POST /reviews` against the containerized Gateway was picked up by the containerized Worker
(`Claimed job (jobId=1)` in `docker logs airg-worker`), the Worker attempted the `llama-server` call
(expected failure — no `llama-server` is running in this recipe), the Gateway's own review state flipped
to `RUNNING`/`attempts:1`, and `worker_jobs_failed_total` incremented — genuine claim→attempt→failure
round-tripping between two real containers, not two containers that merely started.

Tear down with:

```bash
docker rm -f airg-gateway airg-worker airg-postgres
docker network rm airg-test
```

### 11.3 Docker Compose (the same topology, one command)

`docker-compose.yml` at the repo root automates exactly the §11.2 recipe above — Postgres, the Gateway,
a one-shot backend-registration job, and the Worker — instead of four separate `docker run` calls. It
uses the same `network_mode: "service:gateway"` trick for the Worker (so `GATEWAY_URL=http://127.0.0.1:8080`
is genuinely loopback from the Worker's point of view) and an `ON CONFLICT (name) DO NOTHING` insert for
the backend row, so it's safe to re-run.

```bash
export DB_PASSWORD=change-me CI_TOKEN=$(openssl rand -hex 32) WORKER_TOKEN=$(openssl rand -hex 32) \
       ADMIN_TOKEN=$(openssl rand -hex 32) GITLAB_TOKEN=$(openssl rand -hex 32) LLAMA_MODEL=qwen2.5-coder
# (or put the same variables in a `.env` file next to docker-compose.yml instead of exporting them)
docker compose up --build
```

No defaults are set for the six required secrets (`DB_PASSWORD`, `CI_TOKEN`, `WORKER_TOKEN`,
`ADMIN_TOKEN`, `GITLAB_TOKEN`, `LLAMA_MODEL`) — Compose refuses to start with a clear
`required variable ... is missing a value` error if one is unset, rather than silently running with a
blank/fake value. `LLAMA_URL` defaults to `http://127.0.0.1:8000` (a dev no-op, since no `llama-server`
runs inside this stack) — override it to point at a real one. Verified end-to-end on this machine: the
same claim→attempt→failure round-trip described in [§11.2](#112-local-single-host-smoke-test-this-is-the-exact-recipe-used-to-verify-both-images)
was reproduced through `docker compose up` alone. Tear down with `docker compose down -v`.
