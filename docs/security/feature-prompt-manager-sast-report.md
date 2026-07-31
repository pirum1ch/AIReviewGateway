# AppSec SAST Report — feature/prompt-manager (V3: repo-sourced system prompts)

> **STATUS (final): RELEASE GATE OPEN — approved for merge into `master` (verified at `ac4481c`).**
> Everything below up to "Fixes applied by appsec in this round" is the **round-1** report (verdict
> *NEEDS A DEV PASS*), kept verbatim as the historical record. The backend-developer fix round and my
> line-by-line verification of it are in
> [Round 2 — final verification / release gate](#round-2--final-verification--release-gate) at the end
> of this document. Read that section for the current status of every finding.

Scope: `master..feature/prompt-manager`, HEAD `6a4dcef` at the start of this round, tree clean apart from
the three untracked design docs. 9 commits (`7fb03a8`/`eab00a7`/`270e58c`/`2e5f822` dev → `8947483` docs →
`8c087ff` dev self-review → `8ef36b2`/`ad17cb8`/`6a4dcef` QA), 75 files, +5375/−144. In scope: the new
`service/PromptManager`, `service/PromptSourceResolver`, `service/PromptAssembler`,
`service/PromptMessageFormatter`, `service/TextSanitizer`, `service/BoundedInputStream`, the
`GitLabClient`/`GitLabClientImpl` read extension, `model/ReviewPromptSection` +
`repository/ReviewPromptSectionRepository`, the five new enums, `db/migration/V3__prompt_manager.sql`,
the `GatewayProperties.Prompt` tree + its startup validation, `RestClientConfig.gitLabPromptRestClient`,
the V3 edits to `ReviewService`/`QueueManager`/`DiffSizeValidator`/`ChunkContextRenderer`/
`StatisticsService`/`GlobalExceptionHandler`/`dto.JobPayload`/`service.dto.ClaimedJob`, and the Worker's
`prompt/PromptTemplateService` + `gateway/dto/JobPayload` + `gateway/dto/ClaimResponse` +
`config/WorkerProperties`.

Method: verification of each of my own pre-implementation MUSTs (**PMR-01…PMR-30**,
`docs/prompt-manager-threat-model.md`) against the code that was actually built — reading and tracing the
code, not the design doc and not the developer's own report — plus a general SAST pass (injection /
resource exhaustion / deserialization / access control / secret & payload leakage / exception handling /
migration safety / concurrency / dependency delta), plus **six executable probes** run against the real
classes rather than desk-checked, plus the explicit non-regression set from threat model §7.

**Suites (run by me, not taken on report):** Gateway `mvn -o test` → **516/516**, BUILD SUCCESS
(pre-fix baseline), Worker `mvn -o -f worker/pom.xml test` → **125/125**, BUILD SUCCESS. Post-fix results
at the end of this report.

**Scanners (run by me):** `semgrep` (`p/java` + `p/sql-injection` + `p/secrets` — the exact gate config
from `.github/workflows/security-gate.yml`) over `src/main/java` + `worker/src/main/java` → **0 findings**
(96 rules, 147 files, ~100 % parsed). `gitleaks` full history (64 commits, `.gitleaks.toml`) →
**no leaks found**. SCA: `pom.xml` and `worker/pom.xml` have a **zero-byte diff** vs. the previous
verified-clean baseline (`git diff 813ae61 HEAD -- pom.xml worker/pom.xml` is empty) — no new direct or
transitive dependency, so the feature-diff-chunking dependency verdict carries over unchanged.

## Round-1 verdict (superseded by Round 2 below): **NEEDS A DEV PASS** — 1 Medium release blocker (deployability), 1 Medium fixed here, plus 6 should-/nice-to-fix.

Severity counts: Critical 0 · High 0 · **Medium 2** (1 fixed by me) · Low 2 (1 fixed by me) · Info 8.

This is the strongest feature this project has shipped to date on the security axis. The load-bearing
controls all hold up under execution, not just inspection: the U+241E delimiter is **structurally
non-forgeable** (verified over all 114 split points of a self-nesting payload — F-DC-02's lesson correctly
generalized rather than re-learned), `max-file-bytes` is a genuine **streaming** bound (F-DC-01's lesson
likewise), claim-time assembly **fails closed** on missing corporate sections instead of degrading to an
empty system prompt, the GitLab credential is genuinely split, `followRedirects(NEVER)` is proven at the
transport layer, the MR target branch is **never read at all** (the blocking PMT-02 architectural flaw is
gone at the source, not patched around), and the system prompt is a first-class term in the diff budget.
23 of the 25 blocking MUSTs are fully closed; the other two are partial in ways that do not open an
attack path.

What is not ready: **the stock deployment cannot start** (F-PM-02 — `application.yml` never grew a
`gateway.prompt` block, so `gateway.prompt.enabled` defaults to `true` with no corporate project and no
prompt token, and the two environment variables `DEPLOYMENT.md` tells the operator to use do not bind to
anything), and the `on-error=SKIP_OPTIONAL` control did not actually cover the failure class most likely
to be triggered by an ordinary developer (F-PM-01, found by QA, classified and fixed here).

**Fixed by me in this round (with regression tests):** F-PM-01 (Medium), F-PM-04 (Low), F-PM-05 (Info).
**Must-fix before merge (back to backend-developer):** F-PM-02 (Medium).
**Should-fix in the same pass (cheap, same files):** F-PM-03, F-PM-06, F-PM-10.
**Nice-to-have / requirement-amendment candidates:** F-PM-07, F-PM-08, F-PM-09, F-PM-11.
**QA follow-up:** F-PM-12 (a pre-existing concurrency test leaks backend capacity and is intermittently red).

---

## Findings

| # | Severity | CWE / OWASP | Where (file:line) | Description | Status / remediation |
|---|----------|-------------|-------------------|-------------|----------------------|
| **F-PM-01** | **Medium** (CVSS:3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:L` = 4.3; the impact is on the *availability of a security control*, which this project rates above a plain endpoint DoS) | CWE-755 (improper handling of exceptional conditions) / CWE-703, A04:2021 | `PromptManager.java:146` (pre-fix) | **`gateway.prompt.error-handling.on-error=SKIP_OPTIONAL` was a no-op for the entire `PromptSourceInvalidException` family.** The catch around the *optional* `PROJECT_*` resolution named only `PromptSourceUnavailableException`, so every `PromptSourceInvalidException` — file over `max-file-bytes`, invalid UTF-8, a NUL byte, **or an empty file** — propagated out of `doResolve` and turned `POST /reviews` into a `422 PROMPT_SOURCE_INVALID` regardless of how the operator configured error handling. Architecture §3 step 4c says the opposite ("other error ⇒ FAIL or SKIP_OPTIONAL per config"). Consequence: any developer who can land a file on their project's **default branch** — e.g. a 300 KB `.ai-review/architecture.md`, a UTF-16-saved doc, or simply an empty placeholder file — makes AI review **permanently un-creatable for that project**, and the operator's documented escape hatch does not work. The zero-privilege variant is the most likely one in practice: an accidentally-committed empty `.ai-review/architecture.md` breaks the pipeline with a message about UTF-8/NUL that points nowhere near the cause. **Reproduced** (probe 1, real `PromptManager` + mocked `GitLabClient`): with `on-error=SKIP_OPTIONAL`, an invalid optional `PROJECT_ARCHITECTURE` threw `PromptSourceInvalidException`; with `on-error=FAIL` it threw the same thing — i.e. the config value had **no observable effect at all**, the discriminator a fix must change. Originally spotted by qa-engineer; classified, reproduced and fixed here. | **FIXED BY APPSEC.** `catch (PromptSourceUnavailableException \| PromptSourceInvalidException projectFailure)`. Scope is provably unchanged for corporate sections: they are fetched *above* the `try` block and still fail hard under either setting (pinned by a new test). Three regression tests added to `PromptManagerTest`: skip-under-`SKIP_OPTIONAL`, still-fail-under-`FAIL`, and corporate-always-fails-even-under-`SKIP_OPTIONAL`. |
| **F-PM-02** | **Medium** | CWE-1188 (insecure default) / CWE-710 / CWE-665, A05:2021 | `src/main/resources/application.yml` (no `gateway.prompt` block at all; `gateway.gitlab` has no `prompt-token` key) vs. `GatewayProperties.java:697` (`enabled = true`) and `DEPLOYMENT.md:158-170`, `DEPLOYMENT.md:698-711` | **The stock deployment refuses to start, and both documented environment variables are inert.** `application.yml` was never extended for this feature: there is no `gateway.prompt.*` subtree and no `gateway.gitlab.prompt-token` key. But `GatewayProperties.Prompt.enabled` defaults to **`true`** in Java, so `validatePromptOnStartup()` runs on every boot and fails on the very first check. **Reproduced** (probe 6, the exact property set the stock `application.yml` + `DEPLOYMENT.md` §10.1 env file produce): `gateway.prompt.enabled default = true` → `IllegalStateException: gateway.gitlab.prompt-token must be set (SR-01) — refusing to start`. Worse, both escape routes the deployment doc gives are dead: `GITLAB_PROMPT_TOKEN` has no `${...}` placeholder to bind through (Spring's relaxed binding would need `GATEWAY_GITLAB_PROMPTTOKEN`), and `PROMPT_MANAGER_ENABLED=false` — which `DEPLOYMENT.md:169` presents as the supported "not ready for Prompt Manager yet" path — binds to nothing at all (it would have to be `GATEWAY_PROMPT_ENABLED`). Nor is there any wiring for `PROMPT_CORPORATE_PROJECT`, which architecture §5 specifies. This is **not a vulnerability** — the direction is fail-closed and it surfaces on the first deploy attempt — but it is a release blocker, it leaves PMR-15's "env-only" clause literally unimplemented, and the realistic operator response to an un-bootable Gateway (hard-coding `gateway.prompt.enabled: false` into the YAML to get it up) silently and durably disables the security control this whole feature exists to add — PMT-22's exact failure mode, arrived at by accident. Exactly the F-DC-04 class of defect (properties class, `application.yml` and docs disagreeing), one feature later, and stronger. | **NEEDS BACKEND-DEVELOPER.** Add the architecture-§5 block to `src/main/resources/application.yml`, env-overridable throughout, and the token key alongside the existing one: `gateway.gitlab.prompt-token: ${GITLAB_PROMPT_TOKEN:}` and `gateway.prompt: {enabled: ${PROMPT_MANAGER_ENABLED:true}, corporate: {project: ${PROMPT_CORPORATE_PROJECT:}, ref: ${PROMPT_CORPORATE_REF:main}, …}, limits: {…}, connect-timeout/read-timeout/total-timeout}`. Add `GITLAB_PROMPT_TOKEN` and `PROMPT_MANAGER_ENABLED`/`PROMPT_CORPORATE_PROJECT` to the `DEPLOYMENT.md` §10.1 env-file appendix (§2's prose mentions the token but the copy-pasteable file does not). Then decide deliberately, and document, whether the shipped default for `enabled` is `true` (secure default, but every existing deployment must provision a token before it can restart) or `false` (upgrade-safe, but the control is off until switched on) — that is a deployment-policy call, which is why this is not patched here. Add a test that boots the real `application.yml` property set with only the documented env vars present. |
| **F-PM-03** | Low | CWE-400 / CWE-770, A04:2021 | `PromptAssembler.java:122-125` (the aggregate cap throws) + `PromptManager.java:160` (`assemble` is called *outside* the project try/catch) | **`PromptTooLargeException` from an optional project section is likewise not skippable, and there is no per-source cap on the project half of the budget.** `max-system-prompt-tokens = 6000` (≈24,000 chars at `chars-per-token=4`) is enforced only as one aggregate over corporate + project sections, and the throw happens after the try/catch, so `on-error=SKIP_OPTIONAL` cannot reach it either. **Reproduced** (probe 2): a 200 KB `.ai-review/architecture.md` — comfortably under `max-file-bytes = 262144`, and not an implausible size for a real architecture document — yields `PromptTooLargeException` → `422 PROMPT_TOO_LARGE` on every `POST /reviews` for that project, with **no operator escape hatch**. Same end state as F-PM-01 but reached through a different exception, and reachable by accident (a genuinely large doc) as easily as on purpose. Rated Low rather than Medium only because it needs content on a **protected default branch** (PMR-05's merge gate is doing real work here) and because the 422-over-truncation choice is itself a PMR-21 requirement I wrote — silent truncation of the corporate rulebook would be strictly worse. | **NEEDS BACKEND-DEVELOPER (design call).** Preferred: move the `assemble` call inside the same catch and add `PromptTooLargeException` to it **only when the overflow is attributable to `PROJECT_*` content** — i.e. assemble corporate-only first, and if adding the project sections breaks the aggregate, drop the project sections, set `degraded = true`, WARN, and proceed under the corporate rulebook. That keeps PMR-21's "never silently truncate the corporate rules" intact (corporate-only overflow must still be a hard 422) while making the *optional* half genuinely optional. Cheaper alternative: a `max-project-section-tokens` sub-budget checked per project section before assembly. Either way, wire the outcome into the existing `prompt_degraded` column so the audit trail records it. |
| **F-PM-04** | Low (defense in depth) | CWE-20 / CWE-22, A03:2021 | `GatewayProperties.java:235` (pre-fix) | **`requireProjectRef` accepted a `..` segment, contrary to PMR-14's explicit wording.** `PROJECT_REF_PATTERN` = `^[0-9]+$\|^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+){1,10}$`, and a bare `..` matches the `[A-Za-z0-9._-]+` segment class — so unlike `requireRef` and `requireSourcePath`, which both carry an explicit `value.contains("..")` guard, the project-reference validator had none. **Reproduced** (probe 4): `gateway.prompt.corporate.project = "group/../other-group/prompts"` was **accepted at startup**. Not exploitable as a traversal today — every project reference reaches the wire as a strictly-encoded URI template variable (`/` → `%2F`, so the whole reference is one opaque segment; independently pinned by `GitLabClientImplTest.resolveCommitShaUrlEncodesAGroupPathProjectReference`) and the value is deploy-time trusted config either way. But it is the one validator missing the guard its two siblings have, on the input that selects an outbound authenticated fetch target, and PMR-14 names the rule explicitly. | **FIXED BY APPSEC.** Explicit `\|\| value.contains("..")` added to `requireProjectRef`, with the comment explaining why the regex alone does not cover it. Three regression tests in `GatewayPropertiesPromptValidationTest`: corporate ref, override ref, and a scope guard proving legitimate single dots (`org.platform/ai-review.prompts`) still start. |
| **F-PM-05** | Info | CWE-532, A09:2021 | `PromptAssembler.java:86-88` (`SectionCandidate`) | **The one record carrying the untrusted section body in its rawest in-process form shipped with the default record `toString()`** — precisely the latent leak channel F-DC-07 closed for `DiffChunk`/`ChunkPlan`/`CreateReviewCommand`/`SubmitResultCommand`. `AssembledSection`, `ResolvedSystemPrompt`, `ReviewPromptSection`, `dto.JobPayload` and `service.dto.ClaimedJob` were all correctly masked; `SectionCandidate` — which holds the post-sanitization, pre-wrapping text and is the value passed around inside `PromptManager` — was not. **Reproduced** (probe 5): `SectionCandidate.toString()` rendered `sanitizedContent=SUPER-SECRET-SECTION-BODY-TOKEN` in full. No current log statement renders it (grep-verified across `src/main`), so this was latent, not live. | **FIXED BY APPSEC.** Masked `toString()` in the same house style (`<masked, N chars>`), provenance metadata preserved. Four tests added to `SensitiveDtoToStringMaskingTest` including the `List.of(...)`/`String.format`/`"" +` interpolation shapes and an accessor-still-returns-raw guard. |
| **F-PM-06** | Info | CWE-117 (log injection) / CWE-116, A09:2021 | `PromptManager.java:134` → `PromptAssembler.java:69` (`AssembledSection.toString`), `ReviewPromptSection.java:187`, `V3__prompt_manager.sql:25` (`source_ref`) | **The GitLab-supplied `default_branch` name is persisted and rendered without Cc/Cf sanitization.** PMR-25 requires "the MR target-branch name and configured paths are sanitized (Cc/Cf stripped, length-capped) before appearing in **any** log line or error body (F-DC-06/WSR-18)". The target branch is no longer read at all (PMR-05 — good), but the value that replaced it, `resolveDefaultBranch`'s return, is repo-controlled in exactly the same way and flows unsanitized into `source_ref` and into three `toString()` renderings. Materially weaker than the original F-DC-06 finding: it is not reflected into any HTTP response body (verified — every prompt-path exception message is a fixed string plus integers), and no current log statement renders any of the three objects, so this is latent. A branch name is also project-maintainer-controlled rather than any-MR-author-controlled. Secondary: a `default_branch` longer than 256 characters would overflow `source_ref VARCHAR(256)` and surface as a `DataIntegrityViolationException`, which `ReviewService.createReview:163` misinterprets as a dedup race and rethrows as a `500` (GitLab's own ref-name limit makes this practically unreachable). | **NEEDS BACKEND-DEVELOPER (cheap).** Run the resolved branch name through `TextSanitizer.sanitizePath(ref, 200)` once, where it is returned by `resolveDefaultBranch`, before it is used for anything — one call site, closes the persistence, logging and column-overflow cases together. Then it is sanitized-at-the-boundary rather than masked-at-each-renderer. |
| F-PM-07 | Info | CWE-770, A04:2021 | `PromptMessageFormatter.java:45-88`; `GatewayProperties.java:917` (`maxSections`) | **PMR-08's claim-time `max-sections` cap and total-assembled-byte cap are not implemented; `gateway.prompt.limits.max-sections` is dead config.** Grep confirms `getMaxSections()` is read in exactly one place — its own `>= 1` startup validation — and never by `PromptMessageFormatter` or anything else. The bound that PMR-08 asked for does exist, but structurally rather than as a check: `UNIQUE (review_id, kind)` (V3:31) plus a four-value `CHECK` on `kind` (V3:18-19) caps the table at 4 rows per Review, and the formatter's `EnumMap` (`:50-53`) collapses any duplicate that somehow appeared. Total assembled bytes are bounded transitively by the create-time 6000-token aggregate. So the "assembly with a corrupted row set is bounded, not unbounded" property holds — it is just enforced by the schema rather than by the code that PMR-08 named, and a row hand-edited to carry oversized content would not be re-capped at claim time. | Either add the explicit claim-time guard (cap `sections.size()` at `max-sections` and the joined byte total, log + drop the excess) or amend PMR-08 to state that the cardinality bound is deliberately delegated to the schema and delete the unused config key. Do not leave a validated-but-unread limit in the properties tree — that is a control that looks present and is not. |
| F-PM-08 | Info | CWE-1188 / CWE-1427, A05:2021 | `PromptAssembler.java:130-141` (`toSection(candidate, wrapInDelimiter)`) vs. `PromptMessageFormatter.java:67-77` | **The PMR-02 delimiter wrapping is baked into the stored `content` at create time, not re-derived from `kind` at claim time.** The formatter is correctly `kind`-driven for *ordering* and for the preamble/trailer (PMR-04 ✔), but it emits `section.getContent()` verbatim and therefore trusts that whatever wrote the row applied the `␞␞␞ BEGIN/END` wrapper. A `PROJECT_*` row whose stored content lacks the wrapper — a future bug, a partial backfill, a manual insert — would reach the model's system role **undelimited**, and nothing at claim time would notice. The create-time path is currently correct (verified), so this is a robustness gap, not a live defect. | Cheap defense in depth: in `PromptMessageFormatter`, assert that a `PRESENT` `PROJECT_*` row's content starts and ends with the expected delimiter lines for its `kind`, and refuse/re-wrap otherwise. That makes the trust-boundary rendering a property re-checked on every claim rather than one inherited from create time — the same argument PMR-09 already won for the fail-closed check next to it. |
| F-PM-09 | Info | CWE-636 (fail-open) / CWE-1088, A04:2021 | `PromptManager.java:192-197` (`checkDeadline`) + `:133-145` | **A `total-timeout` breach is swallowed as "degraded" under `SKIP_OPTIONAL`, and the effective wall-clock bound is `total-timeout + read-timeout`, not `total-timeout`.** `checkDeadline` throws `PromptSourceUnavailableException`, and the four calls that guard project-section resolution sit *inside* the try block that `SKIP_OPTIONAL` catches — so exhausting the 20 s budget is reported as an ordinary degradation rather than as the deadline breach it is. Separately, the deadline is checked *before* each outbound call and never interrupts one in flight, so a call entering at t=19.9 s can run to its own 8 s read timeout: worst case ≈28 s, not 20 s. Neither undermines PMR-19's load-bearing property — the 4-permit semaphore with immediate `503` is what actually prevents Tomcat-pool starvation, and that is implemented correctly and tested (`saturatedConcurrencyPermitRejectsImmediatelyRatherThanQueueing`). | Give the deadline its own exception type (or a flag on the existing one) so `SKIP_OPTIONAL` does not absorb it, and document the `total-timeout + read-timeout` worst case in the `Prompt.totalTimeout` javadoc — the startup rule `total-timeout >= 2 × read-timeout` already implies the operator is meant to reason about the two together. |
| F-PM-10 | Info | CWE-1188, A05:2021 | `RestClientConfig.java:29-42` (`gitLabRestClient`) | **The write-scoped GitLab client still relies on the JDK's default redirect policy rather than pinning it.** PMR-16 mandates `followRedirects(NEVER)` for `gitLabPromptRestClient` (done, `:59`, and genuinely proven at the transport layer), but threat model §4.4 asked for it on **both** clients, precisely because `PRIVATE-TOKEN` is a custom header the JDK does *not* strip on a cross-host redirect the way it strips `Authorization`. `HttpClient.Builder`'s default happens to be `NEVER`, so behaviour is correct today — QA's own test even asserts it (`gitLabRestClientAlsoNeverFollowsA302ToAnotherHost`) — but it is inherited, not stated, exactly the "implicit default one edit away from a fleet-wide problem" shape PMT-05 called out for the Jackson defaults. | One line: `.followRedirects(HttpClient.Redirect.NEVER)` on `gitLabRestClient`, matching the other two beans. QA's existing test then guards a stated contract instead of a JDK default. |
| F-PM-12 | Info | — (test defect, QA-owned; CI-reliability impact) | `ClaimCancelObsoleteConcurrencyTest.java:193-235` (`multiChunkConcurrentResultSubmitAndCancelNeverDeadlockOrLeakAnUnhandled500`) + `:287-302` (`claimAllChunkJobs`) | **The F-DC-03 multi-chunk hammer test leaks backend capacity and fails intermittently, with a misleading message.** Observed in this round: full-suite run #1 → `Tests run: 525, Failures: 1`, `AssertionError: Expected to claim chunk job 0 of review 35 but got 204 NO_CONTENT`; the same test **passes 2/2 in isolation** and the full suite passed **525/525** on re-run — i.e. intermittent, load-dependent. Root cause is arithmetic in the test, not a claim defect: the backend is registered with `capacity = 10` and the loop runs **8 iterations × 3 claimed jobs = 24 claims**. Per iteration, jobs 0 and 1 get results submitted, but **job 2 is only ever driven to a terminal state by the admin `DELETE` cascading to it** — and `recordIfUnexpected` deliberately whitelists `409` as "a legitimate outcome", so every iteration in which the cancel loses a lock race leaves job 2 **RUNNING forever**. Since `BackendDispatcher` derives load from the count of currently-RUNNING jobs, 8 leaked jobs + 3 in-flight exceeds the capacity of 10, `resolveClaimableBackend` returns empty, `/jobs/claim` correctly answers `204`, and the assertion fires against a *late* iteration (review 35) — pointing the reader at claim logic that is behaving exactly as designed. Not a production defect, and unrelated to this feature (the reviews it creates are `promptBundleMode=NONE`, so no Prompt Manager code executes) — but it makes the SR-23 gate intermittently red on a branch that should be green, which erodes the gate's signal, and it mis-attributes its own failure. | **QA follow-up.** Either raise the test backend's `capacity` above `iterations × 3` (cheapest, one number), or drive job 2 to terminal explicitly each iteration, or assert on capacity/RUNNING count so the real cause is reported instead of "expected to claim". Same spirit as the original F-DC-08 remediation: a concurrency test must fail for the reason it names. |
| F-PM-11 | Info | CWE-778, A09:2021 | `PromptAssembler.java:140` (per-section hash ✔) — no assembled-output hash anywhere; `StatisticsService.java:83-84` | **Two sub-clauses of PMR-07/PMR-11 are partial.** (a) PMR-07's "the assembler's output hash for the review is recorded once, so the full assembled prompt is reconstructible from the DB" is not implemented — there is no assembled-prompt hash column or event. Reconstructibility itself *is* satisfied structurally (ordering derives from `kind`, the preamble/trailer are compile-time constants, and each row's `content_sha256` covers its exact stored bytes), so the forensic property survives; what is missing is the single artifact that would let an auditor verify a reconstruction without re-deriving it from the code version. (b) PMR-11's metric is exposed as two flat counters (`promptDisabledCount`, `promptSectionMissingCount`) rather than `prompt_section_absent_total{kind, configured}` — correct in substance, no label dimensions, so an operator cannot tell *which* override is broken from `/metrics` alone (the `review_events` row does carry `kind=…`). | (a) Record the assembled hash once per Review — cheapest form is a `PROMPT_ASSEMBLED` event carrying `sha256(joined pieces)`, no schema change. (b) Either add the two dimensions or amend PMR-11 to match what a label-less `/metrics` endpoint can express (this project deliberately has no Prometheus — see requirements §15 — so label-less is a legitimate reading). |

### F-PM-01 — reproduced discriminator (verbatim probe output against the real `PromptManager`)

```
PROBE1  [F-PM-01] on-error=SKIP_OPTIONAL + invalid optional PROJECT section:
        THREW PromptSourceInvalidException -> POST /reviews 422 despite SKIP_OPTIONAL
PROBE1b on-error=FAIL: THREW PromptSourceInvalidException (expected)
PROBE2  [F-PM-03] oversized optional section under SKIP_OPTIONAL:
        THREW PromptTooLargeException -> 422 PROMPT_TOO_LARGE, no operator escape hatch
```

The point of PROBE1b is that the two configurations were **indistinguishable**: the setting had no
observable effect, which is what makes this a broken control rather than a debatable severity call.

### F-PM-02 — reproduced (verbatim probe output, stock `application.yml` + `DEPLOYMENT.md` §10.1 env file)

```
PROBE6 [F-PM-02] gateway.prompt.enabled default = true
PROBE6 [F-PM-02] stock config REFUSES TO START:
       gateway.gitlab.prompt-token must be set (SR-01) — refusing to start
```

---

## PMR-by-PMR verification (against the shipped code, not the design doc)

| PMR | Verdict | Evidence |
|-----|---------|----------|
| **PMR-01** constant preamble + trailer, both formats, counted in budget | **PASS** | `PromptAssembler.PREAMBLE`/`TRAILER` are `public static final String` literals (`:39-46`) — grep confirms neither is ever assigned from config or from a fetch. Emitted identically by the create-time budgeter (`:117-119`, only when project content is actually present) and by the claim-time renderer (`PromptMessageFormatter:69-80`), in the right positions in **both** MULTI and SINGLE. Tested: `preambleAndTrailerAreEmittedWhenProjectSectionsArePresent`, `noProjectSectionsMeansNoPreambleTrailerTokenCostAdded`, `multiFormatGivesOneMessagePerPresentSectionPlusPreambleTrailer`, `singleFormatGivesExactlyOneJoinedMessage`. |
| **PMR-02** non-forgeable begin/end delimiter | **PASS (verified by execution)** | The delimiter is a **single code point** (U+241E) repeated, and `TextSanitizer.sanitizeSectionText:79` strips every occurrence of that code point from section content — so the F-DC-02 failure mode is not merely fixed but structurally unreachable. **Probe 3, run against the compiled classes:** `PromptAssembler.DELIMITER_CHAR` = U+241E and `TextSanitizer.DELIMITER_CODE_POINT` = U+241E **match** (this is worth checking rather than assuming — a non-UTF-8 source encoding would silently desynchronize the literal from the numeric constant; `spring-boot-starter-parent` pins `project.build.sourceEncoding=UTF-8`, and the runtime values confirm it); the F-DC-02 self-nesting payload `X.substring(0,mid) + X + X.substring(mid)` replayed at **all 114 split points** of the end-delimiter token left **no U+241E** in any output; a rendered block contains **exactly 2** delimiter lines. Also tested in-suite: `selfNestingPayloadCannotForgeAnEarlyBlockClose`, `delimiterStrippingIsNotASinglePassStringReplace`, and end-to-end in `selfNestingDelimiterPayloadInProjectSectionCannotForgeABoundaryInAssembledSingleMessage`. |
| **PMR-03** Cc(except `\n`/`\t`)/Cf/Zl/Zp + delimiter stripped, NUL rejected, strict UTF-8, shared helper | **PASS** | `TextSanitizer.sanitizeSectionText:69-84` keeps `\n`/`\t` and strips CONTROL/FORMAT/LINE_SEPARATOR/PARAGRAPH_SEPARATOR + U+241E in **one code-point pass** — which is strictly stronger than the "Cc/Cf before delimiter" ordering CSR-09 required, because per-code-point stripping has no ordering to get wrong (nothing can recombine). Probe 3 confirmed on live input: `line1\n␮evil…` → bidi overrides U+202E/U+2066/U+2069, U+2028, U+2029, U+0007 and U+241E all gone, `\n`/`\t` preserved. NUL is rejected explicitly (`GitLabClientImpl:187-189`) because 0x00 is *valid* UTF-8 and would otherwise pass the decoder; decoding is strict `REPORT`/`REPORT` (`:196-205`), so no U+FFFD garbage can occupy budget. Genuinely shared, not copy-pasted: `ChunkContextRenderer.sanitizePath:75` now delegates to `TextSanitizer.sanitizePath` and the diff shows the old body deleted, character classes and `capLength` semantics byte-identical. |
| **PMR-04** `CORPORATE_*`/`PROJECT_*` non-mergeable; ordering from `kind` | **PASS** (see F-PM-08 for the delimiting half) | DB `CHECK` on the four `kind` values (V3:18-19) plus `UNIQUE (review_id, kind)` (V3:31). Claim-time assembly indexes an `EnumMap<PromptSectionKind, …>` and looks each section up **by `kind`** (`PromptMessageFormatter:50-63`) — never by `ordinal`, row id or insertion order, so a reordered/renumbered row set cannot promote project text into a corporate position. Create-time `kind` values are compile-time constants at fixed positions in `PromptManager`; no code path derives `kind` from fetched content. |
| **PMR-05** never an unprotected, MR-author-chosen ref | **PASS (strongest form)** | The mode that created the risk is **not implemented at all**: grep across `src/main/java` finds `merge_requests` only in `DISCUSSIONS_PATH` (the pre-existing publish call) and finds no `target_branch`/`targetBranch` anywhere. `PromptSourceResolver:50-68` returns either the reviewed project itself with `explicitRef = null` (⇒ `PromptManager:134` calls `resolveDefaultBranch`) or an operator-pinned override ref from deploy-time config. There is therefore no client- or MR-author-influenced ref input to the prompt path — the blocking PMT-02 flaw is closed at the source rather than guarded against, which is the better outcome. The optional "verified-protected target branch" mode is deliberately skipped, documented in the class javadoc. |
| **PMR-06** exactly one resolver call site; retries read persisted rows | **PASS (grep-proved, as the requirement asked)** | `grep -rn "\.resolve(" src/main/java` returns **exactly one** hit: `ReviewService.java:148`, on the create path, after dedup and before `persistNewReview`. `PromptManager` is injected into no other class (`grep -rn "PromptManager" src/main/java` — only `ReviewService`, plus two javadoc mentions and one unrelated `MetricsResponse` field name). Claim time reads the DB and only the DB (`QueueManager:220-221` → `findByReviewIdOrderByOrdinalAsc`), so a retry, a re-claim and every sibling chunk of the same Review all render from the same immutable rows — one Review can never execute under two rulebooks. |
| **PMR-07** hash over stored bytes; insert-only; DB grant | **PARTIAL** | ✔ `content_sha256` is computed over the **exact stored** post-sanitization, post-wrapping bytes (`PromptAssembler:140` hashes the same `content` string it puts in the row). ✔ Insert-only in Java: every column but `id` is `updatable = false` (`ReviewPromptSection:45-81`), the repository exposes no `deleteBy`/`updateBy` derived query, and `save()` is called exactly once per row at creation (`ReviewService:367`). ✖ **The DB grant is documentation-only** — `DEPLOYMENT.md:209` carries the correct `GRANT SELECT, INSERT ON review_prompt_sections` line and V3 deliberately does not `GRANT` (consistent with V1/V2, which never grant either, since Flyway runs as the schema owner). **Assessment: sufficient as-is, and a stronger control is not realistically testable here.** The test stack is Zonky embedded-postgres with a single superuser role; a test that creates a constrained role, re-grants, and asserts an `UPDATE` is denied would prove only that PostgreSQL enforces its own grants, not that the *production* role was provisioned correctly — the real control is the deployment runbook, exactly as already accepted for `review_events` under SR-19, and the Java-level surface independently mirrors it. What I would add instead is an operational check, not a test: a startup/health assertion (or a documented `\dp` step in the deploy checklist) that reports the actual grants the app role holds, so drift is visible where it actually happens. ✖ No assembled-output hash — F-PM-11. |
| **PMR-08** `UNIQUE(review_id, kind)` + claim-time cap | **PARTIAL** | ✔ `UNIQUE (review_id, kind)` **and** `UNIQUE (review_id, ordinal)` (V3:30-31). ✖ Claim-time `max-sections`/total-byte cap not implemented; the bound is structural instead — see **F-PM-07**. No attack path (the schema caps the row count at 4), but the named config key is dead. |
| **PMR-09** fail-closed at claim; `prompt_bundle_mode` | **PASS** | `reviews.prompt_bundle_mode` with a `CHECK` (V3:40-42), set at create time from the resolution mode. `PromptMessageFormatter:57-60` throws `PromptSectionsMissingException` whenever mode is `REPO` and either mandatory `CORPORATE_*` row is absent — never an empty or partial list. `QueueManager:224-236` catches it inside the job-lock transaction, leaves the job `RUNNING` so the parent can legally advance, then fails the job in a separate transaction (`:251-264`) with a `PROMPT_SECTIONS_MISSING` event; the job is never dispatched. `NONE` mode returns `null` (legacy path), distinct from `[]`. The lock-ordering reasoning for the deferred fail step is spelled out and is consistent with CSR-17/F-DC-03. Tested end-to-end against real PostgreSQL: `repoModeWithNoPersistedSectionsFailsTheJobInsteadOfDispatchingIt`, `noneModeStillClaimsAndRunsNormallyWithNullSystemMessages`. |
| **PMR-10** kill-switch is loud: startup WARN + `/metrics` + per-Review event | **PASS** | All three signals present: startup WARN (`GatewayProperties:153-154`, self-fixed by the developer in `8c087ff`), `/metrics` exposure (`AdminController:40-44` → `MetricsResponse.promptManagerEnabled`, ADMIN-only as before), and a `PROMPT_DISABLED` `review_events` row on **every** Review created in that mode (`ReviewService:340-345`). The event is genuinely recorded, not merely coded — QA added `ad17cb8` specifically to assert it per-Review rather than trusting the branch. |
| **PMR-11** explicit-override 404 ⇒ WARN + event + ABSENT row + metric | **PASS (metric label-less — F-PM-11)** | WARN with `kind`/`project`/**`pathLength`** and never the raw path (`PromptManager:181-182`); `PROMPT_SECTION_MISSING` event (`ReviewService:346-351`, `EventService.scrub` applied as for every other event); `ABSENT` row written for *every* looked-up-and-absent optional section, default path included, so the audit trail positively records "we looked, it was not there" (`PromptAssembler:132-135` + V3's `status` `CHECK`); review creation still returns 200/201. Default-path 404s stay silent (no WARN, no event) — the distinction PMT-07 asked for. Tested: `explicitOverridePathTypo404RecordsMissingSectionEventButStillCreatesReview`, `optionalSectionsAbsentOnDefaultPathsCreatesReviewWithoutWarnEventsOrDegradedFlag`. |
| PMR-12 (SHOULD) startup dry-run of overrides | **NOT IMPLEMENTED — tracked** | Deliberate, with the reasoning and a re-evaluation trigger recorded in-code (`GatewayProperties:138-144`). Acceptable: PMR-11's per-Review WARN + event + ABSENT row means a typo degrades *visibly*, just later than at boot. |
| **PMR-13** templated URI segments, SHA pinned, paths validated at startup | **PASS** | All four URI templates are constants with `{}` placeholders (`GitLabClientImpl:43-46`); grep finds no string concatenation into any path or host in the class. The `filePath` is expanded as a plain template variable, which Spring's default `UriBuilderFactory` strictly encodes (`/` → `%2F`) — the javadoc records that an explicit `encodePathSegment` here would double-encode to `%252F`, which is the correct call and is pinned by `fetchRawFileNestedPathIsUrlEncodedAsOneOpaqueSegment`. `commitSha` is validated against `^[0-9a-f]{40}$` **before** it can reach a URI (`:141-143`, self-fixed in `8c087ff`; tested by `fetchRawFileRejectsACommitShaNotMatchingTheExpectedShapeBeforeIssuingAnyRequest`). Configured paths are startup-validated: allowlist regex, ≤200 chars, no leading `/`, no `..` (`GatewayProperties:251-264`). Semgrep `p/java`+`p/sql-injection`: 0 findings. |
| **PMR-14** project references only, no URL/host field | **PASS (with fix)** | No URL or host field exists anywhere in `gateway.prompt.*` (the whole `Prompt` tree is reproduced in `GatewayProperties:695-961` — `project`/`ref`/paths only); the host is exclusively `gateway.gitlab.base-url`, still SR-15-validated as `https://`. `requireProjectRef` rejects schemes, `@`, `:`, `//` and (now) `..` — **F-PM-04**, fixed here. `gateway.prompt.corporate.project: "https://evil.example/x"` refuses startup, as the requirement's own test case demands. |
| **PMR-15** separate read-only credential + separate bean | **PASS — and the requirement is amended** | Separate `gitLabPromptRestClient` bean with its own `PRIVATE-TOKEN` (`RestClientConfig:56-70`); `GitLabClientImpl` uses the write client for `postDiscussion` only and the prompt client for all three reads (`:85`, `:105`, `:145`), pinned by `resolveCommitShaUsesThePromptClientNeverTheWriteClient`. `promptToken` is masked in `GitLab.toString()` (`:575`) and never logged. **On the developer's deliberate deviation — presence-only rather than ≥32 chars: the developer is right and my requirement was wrong.** SR-01's 32-character floor exists because `CI_TOKEN`/`WORKER_TOKEN`/`ADMIN_TOKEN` are *operator-chosen* values whose entropy the Gateway is the only thing policing; `GITLAB_PROMPT_TOKEN` is *GitLab-issued* and a classic project/group access token is a fixed 26 characters (`glpat-` + 20), so a ≥32 check would reject valid, correctly-scoped credentials for zero entropy assurance — a pure availability cost. `requireGitLabToken` (presence/blank only) is the right control and matches `gateway.gitlab.token`'s existing treatment. **I have amended PMR-15 and §4.4 in `docs/prompt-manager-threat-model.md` accordingly, with the rationale recorded inline.** The real residual on this requirement is the *env-only* half, which is unimplemented — **F-PM-02**. |
| **PMR-16** `followRedirects(NEVER)` + dedicated timeouts | **PASS — and QA's test is a valid transport-level proof** | `RestClientConfig:59` sets it explicitly; 3 s connect / 8 s read from `gateway.prompt.*`, independent of the publish client's 5 s/30 s. **QA's `RestClientConfigRedirectTest` is a genuine transport-layer test, not a configuration assertion** — I checked its construction specifically because that was the open question: it builds the `RestClient` by calling the **production bean method** (`new RestClientConfig(properties).gitLabPromptRestClient()`), so the real `HttpClient` and real `JdkClientHttpRequestFactory` are exercised; it stands up **two real in-process `com.sun.net.httpserver.HttpServer` instances** on different ports; the origin returns a real `302` to the other host; and it asserts both that the observed status is `302` (the raw redirect, not a followed `200`) **and** that the redirect target was **never contacted** (`wasEverContacted()` is false). Its own javadoc correctly identifies why this was needed: `GitLabClientImplTest` uses `MockRestServiceServer`, which intercepts at the `ClientHttpRequestFactory` boundary and never drives the JDK client's redirect behaviour at all. It also covers the write client — see F-PM-10 for why that one should still be pinned in code. |
| **PMR-17** streaming byte bound, not buffer-then-check | **PASS** | `BoundedInputStream` (a `FilterInputStream` counting on both `read()` overloads, throwing the moment `bytesRead > maxBytes`) wraps the raw response body inside `exchange(...)` (`GitLabClientImpl:161`, `:176-185`) — the body is never materialized into a `String`/`byte[]` first. `Content-Length`, when present, is a cheap pre-read reject (`:156-160`). Peak allocation per fetch is `maxBytes + one 8 KiB read buffer`, ×4 sections ×4 concurrency permits ≈ 4 MB worst case. Both halves tested independently: `fetchRawFileOversizedByContentLengthIsRejectedWithoutReadingBody` and — the one that actually matters — `fetchRawFileOversizedByStreamingIsRejectedEvenWithoutContentLengthHeader`. F-DC-01's mistake is genuinely not repeated. |
| **PMR-18** 429 honoured, retries bounded, publish budget protected | **PASS by construction** | I verified the developer's "zero retries" claim rather than accepting it: `grep -rn "Retry-After\|Retryable\|retryTemplate\|429" src/main/java` returns **nothing**, and neither `RestClient` bean installs a retry interceptor. So one `POST /reviews` issues **at most 6** GitLab requests, never more, and a 429 becomes a single `PromptSourceUnavailableException` → clean `502` — which is exactly the requirement's own testable assertion ("a 429 sequence produces a bounded number of requests and a clean 502"). **This does not contradict the requirement's intent.** PMT-21's actual harm was "a read-side rate-limit or abuse ban takes the *publish* path down with it", and that is closed by a different control in the same set: PMR-15's credential split means read and write consume separate per-token budgets. Honouring `Retry-After` only has meaning if something retries; adding a retry loop to gain the ability to back off would be strictly worse. Residual (unchanged, pre-existing): nothing rate-limits `POST /reviews` itself (SR-20/F03-06), so a runaway CI can still generate GitLab read load — bounded, not eliminated, by the 4-permit semaphore. |
| **PMR-19** bounded concurrency permit + wall-clock deadline ⇒ immediate 503 | **PASS** (see F-PM-09 for two second-order notes) | `Semaphore.tryAcquire()` — non-blocking, so saturation is an immediate `PromptResolutionSaturatedException` → `503` (`PromptManager:89-92`, `GlobalExceptionHandler:103-107`), never a queued Tomcat thread; released in `finally`. At most `max-concurrent-resolutions` (4) request threads can be inside prompt resolution at once, so a hung GitLab cannot starve `/jobs/claim` or `/jobs/*/heartbeat` — the property PMT-12 was about. The whole block runs outside any transaction (`ReviewService:146-148`, plain non-`@Transactional` method), so no Hikari connection or row lock is ever held across an HTTP call. Tested with a real latch-blocked thread asserting rejection in <500 ms. |
| PMR-20 (SHOULD) content-addressed cache | **NOT IMPLEMENTED — tracked** | Deliberate, documented in `PromptManager`'s class javadoc with the exact re-evaluation triggers from architecture §11. Fine at 20-30 MR/day. |
| **PMR-21** diff budget reduced by the actual system-prompt size; aggregate cap | **PASS** | `budgetTokens(systemPromptTokens)` (`DiffSizeValidator:55-60`), `assertPromptFits` throwing the distinct `PromptTooLargeException`/`422 PROMPT_TOO_LARGE` (`:69-79`), and the ordering in `ReviewService:148-151` — resolve → assert → `diffChunker.split(diff, systemPromptTokens)` — is exactly right. `rejectIfAbsurdlyLarge` correctly stays at `systemPromptTokens = 0` (it must be IO-free and run before any network call). The aggregate `max-system-prompt-tokens` check covers all sections **plus** the preamble/trailer (`PromptAssembler:116-125`), never per-section. Startup consistency check added (`GatewayProperties:216-223`) so a config where the max system prompt leaves less than `min-diff-budget-tokens` refuses to boot. Tested at the unit level *and* end-to-end: `resolvedSystemPromptSizeActuallyShrinksTheDiffBudgetAndForcesMoreChunking`, `aPreviouslyAcceptedMaxDiffNowOverflowsWhenASixThousandTokenSystemPromptIsAdded` — i.e. the requirement's own literal test case exists. |
| **PMR-22** `CHECK` on `prompt_message_format`; never `valueOf`; MULTI default | **PASS** | DB `CHECK (… IS NULL OR … IN ('MULTI','SINGLE'))` (V3:44-47). `PromptMessageFormat.fromNullable` is the only conversion path and returns `Optional`, never throws; `PromptMessageFormatter:98-109` degrades to the configured global default with a WARN that logs only the **length** of the bad value. Global default is `MULTI` (`GatewayProperties:702`), enforced at startup. Tested: `invalidBackendMessageFormatFallsBackToGlobalDefaultWithoutThrowing`, `nullBackendMessageFormatFallsBackToGlobalDefaultWithoutThrowing`, plus a real-claim override test. |
| **PMR-23** Worker wraps `systemMessages` verbatim | **PASS** | `PromptTemplateService.buildMessages:208-222`: when `systemMessages != null`, each element becomes `new ChatMessage("system", systemMessage)` with **no** `substitute()` call, **no** `{{`/`}}` stripping (that stays scoped to `chunkContext` at `:105`), and `template.system()` is skipped entirely rather than duplicated. The single-pass `Matcher`-based substitution (`:231-243`) is untouched. Five dedicated tests, including the adversarial ones the requirement named: `systemMessagesAreNeverPassedThroughSubstituteVerbatimEvenWithDiffPlaceholderLiteralText`, `systemMessagesStayVerbatimAndIsolatedFromChunkContextSubstitutionWhenBothArePresentTogether`, `systemMessagesAreNotStrippedOfLiteralBraceSequencesUnlikeChunkContext`. |
| **PMR-24** `null` ≠ `[]`; `@JsonIgnoreProperties`; Worker-side caps | **PASS** | `null` is the explicit legacy branch (falls back to the template's own `system:` block); `[]` yields zero system messages — both tested, and tested *at the wire level* too (`claimResponseWithSystemMessagesAbsentFromJsonDeserializesAsNullNotEmpty` vs `…EmptySystemMessagesArrayDeserializesAsEmptyListNotNull`), which is the distinction that actually protects against the PMT-05 claim-storm. `@JsonIgnoreProperties(ignoreUnknown = true)` added to **both** `JobPayload` and `ClaimResponse`, with `claimResponseWithUnknownFieldsFromANewerGatewayIsToleratedNotRejected` making forward compatibility a stated contract rather than a Jackson default. Worker-side caps are genuinely independent of the Gateway's: `max-system-messages = 8` (`WorkerProperties:390`, startup-validated) and the byte total folded into the existing `max-diff-bytes` check (`:148-168`) — headroom is right (MULTI emits at most 6 pieces; 6000 tokens ≈ 24 KB against a 256 KB cap). |
| **PMR-25** masked `toString()`; no content in logs/events/errors; branch name sanitized | **PASS (with fix) — one clause open** | ✔ Masked on `dto.JobPayload`, `service.dto.ClaimedJob`, Worker `JobPayload`/`ClaimResponse`, `ReviewPromptSection`, `AssembledSection`, `ResolvedSystemPrompt`; **`SectionCandidate` was the gap — F-PM-05, fixed here.** ✔ No log line, event `details` or exception message anywhere on this path carries section content: the three prompt-service log statements emit `kind`/`project`/**length** only (grep-verified), event details are `"gateway.prompt.enabled=false"` and `"kind=…"`, and every prompt exception message is a fixed string plus integers. ✖ The GitLab-supplied `default_branch` name is not Cc/Cf-sanitized — **F-PM-06** (latent: never reflected into an HTTP body, never currently logged). |
| **PMR-26** single coarse client-facing error | **PASS** | `GlobalExceptionHandler:77-82` returns a **fixed** body — `PROMPT_RESOLUTION_FAILED` / "Failed to resolve one or more prompt sources" — for every `PromptSourceUnavailableException`, discarding `ex.getMessage()` into a server-side WARN. And the throwing side cooperates: `GitLabClientImpl` collapses 404, 5xx, 401/403, network failure and timeout on the commit/project lookups into that one exception type (`:93-99`, `:113-116`), so "project not found" / "no access" / "MR not found" / "bad ref" are byte-identical to the caller — the oracle PMT-08 described does not exist. Pinned by `resolveCommitSha404IsUndifferentiatedFromOtherFailures`. |
| PMR-27 (SHOULD) project allowlist | **NOT IMPLEMENTED — tracked** | Documented in `PromptManager`'s class javadoc, correctly framed as an amplification of the pre-existing T-21/SR-16 shared-CI-token residual rather than new risk. |
| PMR-28 (SHOULD) corporate ref protected/pinned; rulebook-change event | **PARTIAL — tracked** | `DEPLOYMENT.md` carries the read-only/project-scoped token guidance; `gateway.prompt.corporate.ref` is pin-able to a tag by config. The "emit an event when a corporate `content_sha256` differs from the previous review's" half is not implemented (the data to compute it is all present — it is a query away). |
| PMR-29 (SHOULD) retention purges `content`, keeps provenance | **NOT IMPLEMENTED — tracked** | Documented on the entity, with the honest note that SR-22's retention job does not exist for the pre-existing tables either, so there is no precedent to extend. ~44k rows/yr; not urgent, but it keeps the SR-18 at-rest window open. |
| **PMR-30** `@ConfigurationProperties` only; trust decision documented | **PASS** | The trust-decision javadoc exists on `GatewayProperties.Prompt` (`:684-694`) and names PMT-24 explicitly. Grep across `src/main/java` for `Yaml`, `@RefreshScope`, `EnvironmentPostProcessor`, `ConfigDataLocation`, `readValue(new File` finds **only that javadoc's own mention** — the Gateway has no YAML parser on any path (the Worker's `new Yaml()` is classpath-only and out of scope here, unchanged). No admin endpoint mutates config: `AdminController` holds `GatewayProperties` but only *reads* `getPrompt().isEnabled()` for `/metrics`. |

---

## Non-regression set (threat model §7) — explicitly re-verified

| Control | Verdict | Evidence |
|---|---|---|
| **WSR-01** `promptVersion` allowlist untouched | **PASS** | `PROMPT_VERSION_PATTERN = ^[A-Za-z0-9._-]{1,64}$` plus the explicit `..` rejection, still the **first** thing `resolve()` does (`PromptTemplateService:96`, `:130-140`), still ahead of any resource-path construction, still logging length-only on rejection. Byte-identical to the pre-V3 logic. |
| **WSR-02 / CSR-08** single-pass substitution; `systemMessages` provably outside it | **PASS** | `substitute()` (`:231-243`) is unchanged — one `Matcher` over the template with `appendReplacement`/`quoteReplacement`, replacement text never re-scanned. `systemMessages` never reach it: `buildMessages` adds them as `ChatMessage`s directly and the `else if` branch (`:217`) means `template.system()` is not even rendered when they are present. Proven by test with a section whose literal text is `{{DIFF}}` and another containing `{{CHUNK_CONTEXT}}` — both arrive byte-identical, the diff is not duplicated into the system role. |
| **CSR-09 / CSR-10 + F-DC-02** path sanitization not weakened by sharing | **PASS** | The generalization is a pure move: `ChunkContextRenderer.sanitizePath` now delegates to `TextSanitizer.sanitizePath(rawPath, MAX_PATH_LENGTH)` and the diff shows the old body deleted verbatim. Character classes identical (Cc **including** `\n`/`\t`, Cf, Zl, Zp, `<`, `>`), `trim()` identical, `null`-when-empty identical, `capLength` identical (`max - 3` + `"..."`, `MAX_PATH_LENGTH = 300` still applied by the caller). The F-DC-02 angle-bracket strip — the actual fix — survives untouched, and `ChunkContextRendererTest` still passes unchanged. Note the deliberate asymmetry that makes sharing safe: the *section* entry point keeps `\n`/`\t` (prose needs them) while the *path* entry point still strips them (a path has no legitimate newline), so one-path-per-line structure is preserved. |
| **SR-10** templated URI segments | **PASS** | See PMR-13. Four new URI templates, all constants with placeholders; no concatenation into host or path anywhere in `GitLabClientImpl`; semgrep clean. The new `gitLabPromptRestClient` inherits the fixed `baseUrl` — it has no per-call host input at all, so it is structurally narrower than `backendProbeRestClient`. |
| **SR-11** edge body cap unchanged | **PASS** | `git diff 813ae61 HEAD -- src/main/resources/application.yml` is **empty**; `max-request-body-bytes` remains `${MAX_REQUEST_BODY_BYTES:320000}` and `RequestBodySizeLimitFilter` is untouched. (That same emptiness is what F-PM-02 is about — nothing was added either.) |
| **SR-12 / SR-14** no tokens or LLM content in logs | **PASS** | `promptToken` masked in `GitLab.toString()`; gitleaks full-history clean (64 commits); no new secret-shaped literal; `EventService.scrub` still applied to every new event type; the three new log statements emit `kind`/`project`/length only. The one new value that reaches a log unsanitized is the branch name — F-PM-06, and it is not currently logged by anything. |
| **F-DC-06** no attacker-controlled text in HTTP error bodies | **PASS** | Every new exception message is a fixed string plus integers: `"Assembled system prompt is N tokens, exceeding …"`, `"Prompt source file exceeds …"`, `"Mandatory corporate prompt source is missing: base-prompt-path"` (an operator-config term, not fetched content), `"commitSha does not match the expected ^[0-9a-f]{40}$ shape"`. No section content, no file path from a repo, no branch name, no host, no token, no stack. `PROMPT_RESOLUTION_FAILED`'s body is a literal that ignores the exception entirely. |
| **F-DC-07** masked `toString()` on every content-carrying DTO | **PASS with fix** | Six of seven were correctly masked, including the entity and the two `JobPayload`s on both sides of the wire. `SectionCandidate` was the seventh — **F-PM-05**, fixed here with four tests. |

---

## Other verifications (general SAST pass)

- **SQL injection: PASS.** The one new repository method is a derived finder
  (`findByReviewIdOrderByOrdinalAsc`); the one new `QueueManager` read uses it. No string concatenation
  into any query in the feature. V3 contains no dynamic SQL at all (unlike V2's `format('%I', …)`).
  Semgrep `p/sql-injection`: 0 findings.
- **Migration safety: PASS.** V3 is ordinary transactional DDL, no `CREATE INDEX CONCURRENTLY`, so it runs
  inside Flyway's single transaction like V1/V2. The one risky-looking statement — `ALTER TABLE
  review_events DROP CONSTRAINT ck_event_type` — is safe: V1 names that constraint explicitly
  (`V1__initial_schema.sql:141`), so the drop cannot miss, and the recreated value list is a strict
  **superset** of V1's ten values, so no existing row can fail the new check. The three `ADD COLUMN`s are
  backfill-safe (`prompt_bundle_mode NOT NULL DEFAULT 'NONE'` correctly makes every pre-V3 Review a legacy
  Review, which is exactly the semantics PMR-09 needs; `system_prompt_tokens` nullable;
  `prompt_degraded NOT NULL DEFAULT false`). The new `CHECK`s on `backends`/`reviews` are consistent with
  the defaults being backfilled.
- **Deserialization: PASS.** The three new GitLab response DTOs are private records with
  `@JsonIgnoreProperties(ignoreUnknown = true)` and no polymorphic typing. `fetchRawFile` bypasses the
  message converters entirely (raw `exchange` + `InputStream`), so no converter can be tricked into
  buffering the body before the bound applies. No new YAML/XML/object-stream parsing on the Gateway side.
- **Access control: PASS.** `ReviewPromptSectionRepository` is consumed only by `ReviewService` (insert)
  and `QueueManager` (claim-time read). **No controller references `ReviewPromptSection`**; nothing in
  `ReviewStatusResponse`/`CreateReviewResponse` exposes section content or provenance, and `/metrics` gained
  only three scalars (a boolean and two counts), still ADMIN-only. Section content leaves the process on
  exactly one path — `POST /jobs/claim` to the authenticated WORKER-role claimant — i.e. the same
  boundary the diff already crossed (PMT-23, accepted).
- **Concurrency: PASS.** `PromptManager` is stateless apart from the `Semaphore` (constructed once,
  `tryAcquire`/`release` in `finally`); `PromptSourceResolver`, `PromptAssembler`, `PromptMessageFormatter`
  and `TextSanitizer` hold no mutable state; the per-request `ArrayList`/`StringBuilder` locals never
  escape (the `codePoints().forEach` lambdas are sequential streams over a local builder). Prompt
  resolution takes no lock and holds no connection. The extra claim-time `SELECT` does run inside the
  job-row-lock transaction, adding ≤4 rows / ≤24 KB to the lock hold — measurable but negligible against
  the `lock_timeout = 3s` bound, and it cannot block on anything (indexed read on a table nothing else
  writes after creation).
- **Benign create-time race (not a finding, worth recording):** the dedup lookup is deliberately unlocked,
  so two concurrent `POST /reviews` for the same key can both run a full resolution (≤6 GitLab calls each)
  before one loses on the unique violation and is correctly re-read as deduplicated
  (`ReviewService:163-170`). Wasted calls only, bounded by the 4-permit semaphore; no state divergence,
  since the loser's sections are rolled back with its transaction.
- **State machine / lifecycle: PASS.** The PMR-09 fail path uses only legal transitions
  (job `RUNNING → FAILED` after the parent has legally reached `RUNNING`), goes through `JobStateMachine`
  like every other mutation, and does **not** consume a retry attempt for a condition retrying cannot fix.
  A Gateway crash between the claim commit and the fail step leaves the job `RUNNING` with no worker —
  recovered by the existing heartbeat sweep, i.e. degraded gracefully into a pre-existing mechanism.
- **Secrets: PASS.** gitleaks full history clean; one new configuration key (`gateway.gitlab.prompt-token`),
  masked; no new secret-shaped literal in code or tests (test tokens are obvious repeats).
- **Dependencies: PASS.** `pom.xml` and `worker/pom.xml` are byte-identical to the previous verified-clean
  baseline — no new direct or transitive dependency, no version change. The feature is built entirely on
  the JDK and Spring surface already present (`Semaphore`, `FilterInputStream`, `MessageDigest`,
  `HexFormat`, `CharsetDecoder`), exactly as architecture §2 promised.

## CI-gate posture for this branch

The `security-gate.yml` gate would be **green**: gitleaks clean, semgrep ERROR-severity clean, SCA
unchanged from a passing baseline, both `mvn verify` jobs pass. As with the previous feature, **none of
the findings above is machine-detectable by the configured gate** — F-PM-01 is a missing alternative in a
multi-catch, F-PM-02 is a three-way disagreement between a properties class, a YAML file and a Markdown
doc, F-PM-03 is a budget-attribution decision. That division of labour is expected; recording it again so
a green gate is not mistaken for evidence against these findings.

The two Semgrep rules the threat model suggested adding while the feature was in flight are **no longer
worth writing**: (a) "flag `String.replace(` on a prompt delimiter" has no target, because the delimiter
is a single stripped code point and no multi-character token is replaced anywhere; (b) "flag non-templated
URI construction in `GitLabPromptClient`" is subsumed by the fact that all four templates are `static
final` constants — a lint rule would be checking a property that is already structurally true. Better
value for the same effort: a test that boots the real `application.yml` property set (which would have
caught F-PM-02 mechanically).

---

## Must-fix / must-do list

- **AppSec must-fix before merge (back to backend-developer):**
  1. **F-PM-02 (Medium)** — `application.yml` never grew a `gateway.prompt` block; the stock deployment
     refuses to start and both documented environment variables (`GITLAB_PROMPT_TOKEN`,
     `PROMPT_MANAGER_ENABLED`) bind to nothing. Includes a deliberate, documented decision on the shipped
     default for `gateway.prompt.enabled`.
- **Should-fix in the same pass (cheap, same files):**
  - **F-PM-03 (Low)** — make an oversized *optional* project section degrade rather than 422, without
    weakening PMR-21's no-silent-truncation rule for corporate sections.
  - **F-PM-06 (Info)** — sanitize the resolved `default_branch` at its single entry point.
  - **F-PM-10 (Info)** — pin `followRedirects(NEVER)` on `gitLabRestClient` too (one line).
- **Requirement-amendment candidates (decide, then either implement or amend the threat model):**
  F-PM-07 (dead `max-sections` key vs. schema-delegated bound), F-PM-08 (re-derive the `PROJECT_*`
  delimiting from `kind` at claim time), F-PM-09 (deadline breach absorbed by `SKIP_OPTIONAL`),
  F-PM-11 (assembled-output hash; metric label dimensions).
- **Tracked SHOULDs, not blocking:** PMR-12 (startup dry-run), PMR-20 (content-addressed cache),
  PMR-27 (project allowlist), PMR-28 (rulebook-change event), PMR-29 (retention purge). All five are
  documented in-code with explicit re-evaluation triggers, which is the right way to carry them.
- **Operational must-do at go-live:** the PMR-07 `GRANT SELECT, INSERT ON review_prompt_sections` block
  (`DEPLOYMENT.md:209`) is a runbook step with no automated enforcement — add a `\dp` verification step to
  the deploy checklist, as already applies to `review_events` under SR-19.
- **Carried forward unchanged (still accepted, not re-litigated here):** F03-01 (DNS-rebinding TOCTOU),
  F03-02 (`BACKEND_ALLOWED_HOST_PATTERN`), F03-03 (uncapped claim/heartbeat bodies), F03-04 (self-declared
  worker identity), F03-05 (`sslmode=require`), F03-06/SR-20 (no rate limiting — now also the only thing
  bounding GitLab read volume, see PMR-18), PMT-08's residual cross-project reach under the shared CI
  token, PMT-23 (sections reach any worker-token holder).

## Bottom line

The Prompt Manager is a well-built implementation of a genuinely difficult security design, and the
pre-implementation threat model paid off: the architectural correction that mattered most (PMT-02, the
MR-author-chosen target branch) was not merely mitigated but designed out — that code path does not exist
— and the two hard-won lessons from the previous feature, F-DC-01's "bound while reading" and F-DC-02's
"strip the character class, never a multi-character token", were both correctly *generalized* rather than
re-learned. 23 of 25 blocking MUSTs are fully closed, injection/authz/deserialization/migration/dependency
surfaces are clean, and the fail-closed behaviour at claim time is real and tested against a real database.

It is not mergeable yet, for one unglamorous reason: **the feature has no configuration**. The stock
`application.yml` never grew a `gateway.prompt` block, so a stock Gateway refuses to boot and the two
environment variables the deployment guide tells operators to use do nothing — and the natural workaround
for an un-bootable Gateway is to hard-code the kill-switch off, which is precisely the silent-control-loss
scenario PMT-22 exists to prevent. Alongside that, the `SKIP_OPTIONAL` control did not cover the failure
class an ordinary developer is most likely to trigger (fixed here, with tests).

**NEEDS A DEV PASS** for F-PM-02 (+ F-PM-03/06/10). Given that the three fixes I landed are covered by
new regression tests and that the remaining items are configuration and defense-in-depth rather than
exploitable defects, **a full third SAST round is not warranted** — a targeted verification of F-PM-02/03/
06/10 plus a green run of both suites is sufficient before merge.

---

## Fixes applied by appsec in this round

Committed separately from this report, each with regression tests, all 516+ Gateway tests re-run:

| Finding | Change | Tests added |
|---|---|---|
| F-PM-01 | `PromptManager.doResolve` catch widened to `PromptSourceUnavailableException \| PromptSourceInvalidException` | `PromptManagerTest`: `invalidOptionalProjectSectionIsSkippedUnderSkipOptional`, `invalidOptionalProjectSectionStillFailsHardUnderFail`, `invalidCorporateSectionAlwaysFailsEvenUnderSkipOptional` |
| F-PM-04 | `GatewayProperties.requireProjectRef` now rejects `..` | `GatewayPropertiesPromptValidationTest`: `corporateProjectRefWithDotDotSegmentRefusesStartup`, `overrideProjectRefWithDotDotSegmentRefusesStartup`, `projectRefWithSingleDotsIsStillAccepted` |
| F-PM-05 | Masked `toString()` on `PromptAssembler.SectionCandidate` | `SensitiveDtoToStringMaskingTest`: `sectionCandidateToStringNeverContainsRawSectionContent`, `sectionCandidateMaskingSurvivesListAndFormatInterpolation`, `sectionCandidateAccessorStillReturnsRawContentUnmasked` |
| PMR-15 | Requirement amended in `docs/prompt-manager-threat-model.md` (§5 PMR-15 + §4.4): presence check, not ≥32 chars, for GitLab-issued tokens — confirming the developer's deviation was correct | n/a (documentation) |

No other production code was touched. The six throwaway probes were run under `src/test` and deleted;
`git status` was verified clean of them afterwards.

### Post-fix suite results (run by me)

| Run | Result |
|---|---|
| Gateway `mvn -o test` (pre-fix baseline) | `Tests run: 516, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS |
| Worker `mvn -o -f worker/pom.xml test` | `Tests run: 125, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS (untouched by this round) |
| Gateway, post-fix, run #1 | `Tests run: 525, Failures: 1` — the single failure is **F-PM-12**, the pre-existing flaky concurrency hammer, unrelated to any change here |
| `ClaimCancelObsoleteConcurrencyTest` in isolation | `Tests run: 2, Failures: 0` — BUILD SUCCESS |
| Gateway, post-fix, run #2 | **`Tests run: 525, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |

525 = 516 + the 9 regression tests added with the three fixes above.

---
---

# Round 2 — final verification / release gate

**Scope:** `ebc598c..eb13bd3` — the backend-developer fix round: 7 commits, 20 files, +649/−60, plus one
follow-up commit of my own (below). `git diff ebc598c HEAD -- worker/` is **empty** and
`git diff 813ae61 HEAD -- pom.xml worker/pom.xml` is **empty**.

**Method:** every commit read as its actual diff *and* as the resulting file state — commit messages
were treated as claims to check, not as evidence. Both suites, semgrep (the exact SR-23 gate config) and
gitleaks (full history) were re-run by me. Specifically hunted for fix-induced defects: a widened `catch`
swallowing more than intended, a sanitizer applied where it changes an outbound identifier, a claim-time
cap that can silently drop a mandatory row, a "flaky test fix" that is really a relaxed assertion, and a
config default that disagrees with its own properties class.

## Fix-by-fix verdicts

| Finding | Sev | Fix commit | Verdict |
|---|---|---|---|
| **F-PM-02** | Medium (release blocker) | `be4722a` | **CONFIRMED** |
| **F-PM-03** | Low | `a36459a` | **CONFIRMED** (+ one log-accuracy nit fixed by me) |
| **F-PM-06** | Info | `632a433` | **CONFIRMED** |
| **F-PM-10** | Info | `293e160` | **CONFIRMED** |
| **F-PM-07** | Info | `e67c448` | **CONFIRMED** for the cardinality cap; byte-total clause closed as an accepted deviation |
| **F-PM-08** | Info | `e67c448` | **CONFIRMED** (one residual recorded, DB-write precondition) |
| **F-PM-09 / F-PM-11** | Info | `5b7186f` | **CONFIRMED as deferrals** — which is how they were classified; no behavior change, in-code triggers |
| **F-PM-12** | Info (test) | `eb13bd3` | **CONFIRMED**, and the fix is the right kind — nothing was relaxed |

### F-PM-02 — `application.yml` wiring + shipped default (Medium, release blocker) — CONFIRMED

- The full `gateway.prompt.*` tree is in `src/main/resources/application.yml`, env-overridable throughout,
  plus `gateway.gitlab.prompt-token: ${GITLAB_PROMPT_TOKEN:}`. `PROMPT_MANAGER_ENABLED`,
  `PROMPT_CORPORATE_PROJECT`, `PROMPT_CORPORATE_REF`, the two corporate path vars and `PROMPT_ON_ERROR`
  now genuinely bind.
- The Java default was flipped `true → false`. I did not stop at that one key: I diffed **every** default
  in the `Prompt` tree against the YAML — `enabled`, `corporate.ref`, both corporate paths,
  `project.enabled`, both project paths, `error-handling.on-error`, `message-format`,
  `section-separator`, all five `limits.*`, all three timeouts — and they agree value for value. The
  defect *class* (properties class and YAML disagreeing, F-DC-04 one feature earlier) is closed for the
  whole subtree, not just the key that surfaced it.
- **`ApplicationYamlBootTest` really does boot the production YAML**, and I verified that mechanically
  rather than by reading the file path: `spring.config.location=file:${user.dir}/src/main/resources/application.yml`
  *replaces* the default `classpath:/application.yml` location, so `src/test/resources/application.yml`
  cannot be what is read — and the test's own assertions make this a proof by contradiction.
  `promptManagerOptedInWithItsOwnDocumentedEnvVarsAlsoBootsCleanly` asserts `enabled == true` under
  `PROMPT_MANAGER_ENABLED=true`, which is unsatisfiable **(a)** against the test YAML, which pins
  `gateway.prompt.enabled: false` as a literal with no placeholder, and **(b)** with no YAML at all, since
  the Java default is `false` and `PROMPT_MANAGER_ENABLED` is not a relaxed-binding spelling of
  `gateway.prompt.enabled` (that would be `GATEWAY_PROMPT_ENABLED`). The corporate-project assertion is
  unsatisfiable the same two ways. Both states are covered — opted-out (stock) and opted-in — plus
  opted-in-without-a-token still failing fast, which is the guard that keeps the fix from turning into a
  silent no-op.
- `DEPLOYMENT.md` no longer contradicts the code: §2 is rewritten around off-by-default with an opt-in
  checklist (token + `PROMPT_CORPORATE_PROJECT` + the optional `PROMPT_ON_ERROR`), and both env-file
  appendices (§4.1, §10.1) carry the three variables commented out. Grep-verified: no remaining sentence
  claims `enabled` defaults to `true`.
- **On the deployment-policy call itself** (shipping `false`, deviating from the architecture doc's
  `${PROMPT_MANAGER_ENABLED:true}` example): I agree with it, and it is the choice I would have made.
  Upgrade-safety wins here precisely because "off" is *not* silent in this design — PMR-10 makes it loud
  in all three channels, and the startup WARN now fires on the **default** path rather than only on a
  deliberate opt-out. **Runbook consequence, not a finding:** the feature ships inert, so go-live must
  include actually setting `PROMPT_MANAGER_ENABLED=true` once the corporate prompt repo exists —
  otherwise the control this branch exists to add is present in the code and absent in production.

### F-PM-03 — oversized *optional* project section (Low) — CONFIRMED

- The developer took the strong form, not the cheap one. `PromptAssembler.assemble` still throws
  unconditionally (behavior unchanged, and the reason is now on its javadoc); `PromptManager` is the only
  place that *interprets* the throw, and it degrades **only after a corporate-only re-assembly
  demonstrably fits**. A corporate-only overflow therefore cannot be absorbed, by two independent paths:
  `!hadProjectContent` rethrows immediately, and the attribution re-assembly's own throw propagates.
- That second path is the one a weak fix would have gotten wrong (dropping the project sections *first*
  and never re-checking), and it is pinned by a test that exists specifically to catch it —
  `oversizedCorporateContentAloneAlwaysFailsEvenWhenAProjectSectionIsAlsoPresent`. PMR-21's
  "never silently truncate the corporate rulebook" is intact.
- `on-error=FAIL` is unaffected (rethrow), and the degradation is auditable: `degraded=true` flows
  `ResolvedSystemPrompt` → `PromptResolution` → `review_inputs.prompt_degraded`.
- **Nit found and fixed by me** (commit `ac4481c`, no behavior change): the WARN was emitted *before* the
  attribution re-assembly, so a corporate-only overflow logged "dropping them and proceeding with the
  corporate rulebook only" and then 422'd — describing an outcome that never happened, in the one line an
  operator reads while diagnosing exactly that failure. Moved after the successful re-assembly.

### F-PM-06 — GitLab-supplied `default_branch` sanitization (Info) — CONFIRMED

- One entry point (`resolveDefaultBranch`, grep-confirmed as the only caller of the project-lookup
  endpoint), the **shared** `TextSanitizer.sanitizePath(…, 200)` — not a second implementation.
  `TextSanitizer` itself does not appear in this round's diff at all, which is exactly the outcome the
  finding asked for.
- Sanitized *before* it is persisted as `source_ref`, before any `toString()` rendering, and before it
  keys the subsequent commit-SHA/file fetches — sanitized at the boundary, not masked at each renderer.
  The 200-char cap sits below `source_ref VARCHAR(256)`, which closes the secondary
  `DataIntegrityViolationException`-misread-as-dedup-race path the finding named.
- Fails **closed** when nothing publishable survives (`PromptSourceUnavailableException`) rather than
  returning `null`/`""` — that was the specific failure mode worth checking in a sanitize-at-boundary fix.
  Three tests, including a Trojan-Source U+202E case and the sanitizes-to-nothing case.
- **Accepted residual (Info, no action):** git permits `<`/`>` in ref names and `sanitizePath` strips
  them, so a branch literally named `feat<x>` would be looked up under a ref GitLab does not have →
  `PromptSourceUnavailable` (502, or a degrade under `SKIP_OPTIONAL`). Fails closed, effectively
  unreachable in practice, and the alternative — a branch-specific sanitizer — would re-fork the shared
  helper this very finding asked the code to converge on.

### F-PM-10 — `followRedirects(NEVER)` on the write client (Info) — CONFIRMED

All three `HttpClient` beans now pin it explicitly (`RestClientConfig` `gitLabRestClient:37`,
`gitLabPromptRestClient:65`, `backendProbeRestClient:84`). QA's transport-level test now guards a stated
contract instead of a JDK default, and its javadoc was updated to say so rather than left describing the
old situation.

### F-PM-07 — claim-time `max-sections` (Info) — CONFIRMED (cardinality); byte-total accepted as delegated

- `max-sections` is no longer dead config: enforced in `PromptMessageFormatter.render`, ordinal-ascending
  so `CORPORATE_*` survives truncation. The part that actually matters is that the truncation cannot open
  a hole — dropping below the mandatory pair falls straight into the pre-existing PMR-09 fail-closed
  check, pinned by `rowCountOverMaxSectionsThatDropsBelowMandatoryCorporateStillFailsClosed`.
- PMR-08's other clause (a total-assembled-byte cap at claim time) is **not** implemented, and I am
  closing it as an accepted deviation rather than carrying it as a follow-up: the bound already exists
  twice — at create time (the 6000-token aggregate, which is what actually sizes the payload) and,
  independently of the Gateway, at the Worker (`max-system-messages = 8` plus `systemMessages` bytes
  folded into `max-diff-bytes`). A third check on the same value in between would add config surface
  without adding a bound.

### F-PM-08 — delimiter wrapping re-derived from `kind` at claim time (Info) — CONFIRMED

- Claim-time rendering now re-derives the PMR-02 wrapping via `PromptAssembler.isDelimited` /
  `delimitedBlock` instead of trusting what was stored: an unwrapped `PROJECT_*` row is re-wrapped with a
  WARN, an already-wrapped one is not double-wrapped (both tested). Driven by `kind`, not by insertion
  order or stored text — the property the finding asked for.
- **Residual, recorded not fixed (Info):** the re-wrap path does not re-sanitize the body, and
  `isDelimited` only checks the outer prefix/suffix, so a row whose stored content carries an *interior*
  U+241E sequence could still forge a boundary. Reaching that state requires direct DB write access to
  `review_prompt_sections` — the only in-process writer is `PromptAssembler`, downstream of
  `TextSanitizer`, and there is no dynamic SQL anywhere on this path — i.e. an attacker who already owns
  the database, at which point the corporate sections themselves are editable and this is the lesser
  problem. If it is ever cheap: `delimitedBlock(kind, textSanitizer.sanitizeSectionText(body))` on both
  branches would make the rendered output structurally *exactly* two delimiter lines regardless of row
  state. I deliberately did not take the constructor churn for it at the release gate.

### F-PM-09 / F-PM-11 — deferred design decisions (Info) — CONFIRMED as deferrals

No behavior change (verified: the diff is javadoc/comments in four files). Each deferral carries the
project's existing convention — the decision, why it is a policy call rather than a defect fix, and an
explicit re-evaluation trigger. F-PM-09's second half (the `total-timeout + read-timeout` worst case) is
now stated on `Prompt#totalTimeout`; that was the only part of it that was a documentation gap rather
than a design choice. I agree with deferring the first half: making a deadline breach hard-fail under
`SKIP_OPTIONAL` narrows an availability guarantee for production traffic, which belongs in a deliberate
change, not in a fix-round.

### F-PM-12 — flaky concurrency test (Info, test defect) — CONFIRMED, and the fix is sound

I checked specifically for the failure mode this task named — a flaky test "fixed" by weakening it. **It
is not that.** No timeout was raised, no assertion weakened, no status added to `recordIfUnexpected`'s
whitelist. The only change is the test backend's capacity (`10` → `iterations * 3 + 10` = 34), which
removes the unrelated confound (leaked `RUNNING` jobs starving the dispatcher, so `/jobs/claim` correctly
answered `204`) so the test can only fail for the reason it names. Every real assertion — no deadlock
500s, no unexpected statuses, every chunk claimable — is intact and now actually exercised, and the
arithmetic is written down in a comment so it stays fixed. This is option 1 of the three I offered;
option 3 (assert on the RUNNING count so the real cause is reported) would still be better diagnostics,
but is not required. The test was green in both of my full-suite runs.

## Findings open after this round

**None blocking.** All Info, all recorded here or in-code with triggers: F-PM-07's byte-total clause
(accepted deviation), F-PM-08's interior-delimiter residual (DB-write precondition), F-PM-06's
`<`/`>`-in-a-branch-name residual (fails closed), F-PM-09 / F-PM-11 (deferred), PMR-12/20/27/28/29
(tracked SHOULDs), PMR-07's grant-verification runbook step, and the pre-existing carried-forward set
(F03-01…F03-06, PMT-08's shared-CI-token residual, PMT-23).

**New findings this round: none.** Nothing the fix round missed, and nothing it got wrong.

## PMR delta vs. round 1

| PMR | Round 1 | Now |
|---|---|---|
| **PMR-08** | PARTIAL (claim-time cap unimplemented, dead config key) | **PASS** — cardinality enforced at claim time; byte total accepted as delegated (F-PM-07) |
| **PMR-15** | PASS, but the env-only clause was literally unimplemented | **PASS** — `GITLAB_PROMPT_TOKEN` → `gateway.gitlab.prompt-token` binds for real |
| **PMR-16** | PASS (write client inherited the JDK default) | **PASS** — both GitLab clients pin `NEVER` explicitly |
| **PMR-25** | PASS with one clause open (`default_branch`) | **PASS** — sanitized at its boundary |
| **PMR-21** | PASS | **PASS** — unchanged; the `SKIP_OPTIONAL` degradation was added *without* weakening "never silently truncate the corporate rulebook" (proven by the attribution tests) |
| **PMR-07 / PMR-11** | PARTIAL | PARTIAL, unchanged and **accepted** (assembled-output hash, metric label dimensions — deferred with triggers) |
| PMR-12/20/27/28/29 (SHOULD) | tracked | tracked, unchanged |

**All 25 blocking MUSTs are now closed** (round 1: 23 of 25).

## Non-regression set — explicitly re-verified at HEAD

The starting point that makes this both cheap and reliable: `git diff ebc598c HEAD -- worker/` is
**empty**, so every Worker-side control carries over by construction rather than by re-reading, and
`pom.xml`/`worker/pom.xml` are byte-identical to the verified-clean baseline.

| Control | Verdict | Evidence |
|---|---|---|
| **WSR-01** `promptVersion` allowlist | **PASS** | `PROMPT_VERSION_PATTERN` + the explicit `..` rejection unchanged (`PromptTemplateService:57`, `:132-133`); Worker tree untouched this round. |
| **WSR-02 / CSR-08** single-pass substitution, `systemMessages` outside it | **PASS** | `substitute()` unchanged; `systemMessages` still become `ChatMessage`s directly, never entering the matcher. Worker tree untouched. |
| **CSR-09 / CSR-10 + F-DC-02** path sanitization | **PASS** | `TextSanitizer` and `ChunkContextRenderer` are both **absent from this round's diff** — F-PM-06 *consumed* the shared helper instead of editing or forking it, which is precisely what the finding asked for. |
| **SR-10** templated URI segments | **PASS** | The four URI templates are unchanged constants; the one new value reaching a path (the sanitized `default_branch`) is still a template variable, never concatenated. semgrep `p/java`+`p/sql-injection`: 0 findings. |
| **SR-11** edge body cap | **PASS** | This round's `application.yml` diff is **+43/−0** — nothing removed or altered; `max-request-body-bytes` (320000 / 500000) untouched and `RequestBodySizeLimitFilter` is not in the diff. |
| **SR-12 / SR-14** no tokens or content in logs | **PASS** | The three new log statements emit: an int pair (row count / cap), a `kind` enum, and one fixed string. No section content, no repo path, no token, no branch name. `promptToken` still masked in `GitLab.toString()`; gitleaks clean over the full history including the new commits. |
| **F-DC-06** no attacker-controlled text in HTTP error bodies | **PASS** | The one new exception message ("GitLab project lookup returned a `default_branch` with no publishable content") is a `PromptSourceUnavailableException`, whose handler discards `getMessage()` and returns the fixed `PROMPT_RESOLUTION_FAILED` body. No new message interpolates external input. |
| **F-DC-07** masked `toString()` on content-carrying DTOs | **PASS** | No new content-carrying DTO; `SectionCandidate`/`AssembledSection`/`ResolvedSystemPrompt` masking intact (`PromptAssembler`'s diff is the `isDelimited` helper plus javadoc). |

## Scanners and suites — run by me in this round

| Check | Result |
|---|---|
| **semgrep**, the exact SR-23 gate config (`p/java` + `p/sql-injection` + `p/secrets`, `--error --severity ERROR`) over `src/main/java` + `worker/src/main/java` | **0 findings** — 147 files, ~100 % parsed, 56 rules run |
| **gitleaks** `git --redact -c .gitleaks.toml` over the **full history** (73 commits) | **no leaks found** |
| **SCA** | `pom.xml` / `worker/pom.xml` byte-identical to the verified-clean baseline — no new direct or transitive dependency; previous verdict carries |
| Gateway `mvn -o test` at `eb13bd3` | **`Tests run: 540, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |
| Worker `mvn -o -f worker/pom.xml test` | **`Tests run: 125, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |
| Gateway `mvn -o test` after my `ac4481c` log fix | **`Tests run: 540, Failures: 0, Errors: 0, Skipped: 0`** — BUILD SUCCESS |
| `ClaimCancelObsoleteConcurrencyTest` | green in **both** full-suite runs — F-PM-12's intermittent failure did not reproduce |

540 = 525 (round-1 post-fix) + the 15 regression tests the fix round added (3 boot, 4 too-large, 3
default-branch, 3 max-sections, 2 delimiter re-derivation).

**SR-23 CI gate posture: green** on all six jobs. As in round 1, none of this round's findings was
machine-detectable by the gate — but F-PM-02 now *is*, because `ApplicationYamlBootTest` turned it into a
mechanical check. That is the single most valuable artifact this fix round produced: the next time
someone adds a config key to the properties class and forgets the YAML, a test fails instead of a
deployment.

## Verdict: **READY TO MERGE**

Every finding from round 1 is closed, deferred with an explicit in-code trigger, or accepted as a
documented deviation — and each fix was verified against the code, not the commit message. The two fixes
that could plausibly have been done in a way that *looked* right and was not — F-PM-03's degradation
(could have quietly swallowed a corporate-content overflow) and F-PM-12's flaky test (could have been a
relaxed assertion) — were both done in the strong form, with the adversarial case explicitly pinned by a
test. The blocker, F-PM-02, was fixed *and* the defect class behind it was closed for the whole property
subtree, with a boot test that makes the next occurrence a failing build rather than a failed deploy.

No further appsec round is required before merge. Two things belong on the **go-live checklist**, not on
the merge decision:

1. **The feature ships inert.** `PROMPT_MANAGER_ENABLED` defaults to `false`; production must set it to
   `true` (with `GITLAB_PROMPT_TOKEN` + `PROMPT_CORPORATE_PROJECT` provisioned) or the control does not
   exist in production. The startup WARN, `/metrics` and the per-Review `PROMPT_DISABLED` event are what
   keep that visible — verify they are actually being read.
2. **PMR-07's grant step** (`GRANT SELECT, INSERT ON review_prompt_sections`, `DEPLOYMENT.md:229`) has no
   automated enforcement; add the `\dp` verification step to the deploy checklist, as already applies to
   `review_events` under SR-19.
