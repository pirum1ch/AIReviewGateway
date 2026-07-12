# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

This repository currently contains only planning/specification documents — no source code, build files, or tests have been written yet:

- `Требования_Review_Gateway_v2.md` — full functional/technical/security requirements (source of truth for behavior).
- `# Итоговая архитектура AI Code Review Platform.md` — final architecture: component diagram, API contracts, state machine, DB schema, deployment model.
- `Системный промт для генерации кода Review Gateway.md` — the staged code-generation brief this project is meant to be built from (Java 21 / Spring Boot 3.2).

Read the two spec files in full before generating code — they are the authoritative reference; the summary below is only for quick orientation. There are no build/lint/test commands yet because no project skeleton exists. Once a Maven/Gradle project is created, this file should be updated with the real commands.

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

**Service:** `GET /health`, `GET /metrics`, `GET /backends`.

## Data model (PostgreSQL, via Flyway migrations)

- `review_inputs` — immutable input payload (diff, SHAs, prompt version) — enables re-running a Review without hitting GitLab again.
- `review_jobs` — the queue: status, priority, backend_id, worker_id, attempts, heartbeat_at, timestamps.
- `review_results` — raw model response (mandatory, stored before parsing), summary, tokens, duration, model.
- `review_comments` — parsed comments (file/line/severity/text) plus `discussion_id` for idempotent publishing.
- `review_events` — full audit trail (CREATED, CLAIMED, RUNNING, HEARTBEAT, RETRY, COMPLETED, PUBLISHED, FAILED, OBSOLETE).
- `backends` — registry of llama-server instances: url, model, capacity, status (`ACTIVE/SUSPECT/MAINTENANCE/OFFLINE`), last_seen. Backend load is derived from the count of currently-running jobs, not a separate counter.

## Retry / timeout / backend health

- Retry logic lives only in Gateway (attempts < 3 by default); Worker/Backend have no retry logic of their own.
- A job is considered stuck if `now - heartbeat_at` exceeds the configured interval (~3 min); it's returned to the queue. A separate max-total-duration cap is a backstop beyond heartbeat monitoring.
- Backends flip `ACTIVE → SUSPECT` on health-check failure or unavailability and are excluded from new assignments; a periodic check auto-recovers `SUSPECT → ACTIVE`.

## Code-generation workflow for this project

The system-prompt doc defines a staged build-out — when asked to "generate code" for this project without further qualification, follow this order and don't jump ahead of the requested stage:

1. JPA entities + Flyway migrations (schema matching the tables above exactly, plus status enums).
2. Repositories (including the `FOR UPDATE SKIP LOCKED` claim query and dedup lookups).
3. Services: `ReviewService`, `QueueManager`, `BackendDispatcher`, `RetryManager`, `TimeoutManager`, `GitLabPublisher`, `StateMachine`, plus `DeduplicationService`/`HeartbeatChecker`/`BackendHealthChecker`.
4. REST controllers: `ReviewController`, `JobController`, `AdminController`, plus `@ControllerAdvice` error handling.
5. Config/security: `SecurityConfig` (per-role token checks), `RestClient` setup for GitLab/llama-server calls (not the deprecated `RestTemplate`), `application.yml`, `@Scheduled` background jobs.
6. (Optional) Integration tests with Testcontainers/PostgreSQL.

Code quality bar from the brief: Java 21, Spring Boot 3.2, constructor injection, `@Transactional` with correct isolation on services, all external calls wrapped with error handling (+ retry where appropriate), `record` for DTOs, INFO logging for main actions / DEBUG for details.
