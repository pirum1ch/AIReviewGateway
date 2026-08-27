# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

The staged build-out described below is **complete**. This is a working Java 21 / Spring Boot 3.2 repo,
not a planning-only one. All of the following is merged into `master` (not living on an unmerged feature
branch):

- `src/main/java/com/review/gateway/` — the Gateway (this repo's root Maven module): entities, Flyway
  migrations, repositories, services, REST controllers, security config. Diff chunking (V2), Prompt
  Manager (V3, feature-flagged off by default), and Structured Review Output (V5, `promptVersion: v3`,
  not allowlisted by default) are all implemented on top of the original build-out. A `v3` Review is
  coverage-enforced (a per-file coverage list is always rendered into the prompt, regardless of chunk
  count or constraint mode) — every response is strictly parsed and validated against that coverage list
  on receipt, never trusting the decoder constraint, the backend's mode, or `finish_reason` (see
  `README.md` §4.5/§6.1c). Decoder-constrained mode (`backends.structured_output_mode`) is a *separate*,
  currently-off-by-default opt-in on top of `v3` — see "Structured Output Grammar Budget" below before
  ever turning it on.
- **Structured Output Grammar Budget fix** (`docs/structured-output-grammar-budget-architecture.md` +
  its threat model + SAST report): the JSON Schema `ReviewSchemaBuilder` builds for `v3` no longer emits
  `maxLength` on any field — a bounded string repetition is what tripped llama.cpp's grammar-parser
  complexity guard and made decoder-constrained mode fail 100% of the time in production before this fix.
  Length is now a **receipt-side truncation bound** (`gateway.structured.max-comment-chars`/
  `max-suggestion-chars`), never a schema constraint. Both backends currently ship with
  `structured_output_mode = OFF`; re-enabling either one requires the empirical probes in that
  architecture doc's §6 first (a schema that merely *looks* fine is not evidence it compiles).
- `worker/` — a separate Maven module, the stateless LLM Worker (Executor) that claims jobs and calls
  `llama-server`. See `worker/README.md`.
- `README.md` (root) — the authoritative integration guide: every endpoint, feature flag, and error code
  actually implemented, in plain language. **Read this, not the original spec docs, for current
  behavior**; read `DEPLOYMENT.md` (below) for the exhaustive parameter-by-parameter reference.
- `DEPLOYMENT.md` — step-by-step production deployment guide (secrets, PostgreSQL grants, systemd,
  Docker Compose) **and the full grouped configuration/environment-variable reference** — read its
  "Конфигурация: полный справочник параметров" section before touching any `.env`/`docker-compose.yml`/
  `application.yml` value; several parameters look interchangeable but are not (documented there with the
  incidents that found each one).
- `docs/` — architecture and security artifacts per feature: `implementation-architecture.md`,
  `threat-model.md`/`worker-threat-model.md` (baseline), `prompt-manager-architecture.md`/
  `prompt-manager-threat-model.md` (V3), `structured-review-output-architecture.md`/
  `structured-review-output-threat-model.md` (V5), `structured-output-grammar-budget-architecture.md`/
  `-threat-model.md` (the grammar-budget fix above), and per-feature SAST reports under `docs/security/`.

The original spec docs are still present at the repo root (`Требования_Review_Gateway_v2.md`, `#
Итоговая архитектура AI Code Review Platform.md`, `Системный промт для генерации кода Review
Gateway.md`) and remain the source of truth for *intended* behavior/requirements language, but for what
is actually implemented, current config defaults, and API contracts, `README.md` + `DEPLOYMENT.md` +
the code itself are authoritative — they get updated as features land; the spec docs do not.

## Build toolchain

No system-wide Java/Maven — use a local JDK 21 install (export these in every shell before building). The
exact path is machine-specific and has drifted before (`~/tools/jdk-21.0.11+10` doesn't exist on every
checkout of this repo) — verify with `ls` before trusting either line blindly; on this machine it's:

```bash
export JAVA_HOME="/c/Users/dmitr/.jdks/corretto-21.0.8"
export PATH="$JAVA_HOME/bin:$HOME/tools/apache-maven-3.9.9/bin:$PATH"
```

Build/test: `mvn -q compile`, `mvn -q test`. **No Docker on this machine** — Testcontainers will not work; for integration tests use Zonky embedded-postgres (`io.zonky.test:embedded-postgres`) or plain unit tests with mocks.

## What is being built

**Review Gateway** — the central service of an AI Code Review Platform. It receives AI-review requests from GitLab CI for merge requests, manages the full review lifecycle, dispatches long-running LLM jobs to a pool of stateless Workers via a PostgreSQL-backed queue, stores results, and publishes comments back to the MR.

Scale target: 20–30 MRs/day, long-running LLM tasks (up to tens of minutes), 1–10 LLM backend servers (llama-server instances), each paired 1:1 with a Worker on its own host (e.g. Mac mini via launchd).

## Architecture principles (non-negotiable, drive all design decisions)

- **Gateway is the sole owner of business logic and state.** No other component (Worker, Backend, GitLab CI) mutates Review state directly.
- **PostgreSQL is the single source of truth.** No in-memory state; nothing is cached that isn't reconstructible from the DB after a restart.
- **Worker is a fully stateless HTTP client.** It knows nothing about GitLab, the queue, retry, deduplication, or the DB schema. Its only job: claim a job, call llama-server, send heartbeats, submit the result. It never talks to GitLab or PostgreSQL.
- **The queue is implemented in PostgreSQL** via `SELECT ... FOR UPDATE SKIP LOCKED` in a short transaction — claim, then release the lock; ownership afterward is enforced by `RUNNING` status + heartbeat, not by holding a DB lock for the job's duration.
- **No extra infrastructure**: explicitly no Redis, Kafka, RabbitMQ, LiteLLM, Celery, Prometheus, or Kubernetes — see the requirements doc §15 for the reasoning per item. Don't reach for these when implementing features; solve it in PostgreSQL/Spring instead.
- **Idempotency everywhere a retry can happen**: Review creation (dedup key), result submission, comment publication, and all background jobs (timeout sweep, backend health check, publish retry) must be safe to run/repeat concurrently and after crashes.
- **Fail fast at the edge**: reject oversized diffs (`DIFF_TOO_LARGE`, HTTP 422) and other invalid input at `POST /reviews`, not deeper in the pipeline.
- **Gateway restarts must not disturb in-flight work**: `RUNNING` jobs are never reset on Gateway restart; the Worker keeps going and heartbeat is the only liveness signal.

## Review lifecycle (state machine)

```
NEW → QUEUED → RUNNING → COMPLETED → PUBLISHED
                   ↓
                 FAILED
```
Plus `CANCELLED` and `OBSOLETE`, reachable from any non-terminal state. All transitions happen only inside Gateway.

- `QUEUED → RUNNING`: only via a successful `/jobs/claim`.
- `RUNNING → COMPLETED`: successful Worker result.
- `RUNNING → FAILED`: retry limit exceeded, LLM error, or result-processing error.
- Any incomplete Review → `OBSOLETE` when a new `head_sha` arrives for the same MR.
- Manual admin cancel → `CANCELLED`; the Worker learns to stop via the heartbeat response (`continue: false`), not via a direct call.

**Deduplication key**: `(project_id, merge_request_id, head_sha)`. A new Review is created only if no existing Review for that key is in `NEW/QUEUED/RUNNING/COMPLETED/PUBLISHED`; otherwise the existing `reviewId` is returned. Only `FAILED/CANCELLED/OBSOLETE` predecessors allow a new Review to be created for the same key.

## API surface

**GitLab CI-facing:**
- `POST /reviews` — create Review (validates diff size against LLM context budget; returns `reviewId`/`QUEUED` or `422 DIFF_TOO_LARGE`).
- `GET /reviews/{id}` — status.
- `DELETE /reviews/{id}` — admin cancel.

**Worker-facing** (separate Bearer token from CI's, narrower privileges — Worker has no GitLab or Postgres credentials):
- `POST /jobs/claim` — claim next job (`FOR UPDATE SKIP LOCKED`, priority DESC then created_at ASC).
- `POST /jobs/{id}/heartbeat` — Worker pings every ~60s; response's `continue` flag tells it to stop if the Review went `OBSOLETE`/`CANCELLED`.
- `POST /jobs/{id}/result` — submit raw response + tokens + duration; idempotent no-op if job is no longer `RUNNING`.
- `POST /jobs/{id}/fail` — best-effort Worker-reported failure (Worker Observability & Claim Latency);
  drives `RetryManager.requeueOrFail` immediately instead of waiting out the passive stale-heartbeat
  sweep. Ownership re-checked under the job-row lock; `200`/`403`/`404` mirror heartbeat/result's opacity
  and never echo `reviewId`/status. `reason`/`detail` are audit-only, never influence the retry decision.

**Service:** `GET /health`, `GET /metrics`, `GET /backends`.

## Data model (PostgreSQL, via Flyway migrations)

- `review_inputs` — immutable input payload (diff, SHAs, prompt version) — enables re-running a Review without hitting GitLab again.
- `review_jobs` — the queue: status, priority, backend_id, worker_id, attempts, heartbeat_at, timestamps,
  last_error, `not_before` (V4, Worker Observability & Claim Latency — earliest instant a requeued job is
  claimable again; `NULL` = immediately).
- `review_results` — raw model response (mandatory, stored before parsing), summary, tokens, duration, model, `finish_reason` (V5, Structured Review Output — the backend's own completion-stop reason, whitelist-parsed before storage; `NULL` = not reported by an old Worker/backend build). Write-once per `(review_id, chunk_index)`: a retry's raw response/`finish_reason` are never stored, only the first attempt's.
- `review_comments` — parsed comments (file/line/severity/text) plus `discussion_id` for idempotent publishing.
- `review_events` — full audit trail (CREATED, CLAIMED, RUNNING, HEARTBEAT, RETRY, COMPLETED, PUBLISHED, FAILED, OBSOLETE).
- `backends` — registry of llama-server instances: url, model, capacity, status (`ACTIVE/SUSPECT/MAINTENANCE/OFFLINE`), last_seen (updated on successful probe only, as of V4), `probe_failed_since` (V4 — restart-safe continuous-failure-streak start, `NULL` = not currently failing), `structured_output_mode` (V5, Structured Review Output — per-backend wire shape for the decoder constraint, `NULL` = use `gateway.structured.default-mode`; a `CHECK` constraint bounds it to the closed `StructuredOutputMode` vocabulary). Backend load is derived from the count of currently-running jobs, not a separate counter.
- `review_chunks` (V2, diff chunking) — one row per file-based chunk when a diff is too large for one prompt; `review_jobs` is 1:N per Review once chunked.
- `review_prompt_sections` (V3, Prompt Manager, feature-flagged) — immutable, append-only per-section snapshot of the Git-sourced system prompt actually used for a Review (source project/ref/commit, content hash), written once at Review creation and never mutated. `reviews.prompt_bundle_mode` (`NONE`/`REPO`) records whether a Review was created under this feature at all — see `docs/prompt-manager-architecture.md`.

## Retry / timeout / backend health

- Retry logic lives only in Gateway (attempts < 3 by default); Worker/Backend have no retry logic of their own.
- A job is considered stuck if `now - heartbeat_at` exceeds the configured interval (~3 min); it's returned to the queue. A separate max-total-duration cap is a backstop beyond heartbeat monitoring. As of Worker Observability & Claim Latency, a Worker can also report its own abandonment via `POST /jobs/{id}/fail` (best-effort, never required for correctness) to collapse that passive wait to well under a second.
- Backends flip `ACTIVE → SUSPECT` only after a *continuous* failed-probe streak of `gateway.backend.failure-grace` (default 180s, fail-slow) and are excluded from new assignments; a single successful probe still auto-recovers `SUSPECT → ACTIVE` (recover-fast, unchanged). `gateway.retry.requeue-delay` (default 90s) is coupled to `failure-grace` at startup so a dead/restarting backend's attempt budget can never be exhausted faster than the backend can be detected and demoted.

## Workflow for new features on this project

The original staged build-out (entities/migrations → repositories → services → controllers →
config/security → tests) is done — it produced the current `master`. New work is a **feature**, not a
from-scratch build, and goes through a security-gated SDLC on its own branch, driven by subagents (the
user does not want the main assistant writing code itself for this project):

1. `architect` — design/architecture for the feature, checked against the non-negotiable principles
   above and the existing codebase (don't jump straight to backend-developer for anything non-trivial).
2. `appsec-engineer` — pre-implementation threat model (extends the existing `docs/threat-model.md` /
   `docs/worker-threat-model.md`; a feature gets its own `docs/<feature>-threat-model.md` if it's
   substantial, e.g. `docs/prompt-manager-threat-model.md`).
3. `backend-developer` — implementation, on `feature/<slug>` (see prior branches:
   `feature/diff-chunking`, `feature/prompt-manager`, `feature/structured-review-output`) or `fix/<slug>`
   for a bug/hardening pass against already-shipped behavior (e.g. `fix/structured-output-grammar-budget`,
   `fix/worker-observability-and-claim-latency`). Commit regularly, in logical chunks.
4. `qa-engineer` — functional/integration testing, beyond the developer's own unit tests.
5. `appsec-engineer` — SAST/verification round; findings get their own
   `docs/security/feature-<slug>-sast-report.md` (see the existing ones for format/numbering
   convention — each feature/fix gets its own prefix, e.g. `F-DC-`/`F-PM-`/`F-SOGB-`).
6. `backend-developer` — fix round against the SAST findings.
7. `appsec-engineer` — final verification; merge to `master` only after this passes.

Work only from the spec docs + what's already in the codebase — invent nothing beyond them; ask when in
doubt, but keep working on other features while waiting. Code quality bar from the original brief still
applies to all new code: Java 21, Spring Boot 3.2/3.5, constructor injection, `@Transactional` with
correct isolation, external calls wrapped with error handling (+ retry where appropriate), `record` for
DTOs, INFO logging for main actions / DEBUG for details.
