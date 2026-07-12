# Review Gateway — Implementation Architecture

Target: a backend developer can build the service from this document without further design decisions. Java 21, Spring Boot 3.2.x, PostgreSQL-only, Flyway, virtual threads, single Gateway instance, stateless Worker.

All decisions here are derived strictly from `Требования_Review_Gateway_v2.md`, `# Итоговая архитектура AI Code Review Platform.md`, and `CLAUDE.md`. Nothing beyond those specs is invented; where the specs left a gap (notably the missing home for the dedup key and the Review aggregate) the resolution is called out explicitly.

---

## 0. Key design decisions (read first)

1. **`reviews` is the aggregate root and the single source of truth for Review state.** The spec tables (`review_inputs`, `review_jobs`, …) reference a `review_id` and use a dedup key `(project_id, merge_request_id, head_sha)`, but none of the listed tables actually stores `project_id`/`merge_request_id`. The docs therefore imply an aggregate; we make it explicit as `reviews`.
2. **Status, priority, attempts, and the dedup key all live on `reviews`.** This is the decisive resolution of the note in the brief: keeping `status + priority + created_at` and `project_id + merge_request_id + head_sha` on one table lets *both* required indexes (dedup partial-unique index and queue-claim index) be single-table, and guarantees exactly one status column (no dual state machine, no drift). The queue is claimed directly on `reviews`.
3. **`review_jobs` becomes the current-execution record (1:1 with `reviews`)**: which backend/worker holds it now, `heartbeat_at`, and the attempt timestamps. It carries **no** status/priority/dedup columns. Per-attempt history lives in `review_events`. This is a small, deliberate departure from the literal column list in the architecture doc, sanctioned by the brief, and it keeps single-source-of-truth intact.
4. **Enums are stored as `VARCHAR(n) + CHECK IN (...)`, not native PG enums.** Justification: Flyway migrations stay transactional and trivially evolvable (adding a value = editing a CHECK, no `ALTER TYPE`), and JPA maps `@Enumerated(EnumType.STRING)` to `VARCHAR` cleanly. Native enums are rigid and awkward under Flyway. This is the boring, proven choice for a project at this scale.
5. **`RUNNING` is never reset on Gateway restart.** No startup reconciliation touches `RUNNING` reviews; the heartbeat sweep is the only liveness mechanism (requirement 2.7).

---

## 1. Maven project skeleton

- **groupId:** `com.review`
- **artifactId:** `review-gateway`
- **version:** `1.0.0-SNAPSHOT`
- **Java:** 21
- **Base package:** `com.review.gateway`
- **Build:** Maven, Spring Boot parent `3.2.12` (last 3.2.x line; upgrade within 3.2.x only unless a Boot 3.3 migration is scheduled).

### pom.xml (dependency list)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.12</version>
        <relativePath/>
    </parent>

    <groupId>com.review</groupId>
    <artifactId>review-gateway</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>review-gateway</name>

    <properties>
        <java.version>21</java.version>
        <zonky.embedded.version>2.5.1</zonky.embedded.version>
    </properties>

    <dependencies>
        <!-- Web (REST, JSON via Jackson) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Persistence -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Bean Validation on request DTOs -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Security: bearer-token filter for CI / WORKER / ADMIN roles -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- Actuator: ONLY for /actuator/health (DB health indicator) and readiness.
             NO Prometheus registry is added (see requirements §15). Business /metrics
             is a custom controller reading from PostgreSQL. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Flyway schema migrations -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- PostgreSQL driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ===== TEST ===== -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
            <!-- brings JUnit 5, Mockito, AssertJ, JsonPath -->
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Zonky embedded PostgreSQL: real Postgres binary in-process, no Docker.
             Replaces Testcontainers because Docker is unavailable on the build host. -->
        <dependency>
            <groupId>io.zonky.test</groupId>
            <artifactId>embedded-database-spring-test</artifactId>
            <version>${zonky.embedded.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Notes:
- Licences: all above are Apache-2.0 (Spring, Flyway, Jackson, HikariCP), except PostgreSQL JDBC driver (BSD-2), JUnit 5 (EPL-2.0), Mockito (MIT), Zonky embedded-postgres (Apache-2.0 wrapper over the PostgreSQL licence). No copyleft, no BSL — safe.
- `spring-boot-starter-web` uses embedded Tomcat; with `spring.threads.virtual.enabled=true` (Boot 3.2) request handling runs on virtual threads — exactly the profile in requirements §2.1 (many long HTTP waits).
- No `micrometer-registry-prometheus`, no Testcontainers, no Redis/Kafka clients — enforced by omission.

---

## 2. Package structure and class inventory (all 6 stages)

Base package `com.review.gateway`. Every class below maps to a specific codegen stage (S1–S6).

### `com.review.gateway` (root)
- `ReviewGatewayApplication` — `@SpringBootApplication` entry point. (S1)

### `model` (JPA entities + enums) — S1
- `Review` — aggregate root entity → `reviews`. Holds dedup key, `status`, `priority`, `attempts`.
- `ReviewInput` — immutable input payload → `review_inputs`.
- `ReviewJob` — current execution record → `review_jobs`.
- `ReviewResult` — raw model response + metrics → `review_results`.
- `ReviewComment` — parsed comment + publish tracking → `review_comments`.
- `ReviewEvent` — audit event → `review_events`.
- `Backend` — llama-server registry entry → `backends`.
- `enums/ReviewStatus` — NEW, QUEUED, RUNNING, COMPLETED, PUBLISHED, FAILED, CANCELLED, OBSOLETE.
- `enums/BackendStatus` — ACTIVE, SUSPECT, MAINTENANCE, OFFLINE.
- `enums/EventType` — CREATED, CLAIMED, RUNNING, HEARTBEAT, RETRY, COMPLETED, PUBLISHED, FAILED, OBSOLETE, CANCELLED.
- `enums/Severity` — INFO, MINOR, MAJOR, CRITICAL (parsed comment severity).

### `repository` — S2
- `ReviewRepository` — dedup lookup (active-status filter), OBSOLETE sweep, **queue claim query** (`FOR UPDATE SKIP LOCKED`), publish-retry candidates, statistics aggregates.
- `ReviewInputRepository` — insert/fetch immutable payload by review.
- `ReviewJobRepository` — upsert current execution row, heartbeat update, timeout-sweep query, **running-count-per-backend** capacity query.
- `ReviewResultRepository` — idempotent insert-if-absent by `review_id`.
- `ReviewCommentRepository` — bulk insert parsed comments, fetch unpublished by review.
- `ReviewEventRepository` — append-only audit insert; event queries for `/metrics`.
- `BackendRepository` — find by name, find ACTIVE, SUSPECT recovery candidates, status update.

### `service` — S3
- `ReviewService` — orchestrates create (validate → dedup → persist input+review NEW→QUEUED → audit), status read, admin cancel.
- `DeduplicationService` — resolves existing active Review by dedup key; interprets unique-violation as "return existing".
- `DiffSizeValidator` — estimates diff tokens, enforces context budget, raises `DiffTooLargeException`.
- `StateMachine` — the only place transitions are validated and applied; rejects illegal transitions.
- `QueueManager` — implements claim (SKIP LOCKED), records CLAIMED/RUNNING, processes heartbeat + result submission.
- `BackendDispatcher` — resolves backend by name, checks status + free capacity via running-job count.
- `RetryManager` — on failure: requeue if `attempts < max`, else FAILED; writes RETRY/FAILED events.
- `TimeoutManager` — sweep for stuck jobs (heartbeat miss and max-duration cap); delegates to RetryManager.
- `HeartbeatChecker` — `@Scheduled` driver invoking TimeoutManager.
- `BackendHealthChecker` — `@Scheduled` probe: ACTIVE→SUSPECT on failure, SUSPECT→ACTIVE on recovery.
- `ResultProcessor` — stores raw response first, then parses via CommentParser, persists comments, transitions RUNNING→COMPLETED.
- `CommentParser` — parses raw LLM output into structured `ParsedComment`s (file/line/severity/text) + summary.
- `GitLabPublisher` — publishes unpublished comments idempotently via GitLab discussions API; COMPLETED→PUBLISHED.
- `PublishRetryService` — `@Scheduled` driver retrying publication for COMPLETED reviews.
- `EventService` — single entry point to append `review_events` (secret-scrubbed).
- `StatisticsService` — computes `/metrics` aggregates from DB.

### `controller` — S4
- `ReviewController` — `POST /reviews`, `GET /reviews/{id}` (CI role).
- `JobController` — `POST /jobs/claim`, `POST /jobs/{id}/heartbeat`, `POST /jobs/{id}/result` (WORKER role).
- `AdminController` — `DELETE /reviews/{id}`, `GET /backends`, `GET /metrics` (ADMIN role).
- `HealthController` — `GET /health` (public liveness JSON; distinct from `/actuator/health`).
- `GlobalExceptionHandler` — `@RestControllerAdvice`; maps exceptions → `ErrorResponse` (incl. 422 `DIFF_TOO_LARGE`, 404, 409, 401/403).

### `dto` (records) — S4
Request/response contracts, section 11 below.

### `config` — S5
- `GatewayProperties` — `@ConfigurationProperties("gateway")` typed config surface.
- `SecurityConfig` — stateless `SecurityFilterChain`, role→endpoint mapping.
- `TokenAuthenticationFilter` — `OncePerRequestFilter` resolving bearer token → role.
- `RestClientConfig` — two `RestClient` beans: `gitLabRestClient`, `backendProbeRestClient` (timeouts, base URL, auth header).
- `SchedulingConfig` — `@EnableScheduling` + virtual-thread `TaskScheduler`.
- `WebConfig` (optional) — Jackson tuning, request-size limits.

### `exception` — S4/S5
- `DiffTooLargeException` → 422 `DIFF_TOO_LARGE`.
- `ReviewNotFoundException` → 404.
- `InvalidStateTransitionException` → 409.
- `JobNotClaimableException` → 409/204 handling in claim.
- `BackendUnavailableException` → internal (drives SUSPECT).
- `GitLabPublishException` → internal (drives publish retry).

### `test` (S2/S3/S6 — folded into each feature's QA)
- `AbstractPostgresIntegrationTest` — Zonky `@AutoConfigureEmbeddedDatabase` base.
- Repository slice tests (`@DataJpaTest` + Zonky) — dedup index, claim SKIP LOCKED, capacity count.
- Service unit tests (Mockito) — StateMachine, RetryManager, DeduplicationService, DiffSizeValidator.
- Web/security tests (`@WebMvcTest` + `spring-security-test`) — token roles, 422, idempotent result.
- Full-flow integration test — create → claim → heartbeat → result → publish.

---

## 3. Flyway schema — `V1__initial_schema.sql`

File: `src/main/resources/db/migration/V1__initial_schema.sql`. Optional `V2__seed_backends.sql` for local dev seed. Identity columns use `GENERATED BY DEFAULT AS IDENTITY`.

```sql
-- ============================================================
-- reviews : aggregate root + queue owner (single source of truth for status)
-- ============================================================
CREATE TABLE reviews (
    id                BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id        BIGINT       NOT NULL,
    merge_request_id  BIGINT       NOT NULL,          -- MR IID
    head_sha          VARCHAR(64)  NOT NULL,
    base_sha          VARCHAR(64)  NOT NULL,
    prompt_version    VARCHAR(32)  NOT NULL,
    status            VARCHAR(16)  NOT NULL
        CONSTRAINT ck_reviews_status CHECK (status IN
            ('NEW','QUEUED','RUNNING','COMPLETED','PUBLISHED','FAILED','CANCELLED','OBSOLETE')),
    priority          INTEGER      NOT NULL DEFAULT 10,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Dedup: at most ONE active review per (project, MR, head_sha).
-- Active = the 5 statuses that block a new review (req. 1.5). Enforced at DB level;
-- concurrent create -> unique violation -> app returns the existing reviewId.
CREATE UNIQUE INDEX ux_reviews_dedup_active
    ON reviews (project_id, merge_request_id, head_sha)
    WHERE status IN ('NEW','QUEUED','RUNNING','COMPLETED','PUBLISHED');

-- Queue claim: priority DESC, created_at ASC over QUEUED rows.
CREATE INDEX ix_reviews_queue
    ON reviews (priority DESC, created_at ASC)
    WHERE status = 'QUEUED';

-- OBSOLETE sweep when a new head_sha arrives for the same MR.
CREATE INDEX ix_reviews_mr ON reviews (project_id, merge_request_id);

-- Publish-retry candidate scan.
CREATE INDEX ix_reviews_status ON reviews (status);

-- ============================================================
-- review_inputs : immutable payload (enables re-run without GitLab, req. 1.2/4.3)
-- ============================================================
CREATE TABLE review_inputs (
    id                BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id         BIGINT       NOT NULL UNIQUE
        REFERENCES reviews (id) ON DELETE CASCADE,
    diff              TEXT         NOT NULL,
    prompt_version    VARCHAR(32)  NOT NULL,
    head_sha          VARCHAR(64)  NOT NULL,
    base_sha          VARCHAR(64)  NOT NULL,
    estimated_tokens  INTEGER,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- backends : llama-server registry
-- ============================================================
CREATE TABLE backends (
    id          BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,          -- e.g. "mac-mini-01" (worker sends this)
    url         VARCHAR(256) NOT NULL,
    model       VARCHAR(128) NOT NULL,
    capacity    INTEGER      NOT NULL DEFAULT 1,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT ck_backends_status CHECK (status IN
            ('ACTIVE','SUSPECT','MAINTENANCE','OFFLINE')),
    last_seen   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- review_jobs : current execution record (1:1 with reviews)
-- No status/priority here -> reviews is the sole status owner.
-- ============================================================
CREATE TABLE review_jobs (
    id            BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id     BIGINT       NOT NULL UNIQUE
        REFERENCES reviews (id) ON DELETE CASCADE,
    backend_id    BIGINT       REFERENCES backends (id),
    worker_id     VARCHAR(64),
    heartbeat_at  TIMESTAMPTZ,
    claimed_at    TIMESTAMPTZ,
    started_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Capacity count (running jobs per backend) + timeout sweep.
CREATE INDEX ix_jobs_backend    ON review_jobs (backend_id);
CREATE INDEX ix_jobs_heartbeat  ON review_jobs (heartbeat_at);

-- ============================================================
-- review_results : raw model response (stored BEFORE parsing) + metrics
-- ============================================================
CREATE TABLE review_results (
    id                 BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id          BIGINT       NOT NULL UNIQUE
        REFERENCES reviews (id) ON DELETE CASCADE,     -- UNIQUE => idempotent result
    raw_response       TEXT         NOT NULL,          -- mandatory (req. 1.9)
    summary            TEXT,
    prompt_tokens      INTEGER,
    completion_tokens  INTEGER,
    total_tokens       INTEGER,
    duration_ms        BIGINT,
    model              VARCHAR(128),
    backend_id         BIGINT       REFERENCES backends (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- review_comments : parsed comments + idempotent publish tracking
-- ============================================================
CREATE TABLE review_comments (
    id             BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id      BIGINT       NOT NULL
        REFERENCES reviews (id) ON DELETE CASCADE,
    file_path      VARCHAR(1024),
    line_number    INTEGER,
    severity       VARCHAR(16)
        CONSTRAINT ck_comment_severity CHECK (severity IN ('INFO','MINOR','MAJOR','CRITICAL')),
    comment        TEXT         NOT NULL,
    discussion_id  VARCHAR(128),                       -- GitLab discussion id, set after publish
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Publish-retry: fetch only not-yet-published comments of a review.
CREATE INDEX ix_comments_unpublished
    ON review_comments (review_id)
    WHERE published_at IS NULL;

-- ============================================================
-- review_events : append-only audit trail
-- ============================================================
CREATE TABLE review_events (
    id          BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id   BIGINT       NOT NULL
        REFERENCES reviews (id) ON DELETE CASCADE,
    event_type  VARCHAR(32)  NOT NULL
        CONSTRAINT ck_event_type CHECK (event_type IN
            ('CREATED','CLAIMED','RUNNING','HEARTBEAT','RETRY',
             'COMPLETED','PUBLISHED','FAILED','OBSOLETE','CANCELLED')),
    worker_id   VARCHAR(64),
    backend_id  BIGINT,
    details     TEXT,                                  -- free text / JSON, NEVER secrets
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_events_review ON review_events (review_id, created_at);
```

`backend_id` in `review_jobs`/`review_results` references `backends.id` (BIGINT). The `/jobs/claim` API sends `backendId` as the **name** string (`"mac-mini-01"`); `BackendDispatcher` resolves name → row.

---

## 4. State machine — full transition table

All transitions happen only inside `StateMachine`, inside a `@Transactional` service method, and each writes a `review_events` row. `reviews.status` is the only status column.

| # | From | To | Trigger | Guard |
|---|------|-----|---------|-------|
| 1 | — | `NEW` | `POST /reviews` (create) | dedup passed (no active review for key) AND diff within token budget |
| 2 | `NEW` | `QUEUED` | input + job rows persisted | same create transaction as #1 (NEW is transient, never left dangling) |
| 3 | `QUEUED` | `RUNNING` | `POST /jobs/claim` selects the row | backend ACTIVE AND running-count < capacity AND row won via `FOR UPDATE SKIP LOCKED` |
| 4 | `RUNNING` | `COMPLETED` | `POST /jobs/{id}/result` accepted | job currently `RUNNING` AND raw response stored AND parsed successfully |
| 5 | `RUNNING` | `QUEUED` | heartbeat-miss OR max-duration OR worker/backend error | `attempts < max_attempts` (retry) — attempts already incremented on claim |
| 6 | `RUNNING` | `FAILED` | same triggers as #5, or unrecoverable parse/LLM error | `attempts >= max_attempts` OR error classified as fatal |
| 7 | `COMPLETED` | `PUBLISHED` | GitLab publish of all comments succeeds | review not OBSOLETE/CANCELLED AND every comment has `published_at` |
| 8 | `COMPLETED` | `COMPLETED` | GitLab unavailable during publish | transient GitLab error → stay COMPLETED, retry later (req. 1.10) |
| 9 | `NEW` | `OBSOLETE` | new `head_sha` for same MR | there exists a review with a different head_sha for same (project, MR) |
| 10 | `QUEUED` | `OBSOLETE` | new `head_sha` for same MR | non-terminal |
| 11 | `RUNNING` | `OBSOLETE` | new `head_sha` for same MR | non-terminal; worker told to stop via next heartbeat (`continue:false`) |
| 12 | `COMPLETED` | `OBSOLETE` | new `head_sha` for same MR | not yet PUBLISHED (req. 1.5: only non-PUBLISHED become OBSOLETE) |
| 13 | `NEW` | `CANCELLED` | `DELETE /reviews/{id}` (admin) | non-terminal |
| 14 | `QUEUED` | `CANCELLED` | `DELETE /reviews/{id}` (admin) | non-terminal |
| 15 | `RUNNING` | `CANCELLED` | `DELETE /reviews/{id}` (admin) | non-terminal; worker stops via heartbeat `continue:false` |
| 16 | `COMPLETED` | `CANCELLED` | `DELETE /reviews/{id}` (admin) | non-terminal |

Terminal states: `PUBLISHED`, `FAILED`, `CANCELLED`, `OBSOLETE`. No transitions leave them. Any transition not in this table is rejected with `InvalidStateTransitionException` (HTTP 409).

Interaction with dedup: only predecessors in `FAILED/CANCELLED/OBSOLETE` allow a fresh Review for the same key — this is exactly what the partial unique index encodes (those three are excluded from the active set).

---

## 5. Queue claim (FOR UPDATE SKIP LOCKED) + capacity

Claim is a **short transaction** (`REQUIRES_NEW`, read-committed): select, update, commit. The DB lock is released immediately; ownership afterward is `RUNNING` + `heartbeat`, never a held lock (req. 2.3).

Step 1 — capacity check for the requesting backend (by resolved `backend_id`):

```sql
SELECT count(*)
FROM review_jobs j
JOIN reviews r ON r.id = j.review_id
WHERE j.backend_id = :backendId
  AND r.status = 'RUNNING';
```

If `count >= backends.capacity` OR backend status <> `ACTIVE`, return **no job** (`JobController` responds `204 No Content`). This is how "capacity by counting RUNNING jobs" (req. 1.6) is woven in — there is no separate counter.

Step 2 — claim the highest-priority queued review:

```sql
SELECT r.id
FROM reviews r
WHERE r.status = 'QUEUED'
ORDER BY r.priority DESC, r.created_at ASC
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

`SKIP LOCKED` guarantees each queued review is handed to exactly one worker even under concurrent claims (req. 1.3). (JPA: native query, `@Lock` is not expressive enough for `SKIP LOCKED`, so use a native query returning the id.)

Step 3 — in the same transaction, transition and assign:

```sql
UPDATE reviews
   SET status = 'RUNNING', attempts = attempts + 1, updated_at = now()
 WHERE id = :reviewId;

INSERT INTO review_jobs (review_id, backend_id, worker_id, heartbeat_at, claimed_at, started_at)
VALUES (:reviewId, :backendId, :workerId, now(), now(), now())
ON CONFLICT (review_id) DO UPDATE
   SET backend_id  = EXCLUDED.backend_id,
       worker_id   = EXCLUDED.worker_id,
       heartbeat_at= now(),
       claimed_at  = now(),
       started_at  = now(),
       finished_at = NULL,
       last_error  = NULL,
       updated_at  = now();
```

The `ON CONFLICT` handles the retry case (same review re-claimed after a requeue). Commit → lock released. Then `EventService` writes `CLAIMED`/`RUNNING`. The claim response payload is built from `review_inputs.diff`.

---

## 6. Idempotency mechanisms per operation

| Operation | Mechanism |
|-----------|-----------|
| **Create review** (`POST /reviews`) | Partial unique index `ux_reviews_dedup_active` is the source of truth. `DeduplicationService` first does a `SELECT` for an active review by key; if found, returns its id. On the race where two requests pass the SELECT, the second `INSERT` hits the unique violation → caught → re-read and return the existing `reviewId`. Same `200/QUEUED` result either way. |
| **Result submission** (`POST /jobs/{id}/result`) | Guard: proceed only if review is `RUNNING`; otherwise `200 OK` no-op (req. 1.9). `review_results.review_id` is `UNIQUE`; insert uses `ON CONFLICT (review_id) DO NOTHING`, so a duplicate delivery never mutates stored result or state. |
| **Comment publication** | Per-comment `published_at` + `discussion_id`. Publisher selects only `WHERE published_at IS NULL`, posts one discussion, then immediately writes back `discussion_id`/`published_at` in a per-comment transaction. Re-runs skip already-published comments → no duplicates (req. 1.10). |
| **Timeout / heartbeat sweep** | Conditional `UPDATE ... WHERE status='RUNNING' AND heartbeat_at < :cutoff` — repeating the sweep or running after restart is a no-op if the row already moved on. |
| **Backend health check** | Idempotent status writes (`ACTIVE`↔`SUSPECT`) guarded by current status; repeated runs converge. |
| **Publish retry** | Selects `status='COMPLETED'` reviews and re-invokes the (already idempotent) publisher; converges to `PUBLISHED`. |
| **OBSOLETE sweep** | `UPDATE ... WHERE status IN (NEW,QUEUED,RUNNING,COMPLETED) AND head_sha <> :newHead` — idempotent, harmless to repeat. |

Residual risk (documented): a crash between the GitLab discussion POST returning and the local `published_at` write could double-post one comment. Mitigated by per-comment commit granularity; accepted at this scale.

---

## 7. Security design

Stateless, static bearer tokens. Three roles, three tokens (CI and Worker are physically different tokens per req. 3):

- `TokenAuthenticationFilter` (`OncePerRequestFilter`): read `Authorization: Bearer <token>`, constant-time compare against the three configured tokens, set an `Authentication` with the matching authority (`ROLE_CI`, `ROLE_WORKER`, `ROLE_ADMIN`). Unknown/missing token on a protected path → 401.
- `SecurityConfig` (`SecurityFilterChain`): `csrf().disable()`, `sessionManagement(STATELESS)`, then:

| Path / method | Required role |
|---------------|---------------|
| `POST /reviews`, `GET /reviews/{id}` | `CI` (or `ADMIN`) |
| `DELETE /reviews/{id}` | `ADMIN` |
| `GET /backends`, `GET /metrics` | `ADMIN` (or `CI`) |
| `POST /jobs/claim`, `POST /jobs/{id}/heartbeat`, `POST /jobs/{id}/result` | `WORKER` |
| `GET /health`, `GET /actuator/health` | `permitAll` |

- The **GitLab API token** lives only in `GatewayProperties` (`gateway.gitlab.token`), used solely by `gitLabRestClient`. The Worker role can reach none of the GitLab or DB paths — the Worker has no GitLab or Postgres credentials at all (req. 2.5, 3).
- **Secrets never logged / never audited:** `EventService` scrubs `details`; `GatewayProperties` token fields use a masking `toString()`; log config must not dump `Authorization` headers. Input diffs are payload, not secrets, but are never echoed into `review_events`.

---

## 8. Background `@Scheduled` jobs

All are idempotent, single-instance (Gateway runs one instance), and safe across restart. Run on the virtual-thread `TaskScheduler` from `SchedulingConfig`.

| Job (bean.method) | Interval key | Action | Idempotency |
|-------------------|--------------|--------|-------------|
| `HeartbeatChecker.sweepStalled` | `gateway.scheduler.heartbeat-check-interval` (~30s) | `RUNNING` where `now - heartbeat_at > heartbeat.timeout` → requeue (`attempts<max`) or FAIL, via `RetryManager`. | Conditional `UPDATE WHERE status='RUNNING'`; already-moved rows untouched. |
| `TimeoutManager.enforceMaxDuration` | reuse heartbeat interval | `RUNNING` where `now - started_at > job.max-duration` → same requeue/FAIL path (backstop beyond heartbeat). | Same conditional guard. |
| `BackendHealthChecker.probe` | `gateway.scheduler.backend-health-interval` (~60s) | Probe each backend URL; ACTIVE→SUSPECT on failure, SUSPECT→ACTIVE on recovery; update `last_seen`. | Status writes guarded by current status; convergent. |
| `PublishRetryService.retryPublications` | `gateway.scheduler.publish-retry-interval` (~60s) | For each `COMPLETED` review not OBSOLETE/CANCELLED, publish unpublished comments; success → PUBLISHED. | Publisher only touches `published_at IS NULL` comments. |
| `ReviewService.sweepObsolete` (event-driven, invoked on create) | on create | On new head_sha, mark prior non-terminal reviews of the MR OBSOLETE. | `UPDATE ... WHERE head_sha <> :new AND status IN (...)`. |

Requeue/FAIL and RUNNING rows are never touched on Gateway startup — heartbeat sweep is the only path that reclaims them (req. 2.7).

---

## 9. Config surface — `application.yml`

```yaml
spring:
  threads:
    virtual:
      enabled: true                 # Java 21 virtual threads (requirements §2.1)
  datasource:
    url: jdbc:postgresql://localhost:5432/review_gateway
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
  jpa:
    hibernate:
      ddl-auto: validate            # schema owned by Flyway only
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health             # NO prometheus; business metrics = custom /metrics
  endpoint:
    health:
      probes:
        enabled: true

gateway:
  diff:
    context-window: 16384
    prompt-reserve: 2000
    answer-reserve: 4000
    max-diff-tokens: 10000          # derived cap; DIFF_TOO_LARGE above this
    chars-per-token: 4              # heuristic for the token estimator
  heartbeat:
    interval: 60s                   # expected worker ping cadence
    timeout: 180s                   # stuck if now - heartbeat_at exceeds this
  retry:
    max-attempts: 3
  job:
    max-duration: 45m               # hard cap backstop
  scheduler:
    heartbeat-check-interval: 30s
    backend-health-interval: 60s
    publish-retry-interval: 60s
  gitlab:
    base-url: https://gitlab.example.com/api/v4
    token: ${GITLAB_TOKEN}          # masked in toString; only Gateway holds this
    connect-timeout: 5s
    read-timeout: 30s
  security:
    ci-token: ${CI_TOKEN}
    worker-token: ${WORKER_TOKEN}
    admin-token: ${ADMIN_TOKEN}
```

`GatewayProperties` binds `gateway.*` with `@ConfigurationProperties` and JSR-380 validation (`@Positive`, `@NotBlank`). All secrets come from environment variables, never committed.

---

## 10. Feature decomposition → 3 branches

QA (unit + Zonky integration tests) is folded into each branch — a branch is not "done" until its tests pass. Merge order is strict (each depends on the previous).

### `feature/01-data-model` (codegen stages 1–2)
- Scope: all `model` entities + enums, `V1__initial_schema.sql` (+ optional `V2` seed), all `repository` interfaces incl. native claim query, dedup lookup, capacity count, timeout-sweep query.
- QA: `@DataJpaTest` + Zonky embedded Postgres — dedup partial-unique index behaviour, `FOR UPDATE SKIP LOCKED` claim under concurrency, running-count-per-backend, Flyway migrates cleanly.
- Exit criteria: schema applies, entities map, repository queries green.

### `feature/02-core-services` (codegen stage 3)
- Scope: `StateMachine`, `ReviewService`, `DeduplicationService`, `DiffSizeValidator`, `QueueManager`, `BackendDispatcher`, `RetryManager`, `TimeoutManager`, `HeartbeatChecker`, `BackendHealthChecker`, `ResultProcessor`, `CommentParser`, `GitLabPublisher`, `PublishRetryService`, `EventService`, `StatisticsService`. `@Transactional` boundaries + `REQUIRES_NEW` short claim tx.
- QA: Mockito unit tests for transition guards, retry limits, dedup race handling, diff-limit; Zonky integration tests for claim→heartbeat→result→complete and OBSOLETE/CANCELLED sweeps.
- Exit criteria: full lifecycle drivable from service layer (no controllers yet).

### `feature/03-api-security` (codegen stages 4–5)
- Scope: `ReviewController`, `JobController`, `AdminController`, `HealthController`, `GlobalExceptionHandler`, all `dto` records; `SecurityConfig` + `TokenAuthenticationFilter`, `RestClientConfig` (RestClient, not RestTemplate), `GatewayProperties`, `SchedulingConfig` (virtual-thread scheduler), `application.yml`, all `@Scheduled` wiring.
- QA: `@WebMvcTest` + `spring-security-test` for role matrix, 422 `DIFF_TOO_LARGE`, idempotent duplicate result → 200; one end-to-end integration test (create → claim → heartbeat → result → publish) with GitLab RestClient stubbed.
- Exit criteria: service deployable as a systemd unit; all endpoints enforce roles.

Codegen stage 6 (extended integration tests) is not a separate branch — it is the QA layer distributed across all three branches, since Testcontainers is replaced by Zonky and tests ship with the code they cover.

---

## 11. Contracts for the developer (records + external calls)

DTO records (JSON, `dto` package). Field types indicative; add `@NotNull`/`@Positive` per field.

```
// CI-facing
CreateReviewRequest(long projectId, long mergeRequestId, String headSha,
                    String baseSha, String diff, String promptVersion, Integer priority)
CreateReviewResponse(long reviewId, String status)                 // 200; 422 -> ErrorResponse
ReviewStatusResponse(long reviewId, String status, int attempts,
                     Instant createdAt, Instant updatedAt, Integer commentCount)

// Worker-facing
ClaimJobRequest(String backendId)                                  // backend name
ClaimJobResponse(long jobId, long reviewId, JobPayload payload)    // 204 when nothing to claim
JobPayload(String diff, String promptVersion)
HeartbeatRequest(String workerId)
HeartbeatResponse(boolean shouldContinue)                          // false => OBSOLETE/CANCELLED
SubmitResultRequest(String rawResponse, Integer promptTokens,
                    Integer completionTokens, long durationMs, String model)
SubmitResultResponse(long reviewId, String status)                // idempotent 200

// Service / admin
BackendView(long id, String name, String model, int capacity,
            String status, int running, Instant lastSeen)
MetricsResponse(long total, Map<String,Long> byStatus, double avgQueueMs,
                double avgRunMs, long totalComments, long retries)
ErrorResponse(String error, String message)                       // error="DIFF_TOO_LARGE" etc.

// Internal parse contract
ParsedComment(String filePath, Integer lineNumber, Severity severity, String text)
```

External call contracts:
- **GitLab publish** (via `gitLabRestClient`): `POST {base-url}/projects/{projectId}/merge_requests/{mrIid}/discussions` with `PRIVATE-TOKEN`/`Bearer` header; response yields `discussion_id` stored per comment.
- **Backend probe** (via `backendProbeRestClient`): lightweight `GET {backend.url}/health` (or `/v1/models`) with short timeout; failure → `BackendUnavailableException` → SUSPECT.
- The Gateway never calls llama-server for inference — only the Worker does (Gateway↔llama contact is limited to the health probe).

---

## 12. Risks & trade-offs

- **`reviews`-as-queue-owner deviates from the literal `review_jobs.status` column in the architecture doc.** Chosen to keep a single status column and make both required indexes single-table. Trade-off: `review_jobs` is thinner than the doc's field list (status/priority/attempts moved to `reviews`); per-attempt detail is recovered from `review_events`. Explicitly sanctioned by the brief.
- **Single Gateway instance** is a deliberate SPOF at this scale (systemd `Restart=always`, `RUNNING` survives restart). Horizontal scaling is a future change; the SKIP-LOCKED claim already tolerates multiple claimers, so scaling out is possible later without a redesign.
- **Comment double-post window** (section 6) accepted at 20–30 MRs/day.
- **VARCHAR+CHECK enums** trade native-enum type-safety for migration simplicity — the right call for a Flyway-driven schema.
- **Token estimation is heuristic** (`chars-per-token`); if precision matters later, swap `DiffSizeValidator` for a real tokenizer without touching the rest of the pipeline.

---

## 13. Handoff to the developer

Build in branch order `01 → 02 → 03`, following the 6-stage codegen brief inside each branch. Non-negotiables to keep verifying at every stage: PostgreSQL is the only infra; Worker never touches DB/GitLab; `RUNNING` untouched on restart; `RestClient` not `RestTemplate`; constructor injection; records for DTOs; `spring.threads.virtual.enabled=true`; every state change goes through `StateMachine` and writes a `review_events` row; no secret ever reaches logs or `review_events`.
