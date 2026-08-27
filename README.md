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
- **Prompt Manager (V3, optional, off by default)** lets the system prompt come from Git instead of only
  the Worker's own jar: mandatory corporate rules from one org-wide GitLab repo, plus optional
  per-project architecture/code-style rules from the reviewed project itself (or an operator-configured
  override repo) — see [§4.4](#44-prompt-manager-v3-optional) and
  [§6.1b](#61b-prompt-manager-and-system-prompt-assembly).
- **Structured Review Output (V5, `promptVersion: v3`)** gives the model a Gateway-computed per-file
  coverage list and, on a capable backend, a decoder-level JSON Schema constraint — every file listed
  must appear in the response or the job is retried/failed rather than silently missing coverage. `v3`
  is opt-in per Review and not allowlisted by default — see [§4.5](#45-structured-review-output-v5-optional)
  and [§6.1c](#61c-structured-review-output-and-response-validation).

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

Every setting is an environment variable, read once at startup — there is no admin UI and no config
reload. This section covers what's required to get running and what each optional feature turns on.
**For the complete list of every setting (both the Gateway's and the Worker's), how they're wired
through Docker, and which ones must be kept in sync with each other, see `DEPLOYMENT.md`'s "Конфигурация:
полный справочник параметров"** — that's the file to check before changing any value, since several
settings look independent but aren't.

### 4.1 Required secrets (no default — startup fails without them)

The Gateway refuses to start if any of these is missing, blank, or (for the three tokens) shorter than
32 characters. Error messages never repeat the secret's value back, only which one is missing.

| Environment variable | Purpose |
|---|---|
| `CI_TOKEN` | Bearer token GitLab CI uses to create/check reviews. |
| `WORKER_TOKEN` | Bearer token a Worker uses to talk to the Gateway. **Must be the exact same value** you give every Worker's own `GATEWAY_API_KEY`. |
| `ADMIN_TOKEN` | Bearer token for admin actions (cancel a review, view metrics). |
| `GITLAB_TOKEN` | Token the Gateway itself uses to post comments back to GitLab. Never shared with CI or Workers. |
| `DB_USER` / `DB_PASSWORD` | PostgreSQL credentials. |

Generate the three bearer tokens as random values, e.g. `openssl rand -hex 32`. For `GITLAB_TOKEN`, use a
GitLab project or group access token scoped only to the repositories under review, not a personal token
with broad access.

**One more secret, `GITLAB_PROMPT_TOKEN`, only if you turn on Prompt Manager** — see 4.4 below. Leave
Prompt Manager off and this token is never checked or required.

### 4.2 Everything else (has a working default)

Every other setting ships with a sensible default and doesn't need to be touched to get a working
system. The two groups worth knowing about up front:

- **Token budget** (how big a diff/prompt/answer can be): `gateway.diff.*` on the Gateway side, plus
  matching settings on the Worker and in its prompt templates. These have to agree with each other
  across two separate processes — see the full breakdown and worked example in `DEPLOYMENT.md`.
- **Timeouts and retry** (heartbeats, stale-job cleanup, backend health checks): sensible out of the
  box; only worth changing if your hardware is much slower/faster than typical, or your network has
  unusual latency. Full list in `DEPLOYMENT.md`.

Two settings deserve a mention here because getting them wrong fails silently rather than loudly:

- **`BACKEND_ALLOWED_HOST_PATTERN`** — restricts which network the Gateway is allowed to send health
  checks to. Ships permissive (`.*`, any host); tighten it to your actual backend network in production
  (see the deployment must-dos right below).
- **`ALLOWED_PROMPT_VERSIONS`** — which review formats (`v1`/`v2`/`v3`) the Gateway accepts. `v3`
  (Structured Review Output, §4.5) is deliberately left out of the default until every Worker is ready
  for it.

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

**Off by default.** Out of the box, the system prompt sent to the model is the one bundled inside the
Worker's own build. Turning this feature on instead pulls that prompt from a Git repository — a shared
"corporate" prompt everyone gets, plus optional per-project add-ons (an architecture note, coding-rules
file) read from the project actually being reviewed. Nothing changes for anyone until you set
`PROMPT_MANAGER_ENABLED=true` and point it at a repository — see `DEPLOYMENT.md` for every setting this
unlocks (which files it reads, how to override the source repo per project, timeouts, size limits) and
[`docs/prompt-manager-architecture.md`](docs/prompt-manager-architecture.md) for the full design.

The one thing worth knowing before turning it on: this needs its own GitLab token
(`GITLAB_PROMPT_TOKEN`), separate from the one the Gateway uses to post comments, and it should be
**read-only** — it only ever reads prompt files, never writes anything.

### 4.5 Structured Review Output (V5, optional)

**A response *format*, chosen per review via `promptVersion: "v3"`** — not a global switch. Reviews
using `v1`/`v2` are completely unaffected. `v3` isn't accepted until an operator explicitly adds it to
`ALLOWED_PROMPT_VERSIONS`, and only after every Worker already has the `v3` prompt template deployed
(Workers first, Gateway second). Full design in
[`docs/structured-review-output-architecture.md`](docs/structured-review-output-architecture.md).

**What it changes:** a `v1`/`v2` review just asks the model to reply with a JSON list of comments and
trusts whatever comes back — one malformed character anywhere in that list, and the whole response
becomes unreadable and gets dumped into the MR as one ugly comment instead of being split up properly. A
`v3` review instead tells the model exactly which files it must cover and checks the response against
that list on arrival: a response that skips a file, isn't valid, or breaks a size limit is treated as a
failure and retried, rather than silently accepted or dumped as garbage.

There's a second, optional layer on top: for a specific, opted-in backend, the Gateway can also make the
underlying model server itself incapable of producing anything but the expected shape (a "decoder
constraint"), instead of just asking nicely. This is off by default for every backend
(`backends.structured_output_mode = OFF`) and should stay off until you've run the compatibility checks
in `DEPLOYMENT.md` — some model-server builds silently ignore this setting rather than rejecting it,
which is worse than not using it at all if nobody notices.

**Two settings you might reasonably want to change; everything else has a fine default and is covered
in `DEPLOYMENT.md`:**

- `STRUCTURED_OUTPUT_ENABLED` — the kill switch. Turn it off and `v3` reviews behave exactly like `v2`
  (same parsing, same tolerance for a slightly malformed response) with no code change or redeploy. This
  is the right lever to pull first if a `v3`-related problem shows up in production.
- `gateway.structured.on-invalid-response` — what happens when a `v3` response keeps failing validation
  after every retry. The default, `RETRY_THEN_FAIL`, ends that review as failed. The alternative,
  `RETRY_THEN_FALLBACK`, publishes the model's answer anyway (clearly labeled "unvalidated") rather than
  giving up — a deliberate trade-off, not a free quality improvement, since it reintroduces some of the
  malformed-response risk `v3` exists to remove. Treat flipping it back to the default as the fix for an
  incident traced to this fallback, not `STRUCTURED_OUTPUT_ENABLED`.

**Rollout ladder** (from the architecture doc §11 — every stage past the initial deploy is a data/CI
change, never a redeploy):

| Stage | Change | What it proves |
|---|---|---|
| 0 — baseline | Deploy this branch. Every backend `OFF`, `v3` not allowlisted. | `legacyParseFallback`/`averageFileCoverageRatio` on real v2 traffic — the "before" baseline, at zero cost. |
| 1 — schema validation only | Workers updated (**prerequisite for every stage past this one**); `v3` allowlisted; one pilot project's CI sends `promptVersion: v3`. Backends stay `OFF`. | How often the model conforms **without** decoder help — meaningful only because the coverage list is always rendered, even unconstrained. |
| 2 — canary constraint | `UPDATE backends SET structured_output_mode='RESPONSE_FORMAT_JSON_SCHEMA' WHERE name='...'` on **one** backend. | Whether that `llama-server` build actually honors the constraint — see the capability-verification recipe in `DEPLOYMENT.md`. Rollback: the same `UPDATE` back to `'OFF'`. |
| 3 — fleet | All backends on the mode that worked in stage 2. | Steady-state failure rate. |
| 4 — default | CI templates switch to `promptVersion: v3` (with `git -c core.quotePath=false diff` — see [§6.1c](#61c-structured-review-output-and-response-validation)). | — |

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
raw diff text, not structured), `promptVersion` (non-blank `String`, required — checked against
`gateway.review.allowed-prompt-versions` **before any other work**, [§4.2](#42-everything-else-has-a-working-default);
`v1`/`v2` by default, `v3` only once an operator adds it — see
[§4.5](#45-structured-review-output-v5-optional)), `priority` (`Integer`, optional — defaults to `10`;
higher values are claimed first).

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
- **`422 Unprocessable Entity` — `STRUCTURED_OUTPUT_UNSUPPORTED`** (Structured Review Output, V5). Checked
  before any Review/chunk/job is persisted, in five cases, all sharing this one code (no new error code per
  case) and never echoing the offending path/value itself — only which property or character class it
  violated:
  1. `promptVersion` is not in `gateway.review.allowed-prompt-versions` at all (checked first, before even
     the diff-size guard).
  2. `promptVersion` is `v3` but the diff has no `diff --git` headers to derive a per-file coverage list
     from (submit a real `git diff`, or use `v2`).
  3. At least one changed file's path does not survive sanitization, or **two different paths collide onto
     the same sanitized value** — either way a file would be silently uncovered.
  4. A path contains a character outside the conservative alphabet Structured Review Output requires
     (`{ } " \ ` [ ] |` and whitespace are rejected, along with a leading `/` or a `..` segment), or exceeds
     `gateway.structured.max-path-chars` (default 256). If your Git config uses `core.quotePath` (the
     default), a non-ASCII filename is quoted/escaped by `git diff` and will trip this — resubmit with
     `git -c core.quotePath=false diff` (see [§6.1c](#61c-structured-review-output-and-response-validation)).
  5. The diff's coverage list can never fit `gateway.structured.max-files-per-chunk`/`gateway.diff.max-chunks`
     no matter how it is packed (this shares `422 DIFF_TOO_LARGE`'s shape, not this one — listed here since
     it is a structured-output-only condition).

  Every case in this list, except the last, uses:
  ```json
  { "error": "STRUCTURED_OUTPUT_UNSUPPORTED", "message": "promptVersion 'v3' requires file paths free of the characters { } \" \\ ` [ ] | and whitespace, with no '..' segment, no leading '/', and at most 256 characters — use promptVersion 'v2' or rename the affected file(s)." }
  ```
  In every case the stated fallback is the same: use `promptVersion: v2` (unconstrained, unaffected by
  any of the above), or fix the offending file name/path.
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

### 6.1c Structured Review Output and response validation

**Only relevant for `promptVersion: "v3"` ([§4.5](#45-structured-review-output-v5-optional)).** Two
independent mechanisms, both driven by the same per-chunk coverage list (the exact set of changed file
paths for that chunk):

1. **Coverage list in the prompt (always).** Every `v3` job's rendered prompt includes the full list of
   files it must cover, regardless of chunk count or decoder-constraint mode — this is what makes the
   feature work under the shipped `default-mode: OFF`, where no decoder constraint is applied at all.
2. **Decoder constraint (only on a capable, opted-in backend).** When the claiming backend's
   `structured_output_mode` is not `OFF`, the Gateway additionally builds a JSON Schema from the same
   coverage list and attaches it to the outbound `llama-server` request (`responseFormat`/`jsonSchema` on
   the claim payload, see [§6.4](#64-post-jobsclaim--claim-the-next-queued-job-worker)) — this is a
   per-backend capability the Gateway discovers by configuration, never by probing; **no llama.cpp version
   is pinned by this repository**, and a build that silently fails to honor the constraint is a known,
   documented risk (a fail-open backend still passes validation whenever the model happens to comply by
   luck — see `DEPLOYMENT.md`'s capability-verification recipe for how to actually confirm a given backend
   enforces it).

**Every response, constrained or not, is strictly validated on receipt** — `StructuredResponseParser`
never trusts the decoder constraint, the backend's mode, or `finish_reason` to shortcut this. A response
must be valid JSON, contain exactly the expected file keys (no more, no fewer), and every finding must
satisfy its own field/length bounds; anything else is one of four failure kinds:

| Kind | Meaning |
|---|---|
| `NOT_JSON` | The response isn't parseable JSON at all (includes a duplicate JSON key, which the parser's strict-mode parser rejects outright rather than silently keeping only the last occurrence). |
| `SCHEMA_MISMATCH` | Valid JSON, but the wrong shape — a missing/extra top-level key, a finding missing a required field, an out-of-enum `severity`, or a `findings`/`comment`/`suggestion` bound exceeded. |
| `COVERAGE_SHORTFALL` | The `files` object's key set doesn't exactly match the expected coverage list — a file is missing, or the response names a file that isn't in this chunk. |
| `TRUNCATED` | `finish_reason: "length"`, or the raw response was itself truncated by the Gateway's own size cap before validation ran. |

A validation failure is a **retryable job failure** (`RetryManager.requeueOrFail`, exactly the same
attempts/`not_before` machinery as an infrastructure failure), not an immediate `FAILED` — see
[§8](#8-review-lifecycle). `last_error` carries a diagnosis (e.g. `structured-output: COVERAGE_SHORTFALL;
missing=[src/B.java]; unexpected=[none]`) capped and sanitized, never the model's raw text.
`gateway.structured.on-invalid-response` (default `RETRY_THEN_FAIL`) decides what happens once attempts
are exhausted — see [§4.5](#45-structured-review-output-v5-optional) for the `RETRY_THEN_FALLBACK`
escape hatch and its documented risk re-acceptance.

**A published `v3` comment's body is a fixed, Gateway-owned template** — header (severity + file + line)
plus the model's prose, plus an optional fenced `diff`-language context excerpt and an optional
suggested-fix block, never the fence language `suggestion` (which GitLab treats specially) and never
markdown/HTML the model's own text could use to escape the intended structure. The *shape* of a v3
comment body is decided entirely by the Gateway; the model only ever supplies the text that goes inside
it.

**CI must produce a `git diff` the coverage list can trust.** If your Git configuration uses
`core.quotePath` (the default), a non-ASCII file name is quoted/escaped in `diff --git` headers, which
trips the character-alphabet check in [§6.1](#61-post-reviews--create-a-review)'s
`STRUCTURED_OUTPUT_UNSUPPORTED` list — submit `git -c core.quotePath=false diff` instead (see
[§7](#7-gitlab-ci-integration)). Falling back to `promptVersion: v2` always works regardless.

**`finish_reason` (and the raw response it came from) describes only the first attempt.**
`review_results` is write-once per `(review_id, chunk_index)` (unchanged since before this feature,
`SRO-37`) — a retry's raw response and `finish_reason` are never stored, only the first attempt's are.
A `TRUNCATED` classification is always derived from the **in-flight** submission being validated, never
from a previously-stored value that could belong to a different attempt. The model's chunk-level
`summary` field is validated (bounded, must be a string) but **not currently persisted anywhere** —
`review_results.summary` stays `NULL` for every Review, `v3` included; this is a known, accepted gap
(not a bug), tracked for a future change.

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
    "payload": { "diff": "diff --git a/Foo.java b/Foo.java\n...", "promptVersion": "v1", "chunkContext": null, "systemMessages": null, "responseFormat": null, "jsonSchema": null }
  }
  ```
  As of diff chunking (V2), `jobId` identifies one **chunk** of the Review (`review_jobs` is now 1:N per
  Review), and `payload.diff` is that chunk's slice of the original diff, not necessarily the whole
  thing — see [§6.1a](#61a-diff-chunking). `payload.chunkContext` (additive) is the rendered cross-chunk
  header text (`null` for the common single-chunk case); the Worker substitutes it into the resolved
  prompt template's `{{CHUNK_CONTEXT}}` placeholder exactly like `diff`/`{{DIFF}}`, with no other
  chunk-awareness required on its side (it stays a fully stateless, chunk-ignorant HTTP client).
  **Structured Review Output (V5): `payload.chunkContext` is never `null` for a `v3` job**, regardless of
  chunk count — it always carries the per-file coverage-obligation block the model must satisfy (see
  [§6.1c](#61c-structured-review-output-and-response-validation)); the "`null` for single-chunk" rule
  above applies only to `v1`/`v2`.
  `payload.systemMessages` (V3, Prompt Manager) is `null` for a legacy Review or when
  `gateway.prompt.enabled=false` (use the Worker's own bundled template, exactly pre-V3 behavior) — a
  non-null value is one or more already-fully-assembled system-prompt strings to wrap verbatim into
  `ChatMessage(role=system, ...)`, **never** run through the `{{DIFF}}`/`{{CHUNK_CONTEXT}}` substitution
  logic (that stays scoped to the `user`-role template only). See
  [§6.1b](#61b-prompt-manager-and-system-prompt-assembly) and [§9](#9-worker-protocol).
  `payload.responseFormat`/`payload.jsonSchema` (V5, Structured Review Output) are the Gateway-computed
  decoder constraint — **at most one of the two is ever non-null**, and both are `null` unless this is a
  `v3` job claimed against a backend whose `structured_output_mode` is not `OFF`
  ([§4.5](#45-structured-review-output-v5-optional)). The Worker attaches whichever is set **verbatim**
  to its outbound `llama-server` call and never inspects, edits, or re-derives either — see
  [§9](#9-worker-protocol) and `worker/README.md` §2.
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
        "model": "llama-3.1-8b-instruct",
        "finishReason": "stop"
      }'
```

Request fields (`SubmitResultRequest`): `workerId` (non-blank, required), `rawResponse` (non-blank,
required — the model's raw text output; for `v1`/`v2` the Gateway tries to parse it as a JSON array of
`{file, line, severity, comment}` objects and falls back to treating the whole response as a single
comment if it isn't; for `v3` it is parsed strictly, see
[§6.1c](#61c-structured-review-output-and-response-validation)), `promptTokens`/`completionTokens`
(`Integer`, optional), `durationMs` (`Long`, optional), `model` (`String`, optional), `finishReason`
(`String`, optional, `≤32` chars — the backend's own completion-stop reason, e.g. `stop`/`length`;
whitelist-parsed against a closed vocabulary before storage, an unrecognized value is stored as
`unknown` rather than rejected. Used by Structured Review Output to classify `finish_reason: "length"`
as a `TRUNCATED` validation failure before even attempting to parse the response — see
[§6.1c](#61c-structured-review-output-and-response-validation); ignored entirely for `v1`/`v2`).

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
`LLM_RESPONSE_TOO_LARGE`/`PROMPT_INVALID`/`WORKER_ERROR`/`CONSTRAINT_INVALID` (the last one added by
Structured Review Output, V5 — the Worker's own defensive re-check of a Gateway-supplied decoder
constraint failed); the Gateway whitelist-parses this and treats any other value as `UNKNOWN` — it
**never** rejects an unrecognized code with `400`, so an independently-versioned Worker fleet degrades
safely), `detail` (optional, `≤500` chars — a fixed, Worker-side-constant description **never** derived
from an exception message; sanitized and truncated to
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
  "promptManagerEnabled": false,
  "promptDisabledCount": 0,
  "promptSectionMissingCount": 0,
  "ownershipMismatches": { "heartbeat": 0, "result": 0, "fail": 0 },
  "workerFailureReportsIgnored": 0,
  "legacyParseFallback": 0,
  "structuredValidationFailures": { "NOT_JSON": 0, "SCHEMA_MISMATCH": 0, "COVERAGE_SHORTFALL": 0, "TRUNCATED": 0 },
  "structuredConstraintSent": { "OFF": 0 },
  "structuredFallbackUsed": 0
}
```
`promptManagerEnabled`/`promptDisabledCount`/`promptSectionMissingCount` (Prompt Manager, V3) mirror
`gateway.prompt.enabled` and the `PROMPT_DISABLED`/`PROMPT_SECTION_MISSING` `review_events` rows,
derived from PostgreSQL like every field above them.

**Structured Review Output (V5), all four process-local, in-memory counters** (reset on a Gateway
restart, same caveat as `ownershipMismatches` below): `legacyParseFallback` increments on **every**
Review (`v1`/`v2` included) whenever `CommentParser`'s strict JSON-array parse fails and it falls back
to the whole-response-as-one-comment path — this is the cheapest available "format-compliance" baseline,
measured on existing traffic at zero cost, and is the "before" number the rollout ladder in
[§4.5](#45-structured-review-output-v5-optional) compares `v3`'s `structuredValidationFailures` against.
`structuredValidationFailures` is keyed by the four failure kinds from
[§6.1c](#61c-structured-review-output-and-response-validation). `structuredConstraintSent` is keyed by
wire mode (`OFF`/`RESPONSE_FORMAT_JSON_SCHEMA`/`RESPONSE_FORMAT_SCHEMA`/`TOP_LEVEL_JSON_SCHEMA`, plus the
pseudo-key `KILL_SWITCH_OFF` when `gateway.structured.enabled=false`) — "how many claims reached the
constraint decision", distinguishing "the model is good without help" from "we never turned the
constraint on" at a glance. `structuredFallbackUsed` counts every time
`gateway.structured.on-invalid-response=RETRY_THEN_FALLBACK` actually published a fallback comment. Every
one of these four is keyed only on a closed, Gateway-defined vocabulary — never a file path, project id,
backend URL, or any model-supplied string.

`ownershipMismatches` (broken down by endpoint) and `workerFailureReportsIgnored` (Worker Observability &
Claim Latency, WOR-03) are process-local, in-memory counters — they reset on a Gateway restart, unlike
every other field on this endpoint, which is derived from PostgreSQL. This is deliberate: writing a
`review_events` row for every rejected/no-op `POST /jobs/{id}/fail` report would turn the endpoint into an
authenticated, unbounded `INSERT` primitive for any worker-token holder — a worse problem than the
repudiation gap these counters close. A `workerId`-guessing campaign against `/jobs/**` is necessarily
noisy; these counters (plus the existing `WARN` logs) are what makes that noise visible without that risk.

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

**The Gateway never calls out to GitLab to compute or fetch a diff.** `GitLabClientImpl` has exactly
four methods — `postDiscussion` (publish a comment), and, only when Prompt Manager is enabled,
`resolveCommitSha`/`fetchRawFile`/`resolveDefaultBranch` (read system-prompt source files, [§6.1b](#61b-prompt-manager-and-system-prompt-assembly)).
There is no `GET .../merge_requests/{iid}/diffs` or `/changes` call anywhere in the Gateway, and no
inbound GitLab webhook receiver either. The `diff` field in `POST /reviews` is the **entire** contract:
whoever calls the endpoint (normally this CI job, via `git diff`) must compute the unified diff itself
and push it in the request body — the Gateway is a purely passive receiver of diff text, never an
initiator of a GitLab diff fetch.

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

**Using `promptVersion: "v3"` (Structured Review Output, [§4.5](#45-structured-review-output-v5-optional)):**
only change the `git diff` invocation and the `promptVersion` field above —
`git -c core.quotePath=false diff "$CI_MERGE_REQUEST_DIFF_BASE_SHA" "$CI_COMMIT_SHA" > diff.txt`. Without
`core.quotePath=false`, Git quotes/escapes any non-ASCII file name in `diff --git` headers, which the
structured-output coverage-list validation rejects at `POST /reviews` (`422
STRUCTURED_OUTPUT_UNSUPPORTED`, see [§6.1](#61-post-reviews--create-a-review)); `v1`/`v2` are unaffected
either way since they never parse file paths out of the diff at all. `v3` is not in the default
`gateway.review.allowed-prompt-versions` allowlist — an unallowlisted value also gets `422
STRUCTURED_OUTPUT_UNSUPPORTED`, at the very first check `POST /reviews` performs.

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

### 7.1 Manually reproducing a CI submission (local / ad-hoc testing)

To submit a Review by hand for a real GitLab merge request, without running the CI job above, replicate
the same three inputs the CI job derives automatically: the numeric `projectId`, the MR's `diff_refs`
(`baseSha`/`headSha`), and the diff text itself.

1. **Resolve the numeric `projectId`** from the project's path (GitLab accepts a URL-encoded path in
   place of the numeric id for this one lookup only — `POST /reviews` itself always requires the
   numeric id):
   ```bash
   curl -sS --header "PRIVATE-TOKEN: $GITLAB_PAT" \
     "https://<gitlab-host>/api/v4/projects/<group>%2F<subgroup>%2F<project>" | jq '.id'
   ```
2. **Resolve `baseSha`/`headSha`** from the MR's own `diff_refs` — not a local `git merge-base`, which
   can disagree with GitLab's view if the target branch has moved. `mr_iid` is the number shown in the
   MR's URL (`.../-/merge_requests/<iid>`), not GitLab's internal global MR id:
   ```bash
   curl -sS --header "PRIVATE-TOKEN: $GITLAB_PAT" \
     "https://<gitlab-host>/api/v4/projects/<projectId>/merge_requests/<mr_iid>" | jq '.diff_refs'
   # → { "base_sha": "...", "head_sha": "...", "start_sha": "..." }
   ```
3. **Produce the diff locally**, exactly as the CI job does (`git diff <base> <head>`), from a clone that
   actually has both commits. If `head_sha` belongs to a since-deleted source branch, fetch GitLab's
   per-MR ref instead of the branch:
   ```bash
   git fetch origin "refs/merge-requests/<mr_iid>/head:refs/mr/<mr_iid>/head"
   git diff --no-color <base_sha> refs/mr/<mr_iid>/head > diff.patch
   ```
   `DiffChunker` parses standard `git diff` output (preferring `diff --git a/... b/...` headers, falling
   back to plain `--- `/`+++ `); `--no-color` is required, ANSI escapes are not diff syntax.
4. **Submit it**, same shape as [§7](#7-gitlab-ci-integration)'s CI job:
   ```bash
   jq -n --argjson projectId <projectId> --argjson mergeRequestId <mr_iid> \
     --arg headSha "<head_sha>" --arg baseSha "<base_sha>" --arg promptVersion "v1" \
     --rawfile diff diff.patch \
     '{projectId:$projectId, mergeRequestId:$mergeRequestId, headSha:$headSha,
       baseSha:$baseSha, diff:$diff, promptVersion:$promptVersion, priority:10}' \
     | curl -sS -X POST "$REVIEW_GATEWAY_URL/reviews" \
         -H "Authorization: Bearer $REVIEW_GATEWAY_CI_TOKEN" \
         -H "Content-Type: application/json" --data @-
   ```

Notes: the dedup key ([§8](#8-review-lifecycle)) means resubmitting the same `(projectId,
mergeRequestId, headSha)` while a prior Review is still active just returns the existing `reviewId`
(`200`, not `201`) — pick a different `headSha` to force a fresh Review for repeated manual testing.
`promptVersion` must be in `gateway.review.allowed-prompt-versions` — an unallowlisted value now fails
**immediately** at `POST /reviews` time (`422 STRUCTURED_OUTPUT_UNSUPPORTED`,
[§6.1](#61-post-reviews--create-a-review)), not on the Worker side once a job is claimed (Structured
Review Output's SOR-08 allowlist, [§4.5](#45-structured-review-output-v5-optional)) — it must *also*
name a template that actually exists in the Worker's bundle
(`worker/src/main/resources/.../<promptVersion>.yml`), which the Gateway has no way to verify at request
time; an allowlisted `promptVersion` whose template is missing from a given Worker still fails only once
a job reaches that Worker.

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
     `chunkContext` (V2, diff chunking — `null` unless this job is one chunk of a larger, split diff, or
     unless this is a `v3` job — see below) + `systemMessages` (V3, Prompt Manager — `null` unless the
     Gateway has one enabled and resolved) + `responseFormat`/`jsonSchema` (V5, Structured Review Output —
     at most one non-null, both `null` unless this is a `v3` job claimed against a
     `structured_output_mode`-opted-in backend).
     Substitute `chunkContext` into the resolved template's `{{CHUNK_CONTEXT}}` placeholder exactly like
     `diff`/`{{DIFF}}` if the template has one and it's non-null. If `systemMessages` is non-null, use
     each string **verbatim** as its own `system`-role chat message (or concatenate them if your backend
     only accepts one `system` message) instead of your own bundled system template — do **not** run
     these strings through any placeholder-substitution logic; they are already fully assembled. If it's
     `null`, fall back to your own bundled system template exactly as before V3. If `responseFormat`/
     `jsonSchema` is non-null, attach it **verbatim** to your outbound `llama-server` chat-completion call
     under the corresponding wire field — never inspect, edit, or re-derive it, and never run it through
     any template-substitution logic either. You never need to know whether `jobId` represents a whole
     Review or one chunk of it — treat every claimed job identically.
   - `204` → nothing to claim right now (empty queue, your backend isn't `ACTIVE`/is at capacity, or a
     transient lock contention). Wait (e.g. a few seconds) and poll again.
2. **Run inference** against your local `llama-server` using the claimed
   `diff`/`promptVersion`/`chunkContext`/`systemMessages`, with `responseFormat`/`jsonSchema` attached
   verbatim to the request when present.
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
- **Structured Review Output (V5, optional) — `promptVersion` allowlisted at the edge, response never
  trusted.** `v3` is rejected at `POST /reviews` unless an operator has explicitly added it to
  `gateway.review.allowed-prompt-versions`, and adding it is only safe once every Worker in the fleet
  already ships `v3.yml` (Workers-first deployment order) — see
  [§4.5](#45-structured-review-output-v5-optional). File paths that become the JSON Schema's keys are
  restricted to a conservative character alphabet (checked at `POST /reviews`, independent of and in
  addition to the general path sanitization every diff already goes through) before ever reaching a
  third-party grammar compiler. The decoder-level constraint, when used, is treated as an optimization,
  never a trust boundary: every response is strictly re-validated against the same coverage list
  regardless of whether a constraint was applied, which backend served it, or what `finish_reason` it
  reported — see [§6.1c](#61c-structured-review-output-and-response-validation). Full threat model:
  `docs/structured-review-output-threat-model.md` (SOR-01..23, SOR-INH-1/2/3) and its companion
  `docs/security/feature-structured-review-output-sast-report.md`.
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
