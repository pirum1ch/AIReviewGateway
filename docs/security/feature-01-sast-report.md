# AppSec SAST Report — feature/01-data-model

Scope: Maven skeleton, Flyway `V1__initial_schema.sql`, JPA entities, Spring Data repositories, test
infra. Services / controllers / security config do **not** exist yet (features 02–03) and their
absence is out of scope per the review brief. Method: manual expert SAST + offline dependency-tree
CVE analysis (no network-facing app → no DAST this round). No SAST scanner was installed on the host;
manual review was the primary and sufficient method for this small, mechanical layer.

Verdict: **PASS-with-notes.** The code itself is clean — SR-13 (bound params in native SQL) is fully
satisfied, there is no injection / unsafe-deserialization / info-leakage surface, entity immutability
is correct, and no secrets are committed. The one substantive item is supply-chain (EOL Spring Boot
3.2.x line), which is not reachable at this data-only layer but should be resolved before the HTTP/
security layer (feature 03) lands and before any deployment.

Severity counts: Critical 0 · High 1 · Medium 0 · Low 2 · Info 3.
MUST-FIX (hard-blocks this merge): **none.** F01-01 is MUST-FIX-before-feature-03/release.

---

## Findings

| # | Severity | CWE / OWASP | Where | Description | Remediation | SR |
|---|----------|-------------|-------|-------------|-------------|----|
| F01-01 | High | CWE-1104 / CWE-1035, A06:2021 | `pom.xml:10` (Spring Boot `3.2.12`) | Spring Boot **3.2.x is EOL for free/OSS support** (final community release was 3.2.12, Nov 2024; the line receives no further free security patches). Resolved transitive versions carry **already-published, unpatched CVEs**: `tomcat-embed-core:10.1.33`, `spring-core/web/webmvc:6.1.15`, `spring-security-*:6.2.8`. See dependency detail below. | Upgrade to a supported line — **Boot 3.4.x (min) or current 3.5.x** — while the module is still only entities+repos (near-zero migration cost now; much higher once feature-03 wires web/security/actuator). Add SCA to CI (SR-23) to enforce going forward. | SR-23, T-25 |
| F01-02 | Low | CWE-611 / CWE-917, A05:2021 | transitive `ch.qos.logback:logback-core:1.4.14` (via Boot 3.2.12) | Logback 1.4.14 is affected by CVE-2024-12798 / CVE-2024-12801 (JaninoEventEvaluator expression injection + SaxEventRecorder SSRF via crafted logback config). Exploitation requires attacker control of the logback config file, which is trusted here → low real risk. The 1.4.x line is also superseded by 1.5.x. | Cleared automatically by the F01-01 upgrade (Boot 3.4.x ships logback 1.5.x). No standalone action needed. | T-25 |
| F01-03 | Low | CWE-778 / CWE-283, A09:2021 | `V1__initial_schema.sql:136-148` (`review_events`) | The audit trail is protected only by convention. Two gaps, both cheapest to settle in V1: (a) `review_events` has `ON DELETE CASCADE` from `reviews`, so deleting a review destroys its forensic history (A8 integrity); (b) there is no DB-level append-only enforcement — the app DB user can `UPDATE`/`DELETE` audit rows (SR-19). Not exploitable at this layer, but a schema/grant decision that is harder to change after data exists. | Confirm the planned retention job (SR-22) only NULLs payload columns and never `DELETE`s `reviews`; grant the Gateway DB user `INSERT`/`SELECT` only on `review_events` (SR-19); consider `ON DELETE RESTRICT` (or a separate archive) for the audit table if reviews may ever be deleted. Deployment/grant item — track for feature-03/ops, not a schema blocker. | SR-18, SR-19, T-15, T-26 |
| F01-04 | Info | CWE-89 (defense-in-depth) | `ReviewRepositoryConcurrentClaimTest.java:122-124`; `SchemaConstraintsTest.java:147-148` | Test code builds SQL by string concatenation (`DELETE ... IN (` + joined `Long`s; a table name interpolated into `SELECT count(*) FROM " + table`). Inputs are trusted literals/`long`s, so there is **no** injectable path — but the pattern must never be copied into production code. | Optional: use `IN (:ids)` binding / a fixed table name. Add a Semgrep `p/sql-injection` rule in CI so the pattern can't migrate into `src/main`. | SR-13, SR-23 |
| F01-05 | Info | CWE-770 / CWE-400, A04:2021 | `V1__initial_schema.sql:45` (`review_inputs.diff`), `:100` (`review_results.raw_response`) | `diff` and `raw_response` are unbounded `TEXT`. This is correct at the DB layer (Postgres `TEXT` is fine); the real controls are the edge body-cap (SR-11 at `POST /reviews`) and the result-size cap (SR-21 at `/jobs/{id}/result`), which live in features 02–03. Recorded so those caps are not forgotten when the write paths are built. | No schema change. Verify SR-11 / SR-21 caps are enforced when the controllers/services land. | SR-11, SR-21, T-08, T-19 |
| F01-06 | Info | CWE-918, A10:2021 | `V1__initial_schema.sql:59` (`backends.url`) | `backends.url` is a plain `VARCHAR(256)` with no scheme/host constraint. It is an SSRF sink (Gateway probes it), but validation belongs at the write path (SR-10 allowlist + loopback/link-local/metadata rejection), which does not exist yet. Schema cannot reasonably encode the allowlist. | No schema change. Enforce SR-10 validation on the backend-registry write path and probe client in feature-03. | SR-10, T-07 |

---

## Positive verifications (what was checked and passed)

- **SR-13 — bound parameters in all native SQL: PASS.** Every production native query uses named
  bound params only, with `status`/`ORDER BY` as fixed in-query literals (never concatenated):
  `ReviewRepository.findNextQueuedReviewIdForUpdate` (`FOR UPDATE SKIP LOCKED`), and
  `ReviewJobRepository.countRunningJobsForBackend` / `findReviewIdsWithStaleHeartbeat` /
  `findReviewIdsExceedingMaxDuration` (all `:backendId` / `:cutoff`). The one JPQL `@Modifying`
  update (`markObsoleteForOtherHeadShas`) uses a fully-qualified enum literal in `SET` and bound
  params elsewhere. No string-built SQL anywhere in `src/main`. A `backendId` injection payload
  cannot alter query structure.
- **Information leakage via `toString`: PASS.** No entity overrides `toString()`; all inherit
  `Object` (`class@hash`). Diff, `raw_response`, tokens, and worker/backend ids are never rendered
  by default logging. (Guard for later: do not add Lombok `@Data`/`@ToString` or a hand-written
  `toString` that includes `diff`/`rawResponse` — SR-12.)
- **Mass-assignment / immutability: PASS.** Dedup-key and payload columns (`projectId`,
  `mergeRequestId`, `headSha`, `baseSha`, `promptVersion`, `diff`, `rawResponse`, parsed-comment
  fields) have **no setters** and are `updatable=false`. The mutable setters on `Review`
  (`setStatus`/`setPriority`/`setAttempts`) are intentional low-level mutators for the future
  `StateMachine`; entities are never bound to HTTP request bodies (DTOs are separate `record`s), so
  there is no mass-assignment path.
- **Unsafe deserialization: PASS.** No entity implements `Serializable`, no `readObject`, no Jackson
  polymorphic/default typing, no YAML/XML object graphs. Nothing deserializes untrusted input.
- **Hardcoded secrets: PASS.** No tokens/passwords in `src/main`, `src/test`, `application.yml`
  (main or test), or any committed file. `.gitignore` covers `target/`, `*.log`, IDE dirs. The four
  crown-jewel secrets are correctly deferred to env-var config in feature-03 (SR-01).
- **Schema integrity constraints: PASS.** `CHECK` constraints on `status`/`severity`/`event_type`
  and the partial-unique dedup index correctly enforce state validity at the DB (exercised by
  `SchemaConstraintsTest` / `DedupStatusMatrixTest`). NOT NULL is present on all security-relevant
  columns that must always be populated at this layer.

## Dependency analysis detail (offline `mvn dependency:tree`, resolved versions)

| Artifact | Resolved | Status as of 2026-07 |
|----------|----------|----------------------|
| `spring-boot-starter-parent` | 3.2.12 | **EOL OSS line** — F01-01. |
| `tomcat-embed-core` | 10.1.33 | Unpatched: CVE-2025-24813 (partial-PUT RCE/info-disclosure, cond.), CVE-2024-50379 + CVE-2024-56337 (TOCTOU RCE on case-insensitive FS — note Workers run on macOS, cond.), CVE-2025-31650/31651 (DoS / rewrite bypass). Fixed only in 10.1.35+/10.1.40+. Ships with F01-01 upgrade. |
| `spring-core/web/webmvc` | 6.1.15 | Last OSS 6.1.x release; no free patches after. CVE-2025-22233 (DataBinder) among later fixes. |
| `spring-security-*` | 6.2.8 | Last OSS 6.2.x release; relevant once feature-03 SecurityConfig lands (e.g. CVE-2025-22235 actuator `EndpointRequest.to()` bypass at the Boot level). |
| `postgresql` (JDBC) | 42.6.2 | **OK** — maintained 42.6.x backport branch; patched for CVE-2024-1597 (SQLi, fixed ≤42.6.1) and CVE-2025-49146 (channel-binding). No action. |
| `jackson-databind` + core | 2.15.4 | **OK** — dated but no critical known CVE with default typing off; refreshed by F01-01. |
| `snakeyaml` | 2.2 | **OK** — past CVE-2022-1471 (fixed 2.0). |
| `flyway-core` | 9.22.3 | **OK.** The pom comment correctly documents why `flyway-database-postgresql` (10.x-only) was dropped vs. the architecture doc — a deliberate, sound deviation. |
| `hibernate-core` | 6.4.10.Final | **OK.** |
| `commons-lang3` | 3.20.0 (test) | **OK** — above 3.18.0, so patched for CVE-2025-48924 (recursion DoS); test-scope only, correctly justified in the pom for the embedded-postgres runtime need. |
| `io.zonky.test:*`, `testcontainers` | test-scope | Not shipped; runtime blast radius none. |

## CI/CD recommendation (carry into feature-03 pipeline)

Wire the SR-23 gate now so F01-01-class issues can't recur: `osv-scanner` / OWASP Dependency-Check on
`pom.xml` (fail on High/Critical), `gitleaks` (block committed tokens — the four secrets arrive in
feature-03), and `semgrep` `p/java`/`p/spring`/`p/sql-injection`/`p/secrets` (keeps F01-04 out of
`src/main`). Keep the per-PR set under ~2 min; run Dependency-Check full + ZAP baseline on schedule.

## Status of remediation

Open: F01-01 (High, before feature-03/release), F01-03 (Low, ops/grant), F01-02/04/05/06 (Low/Info,
tracked). Closed this round: none required for the data-model merge.

## Bottom line

Feature-01 is safe to merge as a data-model layer: no code-level vulnerability, SR-13 verified clean,
no exposed surface. Schedule the Spring Boot upgrade (F01-01) before feature-03 wires HTTP/security/
actuator, since that is when the Tomcat/actuator CVEs become reachable and the upgrade becomes costly.

## Remediation update (2026-07-12)

**F01-01 — CLOSED.** Upgraded `spring-boot-starter-parent` from `3.2.12` to `3.5.16` (latest stable
3.5.x in Maven Central at time of upgrade; the 3.2.x line stays EOL). Requirements §2.1 mandates
"Spring Boot 3.x" generically, not the 3.2 minor specifically, so this is a spec-compliant move, not a
deviation — the 3.2.12 pin in the codegen brief/architecture doc was the stricter, now-outdated source.
Resolved versions after upgrade (`mvn dependency:tree`): `tomcat-embed-core:10.1.55`,
`spring-core:6.2.19`, `hibernate-core:6.6.53.Final`, `postgresql:42.7.11`,
`jackson-databind:2.21.4` — all clear of the CVEs listed above. Re-added `flyway-database-postgresql`
(now required alongside `flyway-core` on Flyway 11.x; both BOM-managed, no explicit version) —
resolved `flyway-core:11.7.2` / `flyway-database-postgresql:11.7.2`. The `commons-lang3:3.20.0` test
pin is still required (Boot 3.5.16's BOM only manages 3.17.0, below both the Zonky-required method and
the CVE-2025-48924 fix line) — kept, comment updated. No `src/main`/`src/test` code changes were needed;
all 48 existing tests pass unchanged (`mvn test`, exit 0). `V1__initial_schema.sql` was not touched.

**F01-02 — CLOSED** (as predicted): resolved `logback-classic` is now `1.5.34`, well past the patched
1.5.x line; no standalone action was needed.

**Deferred items — tracking note.** The following findings remain open by design and are not
addressed in this remediation pass; they will land as follows:

- **F01-03** (`review_events` audit-trail integrity: `ON DELETE CASCADE`, no append-only DB grant) —
  deferred to the **ops runbook / deployment migration** that provisions the Gateway DB role (grant
  `INSERT`/`SELECT`-only on `review_events`) and to the retention-job design (SR-22), not this
  code-level remediation.
- **F01-04** (test-only string-built SQL, no injectable path) — optional hardening tracked as a CI
  lint item (Semgrep `p/sql-injection` gate, SR-23), to be wired with the feature-03 pipeline; not a
  code change against this pass.
- **F01-05** (unbounded `TEXT` columns; real control is the edge body-cap) — tracked for
  **feature-02/03** (`POST /reviews` diff-size cap SR-11, `/jobs/{id}/result` size cap SR-21) where the
  write-path controllers/services are implemented.
- **F01-06** (`backends.url` SSRF sink, no allowlist) — tracked for **feature-03** (backend-registry
  write path + probe client, SR-10 allowlist / loopback / link-local / metadata-endpoint rejection).
</content>
</invoke>
