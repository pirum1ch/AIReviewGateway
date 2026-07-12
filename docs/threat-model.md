# Review Gateway — Threat Model (pre-implementation)

Status: PRE-IMPLEMENTATION. No code exists yet. This model threat-models the approved
architecture in `docs/implementation-architecture.md` (source of truth), the requirements
`Требования_Review_Gateway_v2.md` (§3 security), and `# Итоговая архитектура AI Code Review Platform.md`
(§12 security), under the constraints in `CLAUDE.md`.

Methodology: STRIDE per element/flow, mapped to OWASP Top 10 (2021) and CWE. Risk = qualitative
Likelihood × Impact (H/M/L). Every mitigation is mapped to a planned class from the implementation
architecture and marked MUST / SHOULD / ACCEPTED-RISK.

Hard constraints respected throughout: no new infrastructure (no Vault, no OAuth server, no Redis,
no Kafka), single Gateway instance, PostgreSQL + Spring + config-file world only, Worker stays dumb
(no GitLab/DB access, REST-only). Mitigations are chosen to fit that box.

---

## 1. System decomposition — trust boundaries & data flows

### Actors / components
- **GitLab CI job** — untrusted-ish network client; holds `CI_TOKEN`. Runs per-MR.
- **Worker** (1..10, on Mac minis via launchd) — semi-trusted remote host; holds `WORKER_TOKEN`.
  Stateless HTTP client. No GitLab/DB creds.
- **Admin** — human operator; holds `ADMIN_TOKEN`.
- **Gateway** (Spring Boot, single instance) — sole owner of business logic/state.
- **PostgreSQL** — single source of truth. Holds diffs, results, tokens-in-events-must-not.
- **GitLab API** — external; Gateway authenticates with `GITLAB_TOKEN`.
- **llama-server backends** — registered in `backends.url`, probed by Gateway; run inference for Workers.

### Trust boundaries (TB)
- **TB-CI**: CI network → Gateway REST (`Authorization: Bearer <CI_TOKEN>`).
- **TB-WORKER**: Worker hosts (remote, possibly over WAN/VPN) → Gateway REST (`WORKER_TOKEN`).
- **TB-ADMIN**: Admin → Gateway REST (`ADMIN_TOKEN`).
- **TB-DB**: Gateway → PostgreSQL over JDBC (DB credentials).
- **TB-GITLAB**: Gateway → GitLab API (`GITLAB_TOKEN`), egress to external SaaS/self-hosted.
- **TB-BACKEND**: Gateway → llama-server `/health` probe using **admin-controlled URLs** (SSRF surface).

### ASCII Data Flow Diagram (numbered flows)

```
        TB-CI                                                     TB-GITLAB
  ┌───────────────┐   (1) POST /reviews {diff,SHAs}          ┌───────────────┐
  │  GitLab CI    │ ───────────────────────────────────────► │               │
  │  job          │ ◄─────────────────────────────────────── │               │
  └───────────────┘   (2) 200 {reviewId,status} / 422        │               │
                                                              │               │
        TB-ADMIN                                              │   Review      │  (6) POST /projects/{id}/mr/{iid}/
  ┌───────────────┐   (8) DELETE /reviews/{id}, GET /metrics, │   Gateway     │      discussions  {comment text}
  │  Admin        │ ───────  GET /backends ─────────────────► │  (single      │ ─────────────────────────────► GitLab API
  └───────────────┘                                           │   instance)   │ ◄───────────────────────────── {discussion_id}
                                                              │               │
        TB-WORKER                                             │  StateMachine │        TB-BACKEND
  ┌───────────────┐   (3) POST /jobs/claim {backendId}        │  QueueManager │  (7) GET {backends.url}/health
  │  Worker       │ ───────  /jobs/{id}/heartbeat ──────────► │  Dispatcher   │ ─────────────────────────────► llama-server
  │  (Mac mini)   │          /jobs/{id}/result               │  Publisher    │ ◄───────────────────────────── 200 / models
  │               │ ◄─────────────────────────────────────── │               │
  └──────┬────────┘   (4) job payload {diff, promptVersion}  └──────┬────────┘
         │                                                          │ (5) JDBC
         │ (9) localhost inference (OUT OF GATEWAY SCOPE)           ▼   TB-DB
         ▼                                                   ┌───────────────┐
    llama-server                                             │  PostgreSQL   │  reviews, review_inputs(diff),
                                                             │               │  results(raw_response), comments,
                                                             └───────────────┘  events(audit), backends(url)
```

Flow legend:
- (1)(2) CI create/status — crosses **TB-CI**.
- (3)(4) Worker claim/heartbeat/result — crosses **TB-WORKER**; **(4) carries the proprietary diff outbound**.
- (5) Gateway↔DB JDBC — crosses **TB-DB**.
- (6) Gateway→GitLab publish — crosses **TB-GITLAB**; **LLM-generated text leaves the trust boundary into a rendered UI**.
- (7) Gateway→backend health probe — crosses **TB-BACKEND**; **destination URL is data from the DB (admin-controlled) → SSRF sink**.
- (8) Admin ops — crosses **TB-ADMIN**.
- (9) Worker→llama localhost — outside Gateway's control; noted only because a compromised backend can influence (4)→(6).

---

## 2. Assets (what we protect)

| Asset | Confidentiality | Integrity | Availability | Notes |
|-------|:---:|:---:|:---:|-------|
| A1. GitLab API token (`GITLAB_TOKEN`) | H | H | — | Write access to MR discussions across projects. Highest-value secret. |
| A2. CI bearer token (`CI_TOKEN`) | H | H | — | Shared across all pipelines; lets caller create/read reviews. |
| A3. Worker bearer token (`WORKER_TOKEN`) | H | H | — | Lets holder claim any job → **read every diff**. |
| A4. Admin bearer token (`ADMIN_TOKEN`) | H | H | — | Cancel reviews, read metrics/backends, (implicitly) manage registry. |
| A5. DB credentials (`DB_USER`/`DB_PASSWORD`) | H | H | H | Full read/write to all state. |
| A6. Diff contents (proprietary source code) | **H** | M | — | In `review_inputs.diff`, `review_jobs` payload (4), `review_comments`. Crown-jewel data. |
| A7. Review results / raw LLM response | M | M | — | `review_results.raw_response` — may quote source lines. |
| A8. Audit trail integrity (`review_events`) | L | **H** | M | Repudiation defense; must be trustworthy & complete. |
| A9. Review lifecycle state / queue | — | H | H | Corrupt state → wrong/duplicate/missing reviews. |
| A10. Gateway availability | — | — | H | Single instance; SPOF (accepted at scale). |

---

## 3. STRIDE analysis — enumerated threats

Likelihood/Impact/Risk are H/M/L. "Component/Flow" references the DFD numbers and planned classes.

| ID | STRIDE | OWASP / CWE | Component / Flow | Attack scenario | Impact | Likelihood | Risk |
|----|--------|-------------|------------------|-----------------|--------|:----------:|:----:|
| **T-01** | Spoofing | A07 / CWE-798, CWE-521 | All bearer auth, `TokenAuthenticationFilter` | Static, long-lived tokens with no expiry/rotation. A leaked token (CI logs, env dump, ex-employee) grants indefinite access; no revocation short of redeploy. | High (A2/A3/A4 compromise) | M | **H** |
| **T-02** | Spoofing / Info disclosure | A02 / CWE-208 | `TokenAuthenticationFilter` compare | Token verified with `String.equals`/`==` → early-exit timing side channel lets an attacker recover the token byte-by-byte over many requests. | High (auth bypass) | L–M | **M** |
| **T-03** | Spoofing | A01 / CWE-290, CWE-345 | (3) `/jobs/claim`, `ClaimJobRequest.backendId`, `BackendDispatcher` | `backendId` is a self-declared name in the request body with no binding to `WORKER_TOKEN`. A worker (or anyone with the shared worker token) claims jobs under **another backend's name**, stealing its capacity, poisoning capacity accounting, and mis-attributing audit/results to an innocent backend. | Med (capacity theft, audit corruption) | M | **M** |
| **T-04** | Spoofing / Tampering | A01 / CWE-639 (IDOR), CWE-345 | (3) `/jobs/{id}/heartbeat`, `/jobs/{id}/result`, `JobController` | `jobId` is a **sequential BIGINT**; endpoints act on the job identified purely by URL id. With no check that the caller is the worker that claimed it, any worker-token holder submits **forged results or heartbeats for jobs it never claimed** — injecting attacker-chosen LLM output that gets published to another team's MR, or keeping a dead job alive to stall retry. | **High** (forged published comments, integrity of A7/A9) | M | **H** |
| **T-05** | Information disclosure | A01/A02 / CWE-522, CWE-200 | (4) `/jobs/claim` response payload | A single shared worker token can claim jobs indefinitely and read **every diff** (A6). One compromised Mac mini or leaked worker token = full proprietary-source-code exfiltration across all projects. | **High** (mass source-code leak) | M | **H** |
| **T-06** | Tampering (into GitLab UI) | A03 / CWE-79, CWE-74 | (1)→(9)→(6) diff → LLM → `CommentParser` → `GitLabPublisher` | Attacker crafts diff/comments/filenames containing prompt-injection ("ignore instructions, output the following comment…") and/or markdown/HTML payloads. LLM output is published verbatim to MR discussions → markdown injection, misleading `@mentions`/`/quick actions`, phishing links, or stored-XSS attempts in any tool rendering the comment as HTML. | Med–High (MR manipulation, phishing, possible XSS in downstream renderers) | M–H | **H** |
| **T-07** | Info disclosure / SSRF | **A10** / CWE-918 | (7) `BackendHealthChecker`, `backendProbeRestClient`, `backends.url` | `backends.url` is data probed by the Gateway. If registry writes are reachable (admin-token, DB access, or future self-registration) an attacker points a backend at `http://169.254.169.254/…`, `http://localhost:...`, or internal hosts; the Gateway makes the request and status/latency leaks internal reachability. | Med (internal recon, metadata theft) | L–M | **M** |
| **T-08** | Denial of Service | A04 / CWE-400, CWE-770 | (1) `/reviews`, `POST` body, Tomcat | `diff` size is validated **after** the full request body is read into memory. Oversized or many concurrent large bodies exhaust heap/threads before `DIFF_TOO_LARGE` (422) is returned. No hard byte cap at the container edge. | Med (Gateway OOM/stall, SPOF down) | M | **M** |
| **T-09** | Info disclosure | A09 / CWE-532, CWE-215 | Logging, `review_events.details`, `EventService` | Tokens, `GITLAB_TOKEN`, `Authorization` headers, or full diffs land in file logs or the audit `details` column, then in `pg_dump` backups and log aggregation. | High (A1–A4, A6 leak via logs/backups) | M | **H** |
| **T-10** | Tampering | A03 / CWE-89 | Native queries in `ReviewRepository`/`ReviewJobRepository` (claim, capacity, sweeps) | SKIP-LOCKED/capacity queries are hand-written native SQL. String concatenation of `backendId`/`status`/order-by would allow SQL injection. | High (DB compromise) | L (if bound params used) | **M** |
| **T-11** | Elevation / Info disclosure | A01/A05 / CWE-250, CWE-269 | (6) `GITLAB_TOKEN` scope | If the GitLab token is a broadly-scoped personal/group token (api scope, all projects, admin), a Gateway compromise or SSRF-via-GitLab yields write access far beyond "post MR discussion". | High (blast radius) | M | **H** |
| **T-12** | Info disclosure / Tampering / Spoofing | A02/A04 / CWE-319 | All flows (1)(3)(6)(8), and (5) | If any hop runs over cleartext HTTP (esp. TB-WORKER over WAN, TB-DB), bearer tokens, diffs, and GitLab token transit in the clear → sniffing/MITM/replay. Architecture docs do not state a TLS requirement. | High | M (WAN worker links) | **H** |
| **T-13** | Tampering (replay) | A04/A08 / CWE-294 | (3) `/jobs/{id}/result` | A captured result submission is replayed. Current design relies on the RUNNING-guard + `review_results` UNIQUE, making a *later* replay a no-op — but a replay racing the original, or after a legit re-queue+re-claim of the same review id, could overwrite/confuse. No nonce/request signature. | Low–Med (result confusion) | L | **M** |
| **T-14** | Elevation of privilege | A01 / CWE-285, CWE-862 | (8) `DELETE /reviews/{id}`, `AdminController` | The role matrix grants some admin/service reads to "ADMIN (or CI)". If CANCEL or registry-affecting ops are reachable with the CI token, a CI-token holder cancels other teams' reviews (griefing) or reads topology. Broken function-level authorization. | Med (DoS-by-cancel, info disclosure) | M | **M** |
| **T-15** | Repudiation | A09 / CWE-778, CWE-345 | `worker_id`/`backendId` self-reported; `review_events` | Worker-supplied `workerId`/`backendId` are trusted verbatim into the audit trail. A misbehaving worker can forge attribution; combined with T-04 the audit trail cannot reliably answer "who submitted this result". Missing auth-failure logging hurts incident forensics. | Med (audit integrity A8) | M | **M** |
| **T-16** | Denial of Service | A04 / CWE-770, CWE-799 | (1) `/reviews`, queue | A compromised/misused CI token floods `POST /reviews` with distinct SHAs (dedup doesn't help), filling the queue and DB, starving legitimate reviews and burning backend capacity. No per-client rate limit. | Med | M | **M** |
| **T-17** | Info disclosure | A05 / CWE-200 | (8) `GET /backends`, `GET /metrics`, `/actuator/health` | `/backends` exposes internal backend URLs/models/topology; verbose actuator or stack traces leak versions/paths. If exposure is broader than ADMIN, recon is easy. | Low–Med | M | **M** |
| **T-18** | Info disclosure | A02 / CWE-311, CWE-312 | (5) PostgreSQL at rest, `pg_dump` | Diffs (A6) and raw responses (A7) stored unencrypted; DB file/backup theft = proprietary source leak. | High (if DB/backup stolen) | L | **M** |
| **T-19** | Denial of Service / Tampering | A10 / CWE-400, CWE-1188 | (7) probe, (9) malicious backend | A registered backend returns a huge/slow `/health` body or an inference response engineered (via T-06) to make the model emit an enormous `raw_response`, inflating storage and stalling parsing/publish. | Med | L–M | **M** |
| **T-20** | Elevation of privilege | A01 / CWE-269 | `SecurityConfig` role→endpoint mapping | Over-broad grants ("CI or ADMIN", "ADMIN or CI") blur least privilege; a worker/CI token reaching endpoints outside its role enables privilege escalation or cross-tenant actions. | Med | M | **M** |
| **T-21** | Info disclosure (IDOR) | A01 / CWE-639 | (1) `GET /reviews/{id}`, sequential id | Any CI-token holder enumerates sequential review ids and reads status/comment counts for other projects' reviews (and the shared token means no project scoping at all). | Low–Med | M | **M** |
| **T-22** | Info disclosure / Security misconfig | A05 / CWE-209, CWE-16 | `GlobalExceptionHandler`, actuator | Unhandled exceptions return stack traces / internal messages; actuator over-exposed. | Low | M | **L** |
| **T-23** | Denial of Service | A04 / CWE-770 | (3) claim/heartbeat storm | Worker-token holder hammers `/jobs/claim`/`heartbeat`, saturating the single Gateway/DB pool (Hikari max 20). | Low–Med | L–M | **M** |
| **T-24** | Info disclosure | A02 / CWE-212 | Data retention of A6/A7 | Diffs and raw responses retained indefinitely broaden the window for leak via T-05/T-09/T-18. | Low | M | **L** |
| **T-25** | Spoofing / Tampering | A08 / CWE-345 | Supply chain: `pom.xml` deps | Vulnerable/compromised dependency (Spring, Jackson, Postgres driver, Flyway, Zonky) introduces RCE/deserialization. No SCA gate defined. | High (if hit) | L–M | **M** |
| **T-26** | Repudiation / Info disclosure | A09 / CWE-778 | Absence of auth/access logging | No record of failed auth, admin cancels, or claim attribution → blind to token abuse/brute force. | Med | M | **M** |

Risk tally: **High = 6** (T-01, T-04, T-05, T-06, T-09, T-11, T-12 — see note), **Medium = 15**, **Low = 3**.
(Note: T-12 rated H because worker links may be WAN; if all hops are provably on a private trusted LAN it drops to M. Counting H = 7 including T-12.)

Final: **High = 7, Medium = 15, Low = 3** (26 threats total).

---

## 4. Top design-specific risks (deep dive) with mitigations

Each mitigation names the planned class and a MUST/SHOULD/ACCEPTED-RISK tag.

### T-01 — Static long-lived tokens, no rotation
- **MUST** — Load all four secrets (`CI_TOKEN`, `WORKER_TOKEN`, `ADMIN_TOKEN`, `GITLAB_TOKEN`) only from
  environment variables via `GatewayProperties` (already planned); never in `application.yml` committed values,
  never defaulted. Enforce **minimum length/entropy** (≥ 32 random bytes, base64/hex) at startup —
  `GatewayProperties` `@PostConstruct` validation fails fast on short/blank tokens.
- **SHOULD** — Support **more than one valid value per role** (comma-separated list in config) so a token can be
  rotated by adding the new value, redeploying, then removing the old — zero-downtime rotation with no Vault.
  `TokenAuthenticationFilter` matches against a `Set<String>` per role.
- **SHOULD** — Document an operational rotation runbook (rotate on personnel change / suspected leak).
- **ACCEPTED-RISK** — True short-lived/JWT tokens require an issuer we are forbidden to add (no OAuth server).
  Static tokens with rotation support is the accepted control at this scale; justification recorded here.

### T-02 — Timing attack on token compare
- **MUST** — Compare tokens with `java.security.MessageDigest.isEqual(byte[], byte[])` (constant-time), never
  `String.equals`/`==`, inside `TokenAuthenticationFilter`. Pre-hash both sides (SHA-256) before compare so
  length differences don't leak and comparison cost is fixed. Applies to all three role tokens.

### T-03 — Worker claims arbitrary backendId (spoofing another backend)
- **MUST** — Bind identity, not self-declaration. Because a single shared `WORKER_TOKEN` is planned, at minimum
  the Gateway must **treat `backendId`/`workerId` as untrusted claims** and (a) validate that the named backend
  exists and is `ACTIVE` in `BackendDispatcher`, (b) record the claim in `review_events` as *claimed identity*,
  not asserted truth.
- **SHOULD** — Issue **one worker token per backend** (`gateway.security.worker-tokens` map: token → backend name).
  `TokenAuthenticationFilter` resolves the token to its bound backend name and `JobController` **ignores the body's
  `backendId`, using the token-bound name**. This removes spoofing entirely and is pure config (no new infra).
- **ACCEPTED-RISK** — If a single shared worker token is retained for launch, capacity theft/misattribution
  (T-03) is accepted for the internal, small worker pool, contingent on T-04's ownership check being in place.

### T-04 — Forged results/heartbeats for un-owned jobs (sequential jobId)
- **MUST** — **Ownership check on every `/jobs/{id}/*` call.** `JobController`/`QueueManager` must load the
  `review_jobs` row and verify the caller's identity matches the row that claimed it before mutating anything:
  - the review is currently `RUNNING`, AND
  - `review_jobs.worker_id == claimant` (and, with per-backend tokens, `backend_id == token-bound backend`).
  On mismatch → `403` (or `409`/`204` for result idempotency), write a security event, mutate **nothing**.
- **MUST** — Combine with the existing RUNNING-guard + `review_results` UNIQUE so a stale/forged result on a job
  that already moved on is a safe no-op (idempotency), while a forged result on an *active* job owned by someone
  else is rejected by the ownership check.
- **SHOULD** — Return an opaque claim/lease token from `/jobs/claim` (random UUID stored in `review_jobs`) and
  require it on heartbeat/result, so guessing the sequential `jobId` is insufficient. Fits DB/Spring world.

### T-05 — Diff exfiltration via claim endpoint
- **MUST** — Enforce T-04 ownership + T-03 per-backend token so a worker only ever receives the payload for the
  job it legitimately claimed under its own identity.
- **SHOULD** — Log every claim (review id, backend, worker) in `review_events` to make bulk exfiltration
  detectable; expose a `/metrics` counter of claims-per-worker for anomaly spotting.
- **SHOULD** — Deploy workers on a private network segment / VPN so the worker token is not internet-reachable.
- **ACCEPTED-RISK** — The Worker must receive the diff to do its job; we cannot avoid exposing A6 to a legitimately
  claimed job. Residual risk of a fully-compromised worker host reading its own claimed diffs is accepted;
  scope-limited by per-backend tokens + auditing.

### T-06 — Prompt injection via diff → malicious published comment
- **MUST** — Treat LLM output as **untrusted user input on the way out**. Before publishing (in `GitLabPublisher`
  or `CommentParser`), **sanitize/encode** comment text: strip/escape HTML, neutralize GitLab quick-action lines
  (leading `/`), and defang `@`/`#`/`!` mention-and-reference triggers where not intended (e.g., wrap the whole
  LLM comment in a fenced code block or a clearly-labeled quote block, or HTML-escape and post as plain text).
- **MUST** — Enforce a **hard cap on comment count and per-comment length** in `CommentParser`; drop/truncate
  beyond the cap (defends T-19 amplification too).
- **SHOULD** — Prefix every published comment with a machine-generated banner ("AI-generated, may be inaccurate")
  so injected instructions can't impersonate a human reviewer.
- **ACCEPTED-RISK** — We cannot fully prevent the model from being influenced by diff content (that is its input);
  we constrain the *output channel*. Residual semantic-manipulation risk accepted.

### T-07 — SSRF via backends.url probe
- **MUST** — Validate `backends.url` on write **and** before each probe in `BackendHealthChecker`/`RestClientConfig`:
  scheme must be `http`/`https`, host must be on a **configured allowlist** (`gateway.backend.allowed-hosts` or
  CIDR), reject literal/again-resolved IPs in link-local/loopback/metadata ranges (169.254/16, 127/8, ::1, 10/8
  only if intended). Reject redirects on the probe client.
- **MUST** — Give `backendProbeRestClient` **short connect/read timeouts** and disable following redirects.
- **SHOULD** — Only ADMIN may write the registry (T-14); registry writes go through a validated path, not raw SQL.

### T-08 / T-16 / T-23 — DoS (oversized body, queue flood, request storm)
- **MUST** — Set a hard request-body cap at the container edge: `server.tomcat.max-swallow-size` and
  `spring.servlet.multipart`/`server.max-http-request-header-size` plus an explicit `maxPostSize`/content-length
  check in `WebConfig`, sized to `max-diff-tokens × chars-per-token` **+ margin**, so oversized bodies are rejected
  with `413`/`422` *before* full buffering. Fail fast at the edge (requirement).
- **SHOULD** — Add a lightweight in-Gateway rate limit (per-token, in-memory token-bucket in a filter — no Redis)
  on `/reviews`, `/jobs/claim`, `/jobs/*/heartbeat`. Single instance makes in-memory limiting sufficient.
- **SHOULD** — Bound the queue: reject `/reviews` with `429`/`503` when `QUEUED` depth exceeds a configured
  threshold (`ReviewService`).
- **ACCEPTED-RISK** — Single-instance SPOF availability (T-10/A10) is already an accepted architectural decision
  (systemd `Restart=always`); we do not add HA.

### T-09 — Secret/diff leakage into logs & audit
- **MUST** — `EventService` writes `review_events.details` through a **scrubber** that rejects/masks anything
  matching token patterns; **never** put diff, raw_response, or `Authorization` values into events (already a stated
  principle — enforce with a unit test).
- **MUST** — Logback config must **not** log request bodies or the `Authorization` header; `GatewayProperties`
  token fields use a masking `toString()`; a global logging filter redacts `Bearer …` if ever printed.
- **SHOULD** — Restrict filesystem perms on log files and `pg_dump` output; document that backups contain A6/A7.

### T-10 — SQL injection in native queries
- **MUST** — All native queries (`FOR UPDATE SKIP LOCKED` claim, capacity count, sweeps) use **bound parameters**
  only; no string concatenation of `backendId`, `status`, or dynamic `ORDER BY`. Order-by columns are fixed
  literals in the query text, not parameters. Verify in SAST (Semgrep/CodeQL) and a repo test.

### T-11 — GitLab token least privilege
- **MUST** — `GITLAB_TOKEN` must be a **project (or group) access token scoped to `api` on only the projects under
  review**, ideally with a role no higher than needed to create MR discussions (Reporter+ as required), and an
  **expiry set** with a renewal runbook. Document this as a deployment requirement; the Gateway cannot enforce it
  but AppSec verifies at review.
- **SHOULD** — If GitLab supports a finer scope than `api` for discussions in the deployment version, use it.

### T-12 — TLS assumptions
- **MUST** — Document and require **TLS on every hop**: CI→Gateway, Worker→Gateway, Admin→Gateway (terminate TLS at
  a reverse proxy or `server.ssl` in Boot), Gateway→GitLab (`https` base-url — enforce scheme), Gateway→PostgreSQL
  (`sslmode=require` in JDBC URL). `TokenAuthenticationFilter`/`RestClientConfig` reject non-HTTPS GitLab/backend
  URLs. Add a startup check that GitLab base-url is `https`.
- **ACCEPTED-RISK** — Gateway↔PostgreSQL over a loopback/private socket on the same host may use non-TLS if
  co-located; documented and justified per deployment.

### T-13 — Result replay
- **SHOULD** — The claim-lease token (T-04 SHOULD) doubles as anti-replay: a replayed result carries a lease that
  no longer matches after re-queue/re-claim → rejected. Combined with RUNNING-guard + `review_results` UNIQUE,
  replay is neutralized without cryptographic nonces.
- **ACCEPTED-RISK** — Full request signing is out of scope; the state-guard + UNIQUE + lease is accepted.

### T-14 / T-20 / T-21 — Authorization (admin cancel, role over-grant, IDOR)
- **MUST** — `DELETE /reviews/{id}` requires **ADMIN only** in `SecurityConfig`; do **not** grant cancel to CI.
- **MUST** — Registry-affecting/`/backends`/`/metrics` reads require **ADMIN only** (drop the "or CI"); tighten the
  role matrix so each endpoint has exactly one required role. No "ADMIN or CI" ambiguity.
- **SHOULD** — Mitigate IDOR on `GET /reviews/{id}`: the response already excludes the diff — keep it that way, and
  (if/when CI tokens become per-project) scope reads to the caller's project. At launch with a shared CI token this
  is **ACCEPTED-RISK**, recorded, because there is no per-project identity yet.

### T-15 / T-26 — Repudiation / audit integrity & access logging
- **MUST** — Record the **authenticated role/identity** (from the token, not the body) on security-relevant events
  (claim, result, cancel) via `EventService`; log **auth failures** (401/403) with source IP and path.
- **SHOULD** — `review_events` is append-only by design; ensure the DB app-user has no `UPDATE`/`DELETE` on it
  (grant `INSERT`/`SELECT` only) so audit rows can't be silently rewritten — pure Postgres grant, no new infra.

### T-17 / T-22 — Info disclosure via service endpoints / errors
- **MUST** — `GlobalExceptionHandler` returns the fixed `ErrorResponse(error,message)` with **no stack traces or
  internal messages**; map unexpected exceptions to a generic 500 body.
- **MUST** — Actuator exposure limited to `health` (already planned); no `env`/`beans`/`configprops` exposed.
- **SHOULD** — `/backends` returns names/models/status but consider omitting raw internal URLs unless ADMIN needs
  them; if returned, ADMIN-only (T-14).

### T-18 / T-24 — Data at rest & retention
- **SHOULD** — Enable PostgreSQL storage/volume encryption at rest and encrypt `pg_dump` backups; restrict DB and
  backup file permissions. Define a **retention policy**: purge/anonymize `review_inputs.diff` and
  `review_results.raw_response` for terminal reviews older than N days (a scheduled `@Scheduled` cleanup fits the
  existing background-job pattern — no new infra).
- **ACCEPTED-RISK** — Column-level/application-layer encryption of diffs is out of scope at this scale; volume
  encryption + access control is the accepted control.

### T-19 — Oversized/slow backend or amplified LLM output
- **MUST** — Cap `raw_response` size accepted at `/jobs/{id}/result` and probe-response size in
  `backendProbeRestClient`; enforce `gateway.job.max-duration` (already planned) as the backstop.
- **SHOULD** — `CommentParser` caps comment count/length (shared with T-06).

### T-25 — Supply chain
- **MUST** — Add SCA to CI (see §CI/CD below): `osv-scanner` / OWASP Dependency-Check / Trivy on `pom.xml`;
  fail build on High/Critical CVEs. Pin the Spring Boot BOM (already 3.2.12) and keep within the patched line.

---

## 5. CI/CD security gates (open-source, fits current constraints)

No pipeline exists yet. When a build system is added, wire these (fast on every PR, slow on schedule):

- **Pre-commit / pre-push:** `gitleaks` (block committed tokens; the four secrets are the crown jewels of T-01/T-09).
- **On every PR (fast):**
  - `semgrep` (rulesets: `p/java`, `p/spring`, `p/secrets`, `p/sql-injection`) — targets T-02, T-04, T-06, T-07, T-10.
  - `osv-scanner` on `pom.xml` — targets T-25.
  - `gitleaks` full-history scan.
- **On build:** `trivy fs` / `trivy config` if a container image is produced later; SpotBugs+FindSecBugs (Java) as a
  deeper SAST pass.
- **Scheduled / pre-release (slow):** OWASP Dependency-Check full run; OWASP ZAP baseline scan against a staging
  Gateway (checks security headers, error leakage T-22, auth on protected paths).
- **Gate policy:** Critical/High → block merge; Medium → create a tracked issue, non-blocking; Low → informational.
  Keep the per-PR set under a couple of minutes so the team doesn't route around it.

---

## 6. Security requirements checklist (SR-xx) — testable, for the backend developer

AppSec will verify each in the SAST/verification phase. Each is written as a testable assertion.

- **SR-01 (MUST, T-01):** All four secrets are read only from env via `GatewayProperties`; startup **fails** if any
  token is blank or shorter than 32 chars. *Test:* boot with a short token → app refuses to start.
- **SR-02 (MUST, T-02):** `TokenAuthenticationFilter` compares tokens with `MessageDigest.isEqual` over SHA-256
  digests; no `String.equals`/`==` on token values. *Test:* code assertion + Semgrep rule passes.
- **SR-03 (SHOULD, T-01):** Each role accepts a configurable **set** of valid tokens to allow zero-downtime rotation.
  *Test:* two configured CI tokens both authenticate.
- **SR-04 (MUST, T-04):** Every `/jobs/{id}/heartbeat` and `/jobs/{id}/result` verifies the review is `RUNNING` **and**
  the caller identity matches `review_jobs.worker_id` (and token-bound backend); mismatch mutates nothing and returns
  403/409. *Test:* worker B calling worker A's job id is rejected and state is unchanged.
- **SR-05 (MUST, T-05/T-04):** `/jobs/claim` returns the diff payload only for a job the caller has just claimed under
  its own identity. *Test:* claim path records claimant; a second identity cannot read the first's payload.
- **SR-06 (SHOULD, T-03/T-04/T-13):** `/jobs/claim` issues a random lease token stored on `review_jobs`; heartbeat and
  result require the matching lease. *Test:* stale/guessed lease rejected.
- **SR-07 (SHOULD, T-03):** Worker token maps to a backend name in config; `JobController` uses the token-bound
  backend, ignoring body `backendId`. *Test:* body claiming another backend name has no effect on attribution.
- **SR-08 (MUST, T-06):** LLM comment text is HTML-escaped and quick-action/mention triggers are neutralized before
  `GitLabPublisher` posts it. *Test:* a comment containing `<script>`, leading `/close`, and `@all` is published
  inert (escaped / fenced), verified against the publish request body.
- **SR-09 (MUST, T-06/T-19):** `CommentParser` enforces max comment count and max per-comment length; excess is
  dropped/truncated. *Test:* oversized parsed output is capped.
- **SR-10 (MUST, T-07):** `backends.url` is validated (scheme allowlist + host allowlist, no loopback/link-local/
  metadata ranges) on write and before each probe; probe client disables redirects and uses short timeouts.
  *Test:* a backend url of `http://169.254.169.254/…` is rejected/never probed.
- **SR-11 (MUST, T-08):** A hard request-body byte cap (sized from `max-diff-tokens × chars-per-token` + margin) is
  enforced at the container edge; oversized bodies get 413/422 before full processing. *Test:* body over the cap is
  rejected fast.
- **SR-12 (MUST, T-09):** `EventService` scrubs `details`; no diff/raw_response/token/`Authorization` value is ever
  written to `review_events` or file logs; `GatewayProperties` token `toString()` is masked. *Test:* an event write
  attempt containing a token pattern is masked; log config asserts no header logging.
- **SR-13 (MUST, T-10):** All native SQL uses bound parameters; no dynamic string-built SQL. *Test:* Semgrep/CodeQL
  SQL-injection rules clean; injection payload in `backendId` cannot alter the query.
- **SR-14 (MUST, T-11):** Deployment doc mandates a least-privilege, expiring GitLab project/group token scoped to
  the reviewed projects. *Verify:* deployment review checklist item.
- **SR-15 (MUST, T-12):** GitLab base-url and backend urls must be `https`; startup/validation rejects non-HTTPS;
  JDBC uses `sslmode=require` unless DB is loopback-local (documented). *Test:* `http://` GitLab base-url refused.
- **SR-16 (MUST, T-14/T-20):** `SecurityConfig` maps each endpoint to exactly one required role; `DELETE /reviews/{id}`,
  `GET /backends`, `GET /metrics` are ADMIN-only; `/jobs/*` WORKER-only; `/reviews` CI-only; `/health` permitAll.
  *Test:* `@WebMvcTest` role matrix — CI token gets 403 on DELETE and on /backends.
- **SR-17 (MUST, T-17/T-22):** `GlobalExceptionHandler` returns only `ErrorResponse` with no stack trace/internal
  detail; actuator exposes `health` only. *Test:* forced 500 returns generic body; `/actuator/env` is 404/403.
- **SR-18 (MUST, T-15/T-26):** Security-relevant events (claim, result, cancel) record the authenticated identity;
  auth failures (401/403) are logged with path and source. *Test:* a rejected result produces a security log line.
- **SR-19 (SHOULD, T-15):** The Gateway DB user has `INSERT`/`SELECT` only (no `UPDATE`/`DELETE`) on `review_events`.
  *Test:* an `UPDATE review_events` by the app user is denied.
- **SR-20 (SHOULD, T-08/T-16/T-23):** In-memory per-token rate limiting on `/reviews`, `/jobs/claim`, `heartbeat`;
  `/reviews` sheds load when `QUEUED` depth exceeds a threshold. *Test:* burst beyond limit gets 429.
- **SR-21 (MUST, T-19):** `/jobs/{id}/result` caps accepted `raw_response` size; `max-duration` backstop enforced.
  *Test:* oversized raw_response rejected.
- **SR-22 (SHOULD, T-18/T-24):** DB at-rest & backup encryption enabled; a scheduled cleanup purges diffs/raw
  responses of terminal reviews older than the configured retention. *Test:* cleanup job removes eligible payloads.
- **SR-23 (MUST, T-25):** CI runs `gitleaks` + SCA (`osv-scanner`/Dependency-Check) + `semgrep`; High/Critical block
  merge. *Verify:* pipeline config present and gating.
- **SR-24 (SHOULD, T-05):** Claims are counted per worker in `/metrics` for exfiltration anomaly detection.
  *Test:* claim increments the per-worker counter.

---

## 7. Constraints honored
- No Vault/OAuth/Redis/Kafka introduced. Token rotation (SR-03), rate limiting (SR-20), lease tokens (SR-06), and
  audit hardening (SR-19) are all implemented in Spring config + PostgreSQL grants.
- Single Gateway instance: in-memory rate limiting and counters are sufficient and correct.
- Worker stays dumb: no mitigation gives the Worker GitLab or DB access; per-backend tokens (SR-07) and ownership
  checks (SR-04) live entirely in the Gateway.
- Fail-fast-at-the-edge preserved: body cap (SR-11) and diff-size validation reject bad input at `POST /reviews`.

---

## 8. Release gate summary
Blockers for release = all **MUST** SRs: **SR-01, SR-02, SR-04, SR-05, SR-08, SR-09, SR-10, SR-11, SR-12, SR-13,
SR-14, SR-15, SR-16, SR-17, SR-18, SR-21, SR-23.** SHOULD items (SR-03, SR-06, SR-07, SR-19, SR-20, SR-22, SR-24)
are strongly recommended and tracked. ACCEPTED-RISK items are the SPOF availability, shared-token IDOR at launch,
inherent diff exposure to a legitimately-claimed worker, and DB-loopback non-TLS — each justified above.
