# AppSec SAST Report — `feature/structured-review-output` (V5: decoder-constrained, coverage-enforced LLM responses)

Scope: `1957f2b..HEAD` (`35509a9`) on `feature/structured-review-output` — 20 commits (3 docs:
`5f673dc` architecture → `6a70410` threat model → `4ab6c13` architecture amendment; 11 dev commits
`156ce89`…`aed377c`; 6 QA commits `4690c6e`…`35509a9`), 90 files, **+5558/−201**. Working tree carries
one pre-existing, unrelated **uncommitted** `README.md` change (+58 lines, a manual-CI-submission
recipe) which I did not touch and which is discussed under F-SRO-05.

In scope (Gateway): `service/ReviewSchemaBuilder`, `service/DecoderConstraintRenderer`,
`service/StructuredResponseParser`, `service/CommentRenderer` (incl. `sanitizeCodeBlock` and the
diff-context extractor), `service/StructuredPathValidator`, `service/StructuredOutputSupport`,
`service/ChunkContextRenderer.renderStructured`, `service/DiffChunker` (SRO-14/66a/66b),
`service/ReviewService` (edge validation + `promptVersion` allowlist), `service/QueueManager`
(claim-time coverage list, schema/constraint wiring, the two new fail-closed paths),
`service/ResultProcessor` (structured phase 1, SRO-36 retry wiring, SRO-68 fallback),
`service/CommentParser` (`parseStructuredFallback`, `sanitizeProseText`, the SRO-45 counter),
`service/MetricsCounters`, `config/GatewayProperties` (`structured`/`review` subtrees + the five-point
startup validation), `model/enums/{StructuredOutputMode,FinishReason,OnInvalidResponse}`,
`model/{Backend,ReviewResult}`, `dto/{JobPayload,SubmitResultRequest,MetricsResponse}`,
`service/dto/{ClaimedJob,SubmitResultCommand,MetricsSnapshot}`,
`exception/StructuredOutputUnsupportedException` + its `GlobalExceptionHandler` mapping,
`db/migration/V5__structured_review_output.sql`. On the Worker side: `llama/DecoderConstraintResolver`,
`llama/DecoderConstraint`, `llama/dto/ChatCompletionRequest`, `llama/LlamaClient`, `core/WorkerLoop`,
`gateway/dto/{JobPayload,ResultRequest}`, `config/WorkerProperties.Limits`, `error/JobFailureReason`,
`resources/prompts/v3.yml`.

Method: independent re-verification of my own pre-implementation threat model
(`docs/structured-review-output-threat-model.md`) against the code that was actually built — reading and
tracing shipped code, never the architecture doc's intentions, never the developer's or QA's summaries.
Every **CRITICAL** requirement (`SOR-06`–`SOR-13`) re-derived from source; every **TRACKED** requirement
(`SOR-14`–`SOR-23`) — deferred to this round by design — actually verified here rather than restated;
the three accepted residuals (`SOR-INH-1/2/3`) re-checked as still-holding-as-stated. Plus a general
SAST pass (injection, access control, resource exhaustion, information disclosure, error handling,
logging discipline, migration safety, dependency delta, config drift), plus the three items QA
explicitly escalated (duplicate-key gap, the stale-Hibernate-cache bug class, the two flaky tests),
plus my own independent read of the three deliberately-dangerous new surfaces (`sanitizeCodeBlock`,
`StructuredPathValidator`, the Worker constraint wiring).

**Suites (run by me, this session, from the branch HEAD):** Gateway `mvn -q test` → exit 0,
`Tests=776, Failures=0, Errors=0, Skipped=0` (surefire aggregate over 160 report files). Worker
`mvn -q -f worker/pom.xml test` → exit 0, `Tests=156, Failures=0, Errors=0, Skipped=0`. Both flaky
tests QA flagged passed in my run; see the dedicated section below.

**Scanners (run by me):** `semgrep --config p/java --config p/sql-injection --config p/secrets` (the
exact gate config from `.github/workflows/security-gate.yml`) over `src/main/java` +
`worker/src/main/java` → **0 findings**, 167 files, 96 rules, ~100% parse rate. `gitleaks git
--redact -c .gitleaks.toml` over the full history (122 commits, 3.20 MB) → **no leaks found**. SCA:
`pom.xml` and `worker/pom.xml` have a **zero-byte diff** vs. the merge base — no dependency delta to
analyse (the architecture's §0.3 "no new dependencies" claim is literally true).
`.github/workflows/security-gate.yml` and `docker-compose.yml` are untouched, so the SR-23 gate covers
this branch unchanged.

**Empirical work done for this round:** I compiled and ran a standalone Jackson 2.17.2 probe (the exact
version on this classpath) to settle the `STRICT_DUPLICATE_DETECTION` question QA escalated rather than
reason about it from documentation — results in F-SRO-01.

---

## Verdict: **NEEDS ONE MORE DEV PASS** — 4 Medium + 1 Low block the merge.

Severity counts: Critical 0 · High 0 · **Medium 4** · **Low 6** · Info 8.

This is a strong branch and the hardest parts are right. All eight CRITICAL requirements are met in
substance: the Worker-side constraint wiring (`SOR-06`/`SOR-07`) is the cleanest untrusted-blob
pass-through in the repo — a byte-measured pre-parse bound, typed `JsonNode` fields on
`ChatCompletionRequest` rather than the rejected `Map` overlay, no path through `substitute()`, no
Jackson message ever logged; the `promptVersion` allowlist (`SOR-08`) is the first thing
`createReview` does, defaulting to `v1,v2`; the schema is built exclusively from a Jackson `ObjectNode`
tree with zero string templating (`SOR-05a`); `StructuredResponseParser` genuinely has no `Backend`/
`StructuredOutputMode` reference and treats an empty expected set as an `IllegalStateException`, not a
pass (`SOR-11`/SRO-67c); the SRO-36 lock ordering is implemented exactly as specified (job left
`RUNNING`, `RetryManager` called from the non-transactional `process` after phase 1 commits); the
V5 migration is two nullable columns with a `lock_timeout` and a genuine rollback-tolerance story
(`SOR-20`); and the metrics are keyed only on closed Gateway enums (`SOR-21`).

What blocks is that **three of the feature's own guarantees can still be defeated silently, and a
fourth is not wired up at all**:

1. **F-SRO-01 (Medium).** `STRICT_DUPLICATE_DETECTION` is off, so a duplicate `files` key (or a
   duplicate per-file key) makes Jackson discard the first object entirely and the coverage check
   validate the survivor — zero findings published, job `COMPLETED`, no error, no counter, no signal.
   QA pinned this behavior in tests; I confirmed the Jackson mechanics empirically. This is the
   flagship control degrading to *no control at all while reporting success* — precisely the
   `SOT-02`/`SOT-04` class the threat model rated BLOCKING — and it is reachable in the shipped
   default configuration (`default-mode: OFF`, i.e. no decoder constraint).
2. **F-SRO-02 (Medium).** SRO-17's dropped-path check counts sizes, not distinct values, so two
   *different* real paths that **collide after sanitization** (e.g. `a.java` and `a<>.java`, or `a.java`
   and ` a.java`) both survive and are persisted as one duplicated key in `review_chunks.file_paths`.
   The prompt block dedupes; the schema's `required` array does not; the validator's expected *set*
   dedupes but its iteration list does not. Net: one file is silently uncovered, its comments are
   published twice, and the JSON Schema handed to a third-party grammar compiler contains a duplicated
   `required` entry. An MR author picks this by naming a file — the exact suppression primitive
   `SOR-01` was written to close.
3. **F-SRO-03 (Medium).** `gateway.structured.answer-reserve` (8000) and the computed coverage header
   reserve exist **only in the startup validation**. At runtime `DiffChunker.split` still sizes every
   chunk with `gateway.diff.answer-reserve` (4000) and `chunk-header-reserve-tokens` (256). SRO-64d and
   the runtime half of `SOR-13` are simply not implemented, so the branch ships a startup check that
   asserts a budget the code never uses.
4. **F-SRO-04 (Medium).** The structured result path drops `fairShareCommentCap` (SRO-33) and renders
   every finding — up to 800 per result — *before* any cap, each render re-splitting the whole chunk
   diff, all inside the phase-1 job-row-locked transaction. `SOR-18` asked for exactly this not to
   happen.
5. **F-SRO-05 (Low, but blocking by this repo's own rule).** `README.md`, `DEPLOYMENT.md`,
   `worker/README.md` and `CLAUDE.md` are **byte-for-byte unchanged** on this branch. Six of the ten
   TRACKED requirements are documentation-only (`SOR-17`, `SOR-19`, `SOR-20`'s note, `SOR-23`, plus
   SRO-08's capability recipe and SRO-68d's risk re-acceptance) and all six are therefore unmet.

None of these needs a redesign. F-SRO-01 is one builder flag; F-SRO-02 is one comparison; F-SRO-03 is
threading one parameter that already exists in config; F-SRO-04 is one call plus moving the render
loop; F-SRO-05 is documentation. **F-SRO-01 and F-SRO-02 must land together** — see the interaction
note under F-SRO-02.

**AppSec must-fix before merge:** F-SRO-01, F-SRO-02, F-SRO-03, F-SRO-04, F-SRO-05.
**Should-fix in the same pass (cheap, same files):** F-SRO-06 … F-SRO-10.
**Noted, non-blocking:** F-SRO-11 … F-SRO-18.

---

## Findings

| # | Severity | CWE / OWASP | Where (file:line) | Description | Remediation | Verdict |
|---|----------|-------------|-------------------|-------------|-------------|---------|
| **F-SRO-01** | **Medium** (CVSS:3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:N` ≈ 6.5 as a silent integrity/control-bypass defect; the "attacker" is an MR author acting through the model, and the outcome is a green review that reviewed nothing) | CWE-1286 (improper validation of syntactic correctness) / CWE-693 (protection mechanism failure), A04:2021 | `StructuredResponseParser.java:94-100` (the `StreamReadConstraints`/`JsonFactory` build — `STRICT_DUPLICATE_DETECTION` is never enabled) ; pinned by `StructuredResponseParserTest.java:332-383` | **`SOR-14`'s duplicate-key half is not implemented, and the consequence is silent content loss on the feature's flagship control.** The dedicated `ObjectMapper` correctly sets `maxNestingDepth`/`maxNameLength`/`maxStringLength` but not `StreamReadFeature.STRICT_DUPLICATE_DETECTION`. I confirmed the mechanics empirically against the exact classpath version (jackson-core 2.17.2): `readTree` on `{"files":{"a.java":…},"files":{"a.java":…,"b.java":…},"summary":…}` yields `{"files":{…second…},"summary":…}` — the **first** `files` object is discarded outright, not merged and not preferred. Validation then runs against the survivor and **passes**, because the survivor's key set is a legal coverage set. Same one level down: two `"A.java"` entries inside one `files` object, the first carrying a real finding and the second an empty `findings` array, produces `COMPLETED` with **zero** comments. There is no error, no `FailureKind`, no counter and no log line — i.e. the exact "silently absorbed, so there is no feedback signal" pathology this whole feature exists to remove (architecture §2). Reachability: the decoder constraint *would* make a duplicate key unrepresentable, but `gateway.structured.default-mode` is `OFF` (SRO-07, the shipped default) and stages 0–1 of the §11 rollout ladder run unconstrained by design, plus `SOR-INH-2`'s documented fail-open. In those configurations the response shape is chosen entirely by the model, which `SOR-INH-3` concedes is still influenceable by diff content. **The developer's stated reason for skipping it does not survive scrutiny**: it assumed the fix requires string-inspecting Jackson's parse exception to reclassify the failure as `SCHEMA_MISMATCH` rather than `NOT_JSON`, which would violate the WOR-05/F02-03 "never branch on a third-party exception message" rule. But `SOR-14`'s security property is *"never last-wins"*, not *"classified as `SCHEMA_MISMATCH`"* — and `NOT_JSON` is already a correct, retryable, counted outcome. I verified there is indeed no dedicated Jackson exception subtype (`JsonReadContext._checkDup` throws a plain `JsonParseException extends StreamReadException`; there is no `DuplicateFieldException`), so the developer's *premise* is right and the *conclusion* is wrong: no message inspection is needed if `NOT_JSON` is accepted. | Enable the feature on the parser's own factory: `JsonFactory.builder().streamReadConstraints(constraints).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()`. A duplicate key then throws `JsonParseException`, which the existing `catch` at `:144` already maps to `NOT_JSON` with `getClass().getSimpleName()` as the detail — **zero new message handling, zero new discipline exception**. Document in the class javadoc that a duplicate key is reported as `NOT_JSON`, not `SCHEMA_MISMATCH`, and why. *If* the finer classification is genuinely wanted, there is a fully typed, message-free discriminator: on `JsonParseException`, re-parse the same (already length-capped) string with a second, non-duplicate-detecting mapper — if that succeeds, the document is syntactically valid JSON whose only defect is a duplicate key ⇒ `SCHEMA_MISMATCH`; otherwise `NOT_JSON`. Two parses of a ≤200 KB string on the failure path only. Add a test asserting both QA duplicate-key fixtures now fail validation instead of silently passing (invert the two tests at `:332`/`:366` rather than deleting them). | **MUST-FIX** |
| **F-SRO-02** | **Medium** (CVSS:3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:H/A:L` ≈ 6.5; MR-author-selectable, silent, and defeats the stated coverage guarantee for one file per collision) | CWE-20 (improper input validation) / CWE-436 (interpretation conflict) / CWE-693, A04:2021 | `ReviewService.java:232-241` (the SRO-17 check: `sanitizedPaths.size() != chunk.filePaths().size()`) and `:399-404` (the same non-deduping sanitize before `toJsonArray`) ; `TextSanitizer.java:57-72` (`sanitizePath` strips Cc/Cf/Zl/Zp + `<`/`>` **and `trim()`s**) ; `DiffChunker.java:534-540` (the `diff --git` branch of `extractPathFromHeaderLine` does **not** trim) ; `ReviewSchemaBuilder.java:99-106` (`required` and `properties` populated from the raw list) ; `StructuredResponseParser.java:128` vs. `:168` (expected **set** deduped, iteration **list** not) ; `ChunkContextRenderer.java:169-175` (`appendUntruncatedFileBlock` **does** dedupe) | **SRO-17 checks that no path was *dropped*, but not that no two paths *collided*, so a duplicate schema key is reachable from a crafted file name.** `sanitizePath` is not injective: it strips Cc/Cf/Zl/Zp and `<`/`>` and then `trim()`s. Two distinct raw paths can therefore map to the same sanitized string while both remaining non-`null`, so `sanitizedPaths.size() == chunk.filePaths().size()` holds and the SRO-16/17/65 edge validation passes. Two concrete, reachable inputs: (a) files `src/A.java` and `src/A<>.java` (`<`/`>` stripped by F-DC-02's own rule); (b) files `src/A.java` and `src/ A.java` — `extractPathFromHeaderLine`'s `diff --git` branch takes `rest.substring(bIdx + 3)` with no `trim()`, so the leading space survives into `filePaths` as a genuinely distinct raw entry, and `sanitizePath`'s trailing `trim()` then collapses it. `DiffChunker`'s own dedup (`Section.addPath`'s `contains` check, `binPack`'s `LinkedHashSet`, `collectAllFilePaths`' `LinkedHashSet`) operates on the **raw** paths and cannot see the collision, and `ReviewService` never dedupes after sanitizing. `review_chunks.file_paths` therefore stores `["src/A.java","src/A.java"]`. Four downstream consequences, all silent: (i) the second real file is **never covered** — the guarantee the whole feature is named after is void for it, while validation reports success; (ii) `ReviewSchemaBuilder` emits `"required":["src/A.java","src/A.java"]` against a single `properties` entry, i.e. a schema whose `required` array we hand to an unpinned third-party GBNF compiler containing a duplicated member (`SOR-INH-2` territory — exactly the "do not hand the converter something we did not intend" posture `SOR-01` adopted); (iii) `StructuredResponseParser` iterates `expectedPaths` (the list, `:168`), not `expectedSet`, so every finding for that file is collected and **published twice**; (iv) `ChunkContextRenderer.appendUntruncatedFileBlock` dedupes while `ReviewSchemaBuilder` does not, so the prompt block and the schema's `required` disagree — a direct violation of SRO-64c's *"divergence must be impossible by construction"*, which is the property the "one list, one instance" plumbing was built to guarantee. **Interaction with F-SRO-01 — fix them together:** with `STRICT_DUPLICATE_DETECTION` enabled and a constrained backend, a schema whose `required` names the same key twice will drive the model to emit a duplicate JSON key, which the strict parser then rejects on *every* attempt ⇒ three burned attempts ⇒ `FAILED` ⇒ `ChunkCoordinator` cascades `CANCELLED` to successful siblings. Fixing F-SRO-01 alone converts today's silent-loss bug into a deterministic, filename-triggered review-kill. | In `validateStructuredOutputEligibility`, compare **distinct** sanitized paths against the raw count: `if (new LinkedHashSet<>(sanitizedPaths).size() != chunk.filePaths().size())` → the existing `422 STRUCTURED_OUTPUT_UNSUPPORTED` (no new error code; extend the message to name "two paths that become identical after sanitization" without echoing either). Belt-and-braces, per the SRO-67a precedent: make `ReviewSchemaBuilder.build` **throw** on a duplicate entry in `filePaths` — an unconditional invariant of the builder, not a caller obligation — and have `StructuredResponseParser` iterate `expectedSet` rather than `expectedPaths`. Separately, `trim()` the `diff --git` branch of `extractPathFromHeaderLine` for parity with the `+++ `/`--- ` branches (unconditional, no chunk-boundary effect: `filePaths` never feeds chunk text). Tests: the two collision inputs above ⇒ `422` under v3 and unchanged `200` under v2; a property test asserting `set(prompt block paths) == set(schema properties keys) == list(schema required)` with no duplicates. | **MUST-FIX** |
| **F-SRO-03** | **Medium** (CVSS:3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:M` ≈ 4.3 as an availability/correctness defect that manifests the moment v3 is enabled) | CWE-1284 (improper validation of specified quantity) / CWE-770, A04:2021 | `DiffSizeValidator.java:55-59` (`budgetTokens` unconditionally subtracts `cfg.getAnswerReserve()`) ; `DiffChunker.java:123` (`budgetTokens(systemPromptTokens)`, no per-version reserve) and `:146-150` (`headerReserveTokens = diff.chunk-header-reserve-tokens`, 256) ; `GatewayProperties.java:209-235` (the only two readers of `structured.answer-reserve` and of `coverageReserveTokens`, both inside `validateStructuredOnStartup`) ; `application.yml:153` | **The per-version answer reserve and the computed coverage header reserve exist only as a startup assertion; no runtime code path uses either.** SRO-64d is a MUST ("the `DiffChunker` single-chunk shortcut MUST use the header-reserved budget for structured versions … for structured versions the header reserve is **computed**, not taken from `chunk-header-reserve-tokens`") and `SOR-13`'s non-startup half is explicit ("the per-version answer reserve is threaded as a parameter through `DiffSizeValidator.budgetTokens`"). Neither happened. I grepped every reader of `structured.answer-reserve` in `src/main/java`: `GatewayProperties:209,226` only. `ReviewService.createReview` threads `maxFilesPerChunk` into `DiffChunker.split` but nothing else, and `DiffChunker` has no notion of a structured budget. Concretely, at the shipped defaults a v3 Review's per-chunk diff is sized against a **4000**-token answer reserve while `v3.yml` sets `maxTokens: 8192` and reserves **256** header tokens for a coverage block the same branch computes at ≈**2600**. A full-budget v3 chunk therefore overruns the context window by up to ~6.3k tokens at inference time. Because the failure lands *at llama-server*, the visible symptom is a truncated or malformed completion ⇒ `TRUNCATED`/`NOT_JSON` ⇒ `RetryManager` burns all three attempts on a chunk that can never succeed ⇒ `FAILED` + the `ChunkCoordinator` sibling cascade (`SOR-INH-1`'s worst case, triggered by ordinary large MRs rather than by an attacker). The branch also ships the misleading artifact of a five-point startup validation that fails fast on a budget nothing enforces — an operator who satisfies it gets no protection. | Thread the reserves the way `systemPromptTokens` is already threaded: add `DiffSizeValidator.budgetTokens(int systemPromptTokens, int answerReserveTokens)` (keep the existing overload delegating with `diff.answer-reserve`, so v1/v2 arithmetic is byte-identical — T-6.1), and pass `structured ? structured.answer-reserve : diff.answer-reserve` from `ReviewService` through a fourth `DiffChunker.split` parameter. In `split`, use `coverageReserveTokens` (the same `ceil((maxFilesPerChunk × (maxPathChars + 1) + COVERAGE_BLOCK_FIXED_CHARS) / charsPerToken)` formula, extracted to one shared place so the startup check and the runtime cannot drift) in place of `chunk-header-reserve-tokens` whenever `maxFilesPerChunk > 0`, **including for the single-chunk shortcut**. Also apply the structured reserve in `DiffSizeValidator.assertPromptFits` (`ReviewService.java:163`). Tests: (a) v1/v2 corpus byte-compat unchanged (the existing `f577670` corpus test must stay green); (b) a v3 diff sized just under the *old* budget and over the *new* one now chunks (or 422s) instead of producing one over-budget chunk; (c) a unit test asserting `budgetTokens` for a structured version equals `contextWindow − promptReserve − systemPromptTokens − 8000`. | **MUST-FIX** |
| **F-SRO-04** | **Medium** (CVSS:3.1 `AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:M` ≈ 5.4; model-controlled amplification executed while holding a job-row lock and a pooled connection) | CWE-770 (allocation without limits) / CWE-405 (asymmetric resource consumption), A04:2021 | `ResultProcessor.java:219` (legacy path passes `fairShareCommentCap(reviewId)`) vs. `:244-277` (`processStructuredJobPhase` never calls it — only the SRO-68 fallback at `:309` does) ; `StructuredResponseParser.java:175-183` (renders **every** collected finding) ; `CommentRenderer.java:174-217` (`extractDiffContext` re-splits the whole `chunkDiff` per finding) ; `ChunkCoordinator.java:249-256` (`persistCappedComments`, the only surviving cap) | **Two `SOR-18`/SRO-33 obligations are unmet on the structured path.** (a) **The per-chunk fair-share cap is gone.** SRO-33 states the existing `fairShareCommentCap` "and the review-level cap … are unchanged and still apply". `processStructuredJobPhase` hands `result.success().comments()` straight to `chunkCoordinator.completeChunkAndRecompute` with no per-chunk cap; only the review-level `max-comment-count` (50) survives, and it is first-come-first-served. So chunk 0 of a 6-chunk Review can consume the entire 50-comment budget and every sibling chunk publishes nothing — the precise starvation `fairShareCommentCap` exists to prevent, and a quality regression against v2 on exactly the large MRs v3 targets. (b) **Every finding is rendered before any cap is applied.** The hard bound is `max-files-per-chunk × max-findings-per-file` = 40 × 20 = **800** `CommentRenderer.render` calls per result submission, and each one calls `extractDiffContext`, which does `List.of(chunkDiff.split("\n", -1))` over the *whole* chunk diff (up to ~156 KB at the default budget) plus a linear hunk walk. That is ~125 MB of transient allocation and 800 full-diff scans per `POST /jobs/{id}/result`, all executed **inside `processJobPhase`'s `REQUIRES_NEW` transaction while it holds the `review_jobs` row `FOR UPDATE`** (`SET LOCAL lock_timeout = '3s'`) and one of 20 Hikari connections. The count is chosen by the model, i.e. influenceable by diff content (`SOR-INH-3`). `SOR-18` asked for exactly the opposite ordering: "comment bodies are rendered only for findings that survive the fair-share/review-level caps … diff-context extraction is not run per-finding over the whole chunk diff". | Apply `fairShareCommentCap(reviewId)` to the structured result before rendering (pass it into `StructuredResponseParser.validate`, or truncate the `RawFinding` list between collection and the render loop at `StructuredResponseParser.java:175`) — validation of *all* findings must still happen first, only *rendering* is capped. Separately, hoist the line-splitting out of the per-finding loop: split `chunkDiff` once per result and index the `diff --git` section offsets once (a `Map<String, int[]>` of section bounds built in one pass), then have `extractDiffContext` walk only its own section's slice. Optionally move the render loop out of the locked phase-1 transaction entirely — it needs no lock and no entity (it is a pure function of the validated findings + `chunk.getDiff()`), so it can run in `process()` before `completeChunkAndRecompute`. Test: a 40-file × 20-finding conforming response publishes at most `fairShare` comments and the phase-1 transaction does not approach `lock_timeout`. | **MUST-FIX** |
| **F-SRO-05** | **Low** (no direct exploit; blocking because this repo's own contract makes `README.md`/`DEPLOYMENT.md` authoritative, and because six TRACKED security requirements are documentation-only) | CWE-1059 (incorrect/insufficient documentation), A04:2021 | `git diff --stat 1957f2b..HEAD -- README.md DEPLOYMENT.md worker/README.md CLAUDE.md` → **empty**; working-tree `README.md:806-853` (uncommitted, unrelated) states the now-wrong pre-allowlist behavior | **Not one line of documentation landed for a feature that adds a new `promptVersion`, a new error code, 15 new config keys, 3 new env vars, two DB columns, four `/metrics` fields and a new field on two wire contracts.** CLAUDE.md states README/DEPLOYMENT "get updated as features land" and are authoritative for current behavior; merging this as-is breaks that invariant outright. The security-relevant subset — the reason this is a finding and not a chore — is that **six of the ten TRACKED requirements are doc-only and are therefore unmet**: `SOR-17` (the `SOR-INH-1` amplification/sibling-cascade residual documented in `DEPLOYMENT.md` with its response), `SOR-19` (README statement that `finish_reason`/`summary` describe the **first** attempt — SRO-37's accepted trade-off), `SOR-20`'s second half (rollback tolerance + "relax `ck_backends_structured_output_mode` before adding a fifth mode"), `SOR-23` (the `git -c core.quotePath=false diff` CI requirement for v3 and the `422 STRUCTURED_OUTPUT_UNSUPPORTED` response, with "use v2" as the stated fallback), SRO-08 (the llama-server capability `curl` recipe **with its negative control** and the `failed to parse grammar` log check — the only compensating control for `SOR-INH-2`), and SRO-68d (that `RETRY_THEN_FALLBACK` re-accepts the SR-08/SR-09 residual on an MR-author-forceable path and that the correct incident response is `structured.enabled=false`). Also unwritten: `SOR-08`'s hard prerequisite that **every Worker ships `v3.yml` before `v3` enters `allowed-prompt-versions`** (Workers-first), the `structured.answer-reserve` ↔ `v3.yml maxTokens` and `max-schema-bytes` ↔ `max-constraint-bytes` cross-module couplings that "a mismatch is silent" depends on, the SRO-66a note that a >64-path single section now yields a shorter advisory list for v1/v2 too, and the three new env vars (`ALLOWED_PROMPT_VERSIONS`, `STRUCTURED_OUTPUT_ENABLED`, `STRUCTURED_OUTPUT_DEFAULT_MODE`) that exist in `application.yml` but nowhere else — the F-PM-02 config-drift lesson in reverse. Separately, the uncommitted README addition in the working tree asserts that an unregistered `promptVersion` "fails on the Worker side once a job is claimed, **not at `POST /reviews` time**", which `SOR-08`'s allowlist has just made false. | Work through architecture §14 item by item; treat the six TRACKED doc items above as the acceptance criteria. Fix the stale sentence in the uncommitted README block (or drop that block from this branch). | **MUST-FIX** |
| **F-SRO-06** | Low | CWE-1021 (improper restriction of rendered UI layers) / CWE-451 (UI misrepresentation), A04:2021 | `CommentRenderer.java:141-146` (suggestion block), `:150-162` (diff-context block), `:254-282` (`sanitizeCodeBlock`) | **`SOR-22` is not implemented: a code block that `sanitizeCodeBlock` altered or truncated carries no marker, and the diff-context block is not labelled an excerpt.** The sanitizer deliberately mutates what it renders — it collapses every run of 3+ backticks to two, strips all Cf/Zl/Zp and all Cc except `\n`/`\t`, **deletes whole lines matching `^\s*/(?![/*])`** (SRO-56 — which for a *diff-context* block means real source lines can vanish mid-excerpt), and hard-truncates with `substring`. The reviewer is shown a fenced block that looks like a verbatim quotation of the file with no indication that any of that happened. `SOT-23` rated this as the reason SRO-52's "never the `suggestion` fence language" is a hard rule; the marker is the other half of that answer, and it is also the prerequisite recorded in §6.1 for the deferred `feature/gitlab-positioned-suggestions` (an altered block must never be one click from a commit). | Track a `boolean altered` through `sanitizeCodeBlock` (set when any of the four transformations changed the string or the cap fired) and append a Gateway-constant line after the closing fence, e.g. `_(normalized for display; not a verbatim quotation)_`. Label the diff-context block unconditionally, e.g. `Context (excerpt from the reviewed diff):` above the fence — it is by definition ±3 lines, never the whole file. Both strings must be Gateway constants at fixed positions (WOR-04/SRO-41 discipline). | SHOULD-FIX |
| **F-SRO-07** | Low | CWE-116 (improper encoding/escaping of output) / CWE-693, A04:2021 | `CommentRenderer.java:97-103` (`hasBalancedFences` fallback) and `:306-314` ; `CommentParser.java:271-277` (`sanitizeProseText` — no backtick handling) | **The `SOR-09` fence-integrity check is defeatable by the model through the *prose* field, and its own fallback does not re-verify.** `sanitizeCodeBlock` guarantees code content can never contain a 3+ backtick run, so the four-backtick `CODE_FENCE` can only appear as a genuine pair — but the **prose** path (`stripQuickActionLines → neutralizeMentions → htmlEscape → cap`, correctly unchanged per SRO-54) does *not* collapse backticks, and `HtmlUtils.htmlEscape` leaves `` ` `` untouched. A model comment containing a literal ```` ```` ```` therefore makes `hasBalancedFences` return `false`, which (a) drops **both** code blocks — a model-controlled way to suppress the diff-context and suggestion blocks on any finding — and (b) re-assembles `header + prose` and returns it **without re-running the check**, so the shipped body still contains the unbalanced fence and GitLab still renders the tail of the comment as an open code block. `SOR-09` requires the *assembled body* to be structurally verified before persistence/publication and to degrade rather than ship a broken body; the current implementation verifies, then ships a body it has not re-verified. Impact is display corruption plus block suppression, not injection (the prose is HTML-escaped and mentions are neutralized), which is why this is Low. | Collapse 3+ backtick runs in the prose part too — do it in `CommentRenderer` on the already-sanitized prose (**not** inside `CommentParser.sanitizeProseText`, which must stay byte-identical for v1/v2 per SRO-54/SR-08/SR-09), before assembly and before any cap. Then `hasBalancedFences` becomes a genuine invariant rather than a heuristic. Additionally, re-run `hasBalancedFences` on the fallback body at `:99-102` and fall through to `assembleWithTruncatedProse` if it still fails. Test: a finding whose `comment` is exactly ```` ```` ```` still publishes its diff-context and suggestion blocks and yields a balanced body. | SHOULD-FIX |
| **F-SRO-08** | Low | CWE-178 (improper handling of case sensitivity) / CWE-117 (improper output neutralization for logs), A09:2021 | `StructuredResponseParser.java:132` (`"length".equals(finishReason)`) vs. `ResultProcessor.java:412-421` (`normalizeFinishReason` → `FinishReason.fromWireValue`, which `trim()`s and lower-cases) ; `SubmitResultCommand.java:25` | **Two small discipline slips around the new Worker-supplied `finishReason`.** (a) The `TRUNCATED` classification compares the **raw** wire string, while the value **stored** in `review_results.finish_reason` goes through `FinishReason.fromWireValue`. A backend build that returns `"Length"`, `"LENGTH"` or `" length"` therefore stores `length` but is *not* classified `TRUNCATED` — the response falls through to `NOT_JSON`/`SCHEMA_MISMATCH`, and the operator gets the mystery message SRO-42/43 exist to eliminate, while the DB row says the truncation was known. SRO-43's rule is "whitelist-parse against its own closed set", and the parser is the one place that does not. (b) `SubmitResultCommand.toString()` — a deliberately *masked* DTO (F-DC-07) — now renders `finishReason` raw. It is `@Size(max = 32)`-bounded at the DTO edge but not sanitized, so CR/LF from a compromised or buggy Worker reaches any accidental `log.debug("{}", command)` as real newlines (WOR-06/WOR-07's log-injection concern, on a value that today has no `TextSanitizer` pass anywhere). | (a) Compare `FinishReason.fromWireValue(finishReason) == FinishReason.LENGTH` in the parser, and derive the `detail` string from `FinishReason.wireValue()` rather than the raw text. (b) Mask or sanitize `finishReason` in `SubmitResultCommand.toString()` (`textSanitizer` is not available there — simplest is to render only whether it is present, or a `[A-Za-z_]`-filtered form). | SHOULD-FIX |
| **F-SRO-09** | Low | CWE-209 (information exposure through an error message) / CWE-20, A04:2021 | `ReviewService.java:208-213` (`validatePromptVersionAllowlist` embeds `promptVersion` verbatim) ; `GlobalExceptionHandler.java:48-56` (returns `ex.getMessage()` in the body) ; `CreateReviewRequest.java:17` (`@NotBlank`, no `@Size`, no `@Pattern`) | **The new 422 echoes caller-controlled text into its response body — the exact pattern F-DC-06 established must not happen.** F-DC-06's fix javadoc (still in `DiffChunker.java:423-427`) states raw attacker-controlled values are "never safe to embed verbatim in an exception message that reaches an HTTP response body". `promptVersion` has no length or character constraint beyond `@NotBlank`, so a caller can put an arbitrarily long, arbitrary-content string (bounded only by the 320 KB `/reviews` body cap) into the 422 body and, via `GlobalExceptionHandler`'s handler, back out. The blast radius is small — the reflector is the CI-token holder reflecting to itself, and the response is `application/json`, so this is self-reflection, not stored/reflected XSS — but the same handler is the one SR-17 keeps clean of rejected values, and the new `STRUCTURED_OUTPUT_UNSUPPORTED` handler's own javadoc claims it "never echoes a raw file path" while the sibling throw site echoes a raw `promptVersion`. Note the SRO-16/17/65 throw sites at `:226`, `:239` and `:248` are all correct — they name the property/character class and never the path. | Add `@Size(max = 32)` and a conservative `@Pattern("^[A-Za-z0-9._-]{1,32}$")` to `CreateReviewRequest.promptVersion` (which also removes the `@NotBlank`-only gap for `reviews.prompt_version`'s own column width), and stop echoing the value — say only that the requested version is not allowlisted and list the allowed set (a Gateway constant). | SHOULD-FIX |
| **F-SRO-10** | Low | CWE-704 (incorrect type conversion or cast — here, truncation of a diagnostic) / CWE-1059, A09:2021 | `StructuredResponseParser.java:78-79` (`MAX_LISTED_KEYS = 5`, `MAX_KEY_CHARS = 64`) and `:299-320` ; `RetryManager.java:60` (`MAX_LAST_ERROR_LENGTH = 512`), `:168` | **`SOR-15` is implemented but its arithmetic does not achieve the property it was written for.** The key-count cap is exactly what I asked for structurally — at most 5 keys per side, each `sanitizeSingleLine(…, 64)`, `(+K more)` for the remainder, both sides listed separately — but the worst case is `"missing=[" + 5×64 + 4 separators + "] (+K more)" + "; unexpected=" + …` ≈ **700 characters** before `composeStructuredFailureReason`'s Gateway-constant prefix and `RetryManager`'s attempt suffix are added, against a 512-char `last_error` cap. `RetryManager.sanitizeLastError` truncates from the right, so what is lost is the **`unexpected=` half** — the model-controlled half, i.e. the "invented `src/Z.java`" diagnosis that `SOR-15` was specifically written to preserve ("the 512-char cap cannot truncate away the very diagnosis `COVERAGE_SHORTFALL` exists to carry"). The trigger is not exotic: `max-path-chars` is 256, so an MR author choosing long-but-legal file names makes every listed key hit the 64-char cap. Typical 30-char paths stay well inside 512, which is why no test caught it. | Size the two lists against the real budget: either `MAX_KEY_CHARS = 40` with `MAX_LISTED_KEYS = 5` (≈ 460 worst case), or keep 64 chars and drop to 3 keys per side. Better: compute the per-key budget from `MAX_LAST_ERROR_LENGTH` minus the constant prefix/suffix lengths so the two constants cannot drift. Add a test with 5 × 256-char missing paths **and** 5 × 256-char unexpected keys asserting the final `last_error` still contains both the `missing=` and `unexpected=` labels and at least one key from each. | SHOULD-FIX |
| F-SRO-11 | Info | CWE-778 (insufficient logging) | `QueueManager.java:302-327` (`CLAIMED` path records no mode/schema provenance) ; `MetricsCounters.java:31-33` ; `StatisticsService.java:100-105` ; `StructuredResponseParser.java:159,184` + `ResultProcessor.java:400` | Three SHOULDs recorded as **not implemented**, all three legitimately deferrable but none of them stated anywhere as a decision. (a) **`SOR-16`** — the effective `structured_output_mode` and a short schema-hash prefix are not written to the `CLAIMED`/`RUNNING` event `details`, so "was *this* Review decoder-constrained, and under which schema?" is unanswerable from the DB after a config change; the `structuredConstraintSent{mode}` counter is process-local (WOR-03 by design) and cannot substitute. This is PMR-10's lesson (`review_prompt_sections` exists precisely because provenance that is not persisted is lost) applied to the one artifact SRO-19 deliberately does **not** persist — which makes the hash prefix the only available substitute. (b) **`SRO-46`** (`averageFileCoverageRatio`) — not added to `StatisticsService`; the §11 stage-0 "before" measurement is therefore only half-instrumented (`legacyParseFallback` is present and does work on v1/v2 traffic today, which is the more important half). (c) **`SRO-26`** — the chunk-level `summary` is parsed into `Success.summary` and then **discarded**: `storeRawResult` still passes `null` for `review_results.summary` (`ResultProcessor.java:400`), so `Success.summary` is dead code and the V1 column stays empty. | Either implement or record each as an explicit deferral in the architecture doc. `SOR-16` is the one with real operational value. If `SRO-26` is wired up later, read F-SRO-14 first. |
| F-SRO-12 | Info | CWE-770 | absent: no `gateway.structured.max-validation-attempts` anywhere in `src/main` or `src/test` | **`SOR-17`'s knob is not implemented**, so the `SOR-INH-1` residual (3× LLM-compute amplification per chunk, freely triggerable, with the `ChunkCoordinator` cascade discarding *successful* siblings) has exactly one operator lever: `gateway.structured.enabled=false`, which disables the feature wholesale. That is an acceptable position — but it is only acceptable if it is written down, and per F-SRO-05 it is not. | Either add the knob (default = `retry.max-attempts`, read in `ResultProcessor.processStructuredJobPhase`'s `lastAttempt` computation and passed to `RetryManager`) or document the single-lever position in `DEPLOYMENT.md` as the deliberate answer. |
| F-SRO-13 | Info | — (test-strength / requirement-wording) | `StructuredResponseParserTest.java:300-308` ; `StructuredResponseParser.java:130-137` | Two notes on `SOR-11`, whose substance I verified independently as **met**. (a) The architecture test asserts only that the **constructor's parameter type names** contain neither `Backend` nor `StructuredOutputMode`. That is a weak proxy: it would not catch a static call, a method-local type, an import used only inside a method body, or a `Backend` reaching the class through a wider parameter. I confirmed by reading the whole class that no such reference exists today, so the control is real and only its guard is thin. (b) The parser **does** branch on `finish_reason` (the `knownTruncated` short-circuit at `:132`), which the letter of `SOR-11` forbids ("no code path keys off … `finish_reason`"). I am **accepting** the deviation: the branch can only ever produce a *failure*, never an acceptance, so it cannot be used to shortcut validation — which is the property `SOR-11` was written to protect. Worth recording so the next round does not re-open it. | Strengthen the test to scan the class's constant pool / source text for the two type names (the repo already has grep-style architecture tests, e.g. the SOR-05a no-string-templating assertion in `ReviewSchemaBuilderTest`). Amend `SOR-11`'s wording in the threat model to "no code path may *accept* a response on the strength of `finish_reason` or the backend mode". |
| F-SRO-14 | Info | CWE-1284 | `StructuredResponseParser.java:197-200` (top-level `summary`), `:215-220` (per-file `summary`) vs. `:263-268` (`comment`/`suggestion` **are** length-re-checked) | **The two `summary` fields are the only schema-bounded strings whose `maxLength` is not re-validated**, breaking SRO-04's "never trust the constraint" symmetry: `comment` and `suggestion` are re-checked against `maxCommentChars`/`maxSuggestionChars`, and `findings.size()` is re-checked against `maxItems`, but `summary` is only checked for `isTextual()`. Today this is harmless *only because* the values are discarded (F-SRO-11c) — the chunk-level `summary` never reaches `review_results.summary`, and the per-file `summary` is never read at all. It becomes a genuine unbounded write (up to `maxStringLength`, i.e. `max-raw-response-length` = 200 000 chars of model-controlled text) the moment SRO-26 is implemented as designed. | Re-check both against `ReviewSchemaBuilder`'s `CHUNK_SUMMARY_MAX_LENGTH` (500) and `PER_FILE_SUMMARY_MAX_LENGTH` (200) — the two constants already exist in the builder and should be exposed rather than duplicated — and treat an overrun as `SCHEMA_MISMATCH`, exactly like `comment`. Do this **before** wiring SRO-26. |
| F-SRO-15 | Info | CWE-665 (improper initialization) | `db/migration/V5__structured_review_output.sql:12` | `SET lock_timeout = '5s'` is **session**-scoped, not `SET LOCAL`. Flyway borrows its connection from the Hikari pool and returns it without resetting session state, so one pooled connection keeps a 5s `lock_timeout` for the life of the process. This is a verbatim repeat of **F-WOC-06** (filed against V4, still open), the direction is benign (fail-fast, and every lock-taking runtime path sets its own `SET LOCAL lock_timeout = '3s'`), and it is invisible in tests because Zonky hands out fresh databases. Recording it so V4 and V5 get fixed together rather than a third migration repeating it. | `SET LOCAL lock_timeout = '5s';` in both V4 and V5 — Flyway wraps each migration in a transaction, so `LOCAL` is sufficient and correctly scoped. |
| F-SRO-16 | Info | CWE-1284 | `DiffChunker.java:564-576` (`addPath` increments `pathLinesSeen` per *line*; `pathExtractionTruncated()` compares it against `maxPathsPerSection`, a bound on *distinct* paths) | The SRO-66a truncation flag compares a **line** counter against a **distinct-path** cap, so a section containing more than 64 non-blank path header lines that name 64 or fewer distinct files is reported as "extraction truncated" and rejected with `422 DIFF_TOO_LARGE` under v3 even though `filePaths` is complete. Direction is fail-closed (a false 422, never a silently narrowed coverage set), and `git diff` does not produce such a shape, so this is availability-only on crafted/unusual input. The mismatch is nevertheless worth stating: the field name (`pathLinesSeen`) and the javadoc ("counts every non-blank path line seen … so a caller can still tell a truncated extraction apart") describe the intent correctly, and the comparison does not implement it. | Compare distinct paths instead: set an explicit `truncated = true` flag inside `addPath` when `filePaths.size() >= maxPathsPerSection && !filePaths.contains(path)` — that is precisely "a distinct path was seen and not accumulated". Keep `pathLinesSeen` for diagnostics if useful. |
| F-SRO-17 | Info | — (observability accuracy) | `QueueManager.java:311` (`incrementStructuredConstraintSent(mode.name())`) vs. `:313-325` (the `SCHEMA_TOO_LARGE` early return that follows it) | `structuredConstraintSent{mode}` is incremented **before** the schema is built and before the SRO-15 backstop can fire, so a job that ends up failing with `SCHEMA_TOO_LARGE` (and therefore never carried a constraint at all) is counted as having carried one. Also, the `KILL_SWITCH_OFF` pseudo-mode is folded into the same map as the four real `StructuredOutputMode` names, which is fine for a closed Gateway vocabulary (`SOR-21` verified met) but makes the counter's meaning "claims that reached the constraint decision", not its documented "how many claims actually carried a constraint". | Move the increment below the `DecoderConstraintRenderer.render` call, or add a distinct `SCHEMA_TOO_LARGE` key and decrement/skip on that path. Correct the javadoc either way. |
| F-SRO-18 | Info | CWE-1023 (incomplete comparison) | `CommentRenderer.java:225-243` (`locateSectionStart`/`locateNextSectionStart`) | `locateSectionStart` takes the **first** line satisfying `startsWith("diff --git ") && endsWith(" b/" + filePath)`. Two consequences on a crafted diff, both strictly *intra*-chunk: (a) if the chunk text contains two `diff --git` sections for the same path, the excerpt always comes from the first, which may not be the one the finding's line belongs to; (b) the scan is over **all** lines of the chunk, including hunk bodies — a real `git diff` always prefixes body lines with `+`/`-`/space so no body line can match, but the diff is free-form attacker text at `POST /reviews`, so a fabricated unprefixed `diff --git a/x b/target.java` line inside a hunk body creates a synthetic "section" for both this scanner and `DiffChunker.parseSections` (consistently, which is why it is not worse). I verified the load-bearing part of `SOR-12` **is** met: `chunkDiff` comes from the `ReviewChunk` loaded by the locked job row's own `(reviewId, chunkIndex)` (`ResultProcessor.java:247-250`), never from anything in the model response, so no cross-Review or cross-chunk content can ever appear; and I verified the suffix match cannot alias sibling paths (`b/dir/A` does not end with `" b/A"`). This is therefore the intra-chunk misattribution `SOT-17` already identified and rated as the realistic residual, now confirmed as the only remaining form. | Pin `SOR-12`'s stated invariant as a test ("no rendered body ever contains bytes from outside its own file's section", including for a duplicated-path and a fabricated-header diff) rather than changing the algorithm. If cheap, prefer the section whose hunk ranges actually contain the finding's line over the first match. |

---

## Requirement-by-requirement verification against the shipped code

Verified by reading the shipped implementation — not the architecture doc, not commit messages, not
tests alone (where a test is the only evidence, that is stated).

### CRITICAL set (`SOR-06`–`SOR-13`) — gates this round

| Req | Verdict | Evidence |
|---|---|---|
| **SOR-06** (byte-measured constraint bound) | ✅ **Met** | `DecoderConstraintResolver.java:64-73`: `raw.getBytes(StandardCharsets.UTF_8).length` compared against `max-constraint-bytes` **before** `readTree` at `:76` — never `String.length()`, never post-parse. `worker.limits.max-constraint-bytes = 69_632` (`WorkerProperties.java:430`, `worker/application.yml:57`) vs. `gateway.structured.max-schema-bytes = 65_536`: a 4096-byte headroom against the largest wrapper (`RESPONSE_FORMAT_JSON_SCHEMA`'s envelope is ~70 bytes), so the "set them equal ⇒ fleet-wide `CONSTRAINT_INVALID` loop at the top of the range" failure I flagged cannot occur. `requirePositive` at startup (`WorkerProperties.java:142`). The Gateway side measures the same quantity the same way (`QueueManager.java:314`, `schema.getBytes(UTF_8).length`), so the two bounds are commensurable. Covered by `DecoderConstraintResolverTest` and T-3.3. |
| **SOR-07** (transport, not template input; not a body overlay) | ✅ **Met — the strongest item on the branch** | Typed fields, not a `Map`: `ChatCompletionRequest.java:26-27` declares `JsonNode responseFormat` / `JsonNode jsonSchema` with `@JsonInclude(NON_NULL)` and a legacy 4-arg constructor, so a null/null request serializes byte-identically to pre-branch (§9.3.6's rejected alternative is enforced in code, not merely preferred). Never through the template: `WorkerLoop.java:254-261` resolves the constraint from `job.payload()` **directly**, after `promptTemplateService.resolve` and outside it; `PromptTemplateService` has no constraint parameter at all (grep-verified), so the constraint never meets `substitute()` or the `{{`/`}}` strip. Mutual exclusivity: checked first, before any parsing (`DecoderConstraintResolver.java:56-59`) ⇒ `CONSTRAINT_INVALID` before any llama call. Masking: `worker/gateway/dto/JobPayload.java:58-60`, `gateway/dto/JobPayload.java:49-51` and `service/dto/ClaimedJob.java:46-48` all render `<masked, N chars>`; `SensitiveDtoToStringMaskingTest` covers all three in both modules. No logger anywhere receives the constraint: I grepped every `log.*` call in `DecoderConstraintResolver`, `DecoderConstraintRenderer`, `ReviewSchemaBuilder`, `LlamaClient` and `QueueManager`'s new block — all pass ids, lengths, byte counts or `getClass().getSimpleName()`. `DecoderConstraintResolver.java:78-82` explicitly logs the exception **class**, never Jackson's message. |
| **SOR-08** (`promptVersion` allowlisted at the edge) | ✅ **Met** | `ReviewService.java:146` — `validatePromptVersionAllowlist` is the **first** statement of `createReview`, before `rejectIfAbsurdlyLarge`, before dedup, before any DB write. Default `v1,v2` (`GatewayProperties.java:1446`, `application.yml:136`), so `v3` is unreachable until an operator opts in. `@NotBlank` on the DTO means `null` cannot slip past `Set.contains`. Maps to `422 STRUCTURED_OUTPUT_UNSUPPORTED` via the new handler. The **documentation** half (Workers-first ordering as a hard prerequisite) is missing — F-SRO-05; the message-hygiene nit is F-SRO-09. |
| **SOR-09** (fence integrity enforced and asserted) | ⚠️ **Substantially met — one gap, F-SRO-07** | Correct and verified: backtick-run collapsing runs **before** the cap and, critically, **after** `TextSanitizer.sanitizeSectionText` (`CommentRenderer.java:255-258`) — so the `` `` `` + stripped-Cf + `` `` `` → ```` ```` ```` concatenation attack I looked for specifically is closed by the ordering; blocks are dropped **whole** in the SRO-53 order (diff-context, then suggestion, then prose truncation) at `:83-92`, never truncated internally; the fence is four backticks while content can never exceed two; the language tag is a Gateway constant and is never `suggestion` (SRO-52 — `DIFF_FENCE_LANGUAGE = "diff"` and the suggestion block is a bare fence, `:40-43,145`); the assembled body is structurally verified at `:97-103`. The gap is that the *prose* pipeline can inject a 4-backtick run, which both defeats the verification and survives the fallback un-re-verified. |
| **SOR-10** (header path escaped for its context; `file_path` shaped like v1/v2) | ✅ **Met** | `CommentRenderer.java:73` calls `commentParser.sanitizeFilePath` — the *same* method v1/v2 uses (made package-private rather than reimplemented), so escape-then-cap ordering (F02-08) and the `VARCHAR(1024)` protection are shared, and `StructuredResponseParser.java:181` stores the same value in `review_comments.file_path`. `safeHeaderPath` (`:123-133`) independently strips any surviving backtick with a WARN — the "asserted independently so the two controls do not silently depend on each other" clause, implemented exactly as written. Note (Info, not a finding): `sanitizeFilePath` HTML-escapes, and entities are *not* decoded inside an inline code span, so a path containing `&` renders as `&amp;` in the header. That is the deliberate consequence of `SOR-10`'s "both versions store the same shape" requirement, so I am not filing it. |
| **SOR-11** (validation structurally un-shortcuttable) | ✅ **Met** | No reference to `Backend`/`StructuredOutputMode` anywhere in `StructuredResponseParser` (read in full, not just the constructor). `ResultProcessor.processJobPhase:204-210` selects the parser on `promptVersion` + the kill switch **only** — never on the backend or the mode — so a conforming response from a `mode = OFF` backend is validated identically. The `maxItems` bound is re-checked at `:221-224` with the comment "never trust the decoder constraint", and `severity` is whitelist-matched (`:273-284`), not `Enum.valueOf`. SRO-67c's empty-expected-set invariant is a thrown `IllegalStateException` (`:120-127`) that `ResultProcessor.java:258-268` converts to a terminal `FAILED` with `structured-output: INVARIANT_VIOLATION` and **no retry** — correct, and correctly distinguished from a validation kind. Two Info-level notes in F-SRO-13. The SRO-08 capability recipe's **negative control** is part of the missing docs (F-SRO-05). |
| **SOR-12** (diff-context source is the locked job row's own chunk) | ✅ **Met at the level that matters** | `ResultProcessor.java:247-250` loads the `ReviewChunk` by `(job.getReviewId(), job.getChunkIndex())` taken from the row locked `FOR UPDATE` at `:184`, then passes `chunk.getDiff()` through to `CommentRenderer` — nothing in the model's response ever selects a chunk, a Review or a file section (the file key is the *validated* schema key, and validation has already proved the key set equals the chunk's own path list). No cross-Review path exists. `extractDiffContext` locates the section, walks only that section's `@@` hunks tracking new-file line numbers, stops at the next `diff --git` (`CommentRenderer.java:180-216`), and returns empty — DEBUG-logged, block omitted, never a positional guess — when the line is unlocatable or `null`. A finding on file A carrying file B's line number produces no block, because the walk never leaves A's section. Residual intra-chunk misattribution is F-SRO-18 (Info), matching `SOT-17`'s own rating. |
| **SOR-13** (Prompt Manager term in the startup budget; per-version reserve threaded) | ⚠️ **Half met — see F-SRO-03** | The startup half is **implemented well**: `GatewayProperties.validateStructuredOnStartup` (`:178-238`) covers all five points, including the one the pre-implementation draft omitted — `promptManagerTerm = prompt.isEnabled() ? prompt.limits.maxSystemPromptTokens : 0` at `:224` — and the failure message names every contributing property. `GatewayPropertiesStructuredValidationTest` (122 lines) exercises it, and `ApplicationYamlBootTest` boots the real shipped YAML against it, so there is no F-PM-02-style drift (I diffed `application.yml`'s `structured` block against the Java defaults field by field: identical). The runtime half — threading the per-version answer reserve and the computed coverage reserve through `DiffSizeValidator.budgetTokens`/`DiffChunker.split` — was not done at all. |

### TRACKED set (`SOR-14`–`SOR-23`) — deferred to this round, verified here

| Req | Verdict | Evidence |
|---|---|---|
| **SOR-14** (parser hardening) | ⚠️ **Half met — F-SRO-01** | `StreamReadConstraints` **is** applied and correctly sized: `maxNestingDepth(64)`, `maxNameLength(1024)`, `maxStringLength(max(20_000, publish.maxRawResponseLength))` on a dedicated `JsonFactory`/`JsonMapper` (`:94-100`) — the developer's report is accurate on this point. `capRawResponseIfNeeded` provably runs **before** parsing and the fact is passed in explicitly as `rawResponseTruncated` rather than inferred (`ResultProcessor.java:118-125,256`; SRO-32 step 4 no longer depends on an incidental ordering — this is the strongest part of the item). No parse-exception message is ever logged or stored: `:144-150` uses `getClass().getSimpleName()` on both branches, and the broad `catch (Exception)` at `:148` correctly also swallows `StreamConstraintsException` into `NOT_JSON`. `STRICT_DUPLICATE_DETECTION` is the missing piece. |
| **SOR-15** (`last_error` key-count capping) | ⚠️ **Implemented, arithmetic short — F-SRO-10** | Structure is exactly as specified (`:299-320`); every listed key is `sanitizeSingleLine`-ed; both sides are reported separately; `(+K more)` is present. WOR-04's positional grammar is preserved: `composeStructuredFailureReason` (`ResultProcessor.java:345-348`) emits the Gateway constant `"structured-output: <KIND>"` **first**, and `RetryManager` appends its own constant attempt suffix **last**, so a model-supplied key of `"x; worker-reported: heartbeat timeout"` cannot forge the origin discriminator for anyone anchoring at position 0 — I traced this end to end and it holds. |
| **SOR-16** (mode + schema-hash provenance per claim) | ❌ **Not implemented** — F-SRO-11a | Neither the effective mode nor a schema hash reaches `review_events.details`. |
| **SOR-17** (`max-validation-attempts` knob + documented residual) | ❌ **Not implemented** — F-SRO-12, and the doc half is F-SRO-05 | |
| **SOR-18** (bounded rendering) | ❌ **Not implemented** — F-SRO-04 | Rendering runs for every finding, before every cap, inside the locked transaction; the fair-share cap is additionally lost. |
| **SOR-19** (write-once `summary`/`finish_reason`) | ✅ **Met in code, ❌ undocumented** | The security-relevant half is correct: the `TRUNCATED` classification is derived from the **in-flight** `command.finishReason()` and `capped.truncated()` (`ResultProcessor.java:256`), never from a stored value, so a retry's classification can never be contaminated by attempt 1's row. `review_results` is still written once per `(review_id, chunk_index)` (`:194-198`), so `finish_reason` describes the **first** attempt — which `SOR-19` permits provided `README.md` says so. It does not (F-SRO-05). `summary` is moot because it is never written (F-SRO-11c). |
| **SOR-20** (migration `lock_timeout`) | ✅ **Met** (nit: F-SRO-15) | `V5__structured_review_output.sql:12` sets `lock_timeout = '5s'` before the two `ALTER TABLE`s; two nullable additive columns + one `CHECK`, no backfill, no existing-constraint change. Rollback tolerance is real, not asserted: `ReviewResult.finishReason` is `updatable = false` and an older JAR never writes either column, so the `CHECK` cannot be violated and `ddl-auto: validate` tolerates the extra columns. The `CHECK` + `StructuredOutputMode.fromNullable` belt-and-braces split is implemented as designed (`Backend.java:84-85` plain `String`, `DecoderConstraintRenderer.resolveMode:52-62` degrades to the configured default with a WARN that logs only the **length**). The "relax the `CHECK` before a fifth mode" note is in the SQL comment but not in `DEPLOYMENT.md` (F-SRO-05). |
| **SOR-21** (metrics keyed on a closed vocabulary) | ✅ **Met** | `MetricsCounters.java:27-33,54-62`: two `ConcurrentHashMap`s whose keys come only from `StructuredResponseParser.FailureKind.name()` and `StructuredOutputMode.name()` (plus the Gateway constant `"KILL_SWITCH_OFF"`) — never a path, project id, backend URL or model string. Maximum cardinality is 4 + 5. Both are surfaced through `MetricsSnapshot`/`MetricsResponse` on the ADMIN-only `GET /metrics`; SR-16's role binding is unchanged and no new endpoint was added. Accuracy nit at F-SRO-17. |
| **SOR-22** (alteration marker on code blocks) | ❌ **Not implemented** — F-SRO-06 | |
| **SOR-23** (`core.quotePath` + 422 documented) | ❌ **Not implemented (docs)** — F-SRO-05 | The **code** half is closed and I verified it directly: `StructuredPathValidator.FORBIDDEN_CHARACTERS = "{}\"\\`[]|*"` rejects the `\` and `"` that `core.quotePath=true` injects (`\303\251.java"`), so a quotePath-mangled name yields `422 STRUCTURED_OUTPUT_UNSUPPORTED` rather than a schema key for a file that does not exist. `StructuredPathValidatorTest` covers it. Only the README/DEPLOYMENT statement of the CI requirement and the "use v2" fallback is missing. |

### Accepted residuals — re-checked, all three still hold as stated

- **`SOR-INH-1`** (non-conformance consumes LLM compute; a persistently failing chunk cascades
  `CANCELLED` to successful siblings). Unchanged in shape and correctly implemented: validation failures
  route through `RetryManager.requeueOrFail` (attempts-bounded, `not_before`-delayed) rather than a
  terminal `FAILED`, and the one deliberate exception — SRO-67b's claim-time
  `COVERAGE_LIST_UNAVAILABLE`, which is **not** requeued because no retry can change a chunk row — is
  implemented exactly to the PMR-09 shape (own `REQUIRES_NEW`, `applyLockTimeout`, job-row lock only,
  re-check `status == RUNNING`, reuse `EventType.FAILED`, Gateway-constant `last_error` prefix). The
  residual is **wider than accepted in one respect** (F-SRO-03 makes ordinary large MRs trigger the
  three-attempt burn rather than only adversarial or misconfigured cases) and **less controllable than
  accepted in another** (F-SRO-12: `SOR-17`'s knob does not exist). Both are filed; the residual itself
  is not re-opened.
- **`SOR-INH-2`** (unpinned llama.cpp with a documented silent fail-open). Unchanged and correctly
  compensated in code: validation is unconditional and mode-independent (`SOR-11` verified above), the
  conservative path alphabet is enforced at the edge (`SOR-01`/SRO-65 verified), and the Gateway still
  never probes a backend's structured-output capability (SRO-09 — `BackendProber` is untouched on this
  branch; grep-verified that no new call reaches the chat-completions endpoint). The **third**
  compensating control, SRO-08's `curl` recipe with its negative control, is documentation and is
  missing (F-SRO-05). F-SRO-02 also hands this third party a `required` array we did not intend, which
  is the one place the residual is currently made worse than accepted.
- **`SOR-INH-3`** (prompt injection can still influence *content*, not *shape*). Unchanged. Worth
  recording that F-SRO-01 is a case where content influence reaches **shape** on the unconstrained
  path — the model can be talked into emitting a duplicate key — which is exactly why F-SRO-01 is
  must-fix rather than a curiosity.

### Non-regression set — spot-checked, all clean

CSR-08/WSR-02 (single-pass substitution, constraint provably outside it — verified under `SOR-07`);
CSR-09/CSR-10 + F-DC-02 (`TextSanitizer` and `ChunkContextRenderer` are **extended**, never weakened:
`StructuredPathValidator` is a separate additive predicate consulted only on the structured edge path,
`sanitizeCodeBlock` **reuses** `TextSanitizer.sanitizeSectionText` rather than forking it, and
`renderStructured` keeps the delimiter tokens and all instruction text as fixed template constants —
`STRUCTURED_COVERAGE_INTRO` at `ChunkContextRenderer.java:55-62` is Gateway-constant and never derived
from path content); CSR-11 (`pathsTrusted` now surfaced on `ChunkPlan` and rejected at the edge for
structured versions); CSR-12 (`CHUNK_AWARE_PROMPT_VERSIONS` extended to `v3`, and `v3.yml` genuinely
contains `{{CHUNK_CONTEXT}}`); CSR-17/18/19 (lock ordering — SRO-36 verified in detail below; the new
`review_chunks` read at `ResultProcessor.java:247` takes **no** lock and joins the existing phase-1
transaction, adding no ordering); CSR-21 (`persistCappedComments` still the review-level cap under the
parent lock — untouched); SR-08/SR-09/F02-04/F02-08 (`CommentParser.sanitize` is byte-identical, merely
refactored to delegate to the new public `sanitizeProseText`; `parse`'s only behavioral change is the
`legacyParseFallback` counter, and `parseStructuredFallback` is a **separate entry point**, so no v1/v2
call site changed); SR-11 (`/jobs/{id}/result` body cap unchanged by `finishReason`); SR-12/SR-14/T-09 +
F-DC-07 + PMR-25 (masking verified on all five DTOs in both modules); SR-16 (no new endpoint, role
matrix untouched); SR-21 (`capRawResponseIfNeeded` still strictly before parsing, now asserted); PMR-09
(fail-closed claim shape faithfully copied twice); PMR-22 (`fromNullable`, never `Enum.valueOf`, on the
new column, the new config value **and** `finish_reason`); PMR-23 (verbatim forwarding, no
substitution); WOR-04 (audit-discriminator grammar preserved); WOR-05 (no exception message reaches an
audit/detail field in either module); WOR-20 (`last_error` still has no client-facing call site —
grep-verified); the §8 diff-chunking byte-compat guarantee (v1/v2 paths pass `maxFilesPerChunk = 0`,
which short-circuits every new bound; the SRO-66a per-section cap cannot move a chunk boundary because
chunk text derives from section *text*, never from `filePaths`; commit `f577670`'s corpus regression
test pins this and is green).

---

## The three items QA escalated

### 1. `STRICT_DUPLICATE_DETECTION` — **a real gap, not an acceptable residual. Filed as F-SRO-01 (MUST-FIX).**

My call, with the reasoning, since QA asked for a decision rather than an opinion:

- **The behavior is exactly as QA described**, and I reproduced it independently rather than trusting the
  test. Against jackson-core **2.17.2** (the version on this classpath), `readTree` on a document with a
  duplicate `files` key returns the **second** object and discards the first entirely — not a merge, not
  first-wins. Coverage validation then validates the survivor and passes.
- **It is not acceptable as a residual.** The threat model's whole §4.2/§5 position is that the *expected*
  set and the *actual* set must both be trustworthy, and that the feature's failure mode must never be
  "no control at all while reporting `COMPLETED`" (`SOT-04`, which produced the BLOCKING `SRO-67`). A
  duplicate key produces precisely that outcome, on the shipped default configuration, with no counter
  and no log line. Accepting it would re-open `SOT-04` through a door `SRO-67` does not cover.
- **The developer's discipline objection is half right, and the half that is right does not bite.** I
  checked Jackson's actual behavior rather than assuming: there is **no** dedicated exception subtype for
  a duplicate key — `JsonReadContext._checkDup` throws a plain `JsonParseException` (which extends
  `StreamReadException`), and the message (`Duplicate field 'files' at [Source: REDACTED …]`) is the only
  thing distinguishing it. So classifying the failure as `SCHEMA_MISMATCH` *in a single-parse design*
  would indeed require message inspection, and WOR-05/F02-03 correctly forbid that. But that is a
  constraint on the **classification**, not on the **control**: enabling the feature and letting the
  existing `catch (JsonParseException …)` produce `NOT_JSON` closes the security gap with **no** message
  handling at all. `NOT_JSON` is already retryable, already counted per kind, already surfaced in
  `last_error`. The only cost is one bit of diagnostic precision, which a javadoc line covers.
- **If that precision is wanted, there is a typed, message-free way** (given in F-SRO-01's remediation):
  a duplicate-key document is by definition one that a *strict* parse rejects and a *lax* parse accepts.
  Two parses of an already-capped string, on the failure path only, with no string inspection anywhere.
- **Sequencing matters.** Enabling strict detection while F-SRO-02 is open turns a silent bug into a
  deterministic, filename-triggered `FAILED` + sibling cascade. Land the two together.

### 2. The stale-Hibernate-cache bug class — **QA's fix is correct; I found no second live instance.**

The fix in `cbdf5e7` is right and for the right reason. `QueueManager.submitResult` is `@Transactional`
and loads the `Review` into its persistence context one line before calling `ResultProcessor.process`
(`QueueManager.java:553-563`); `process` is deliberately non-transactional, so a plain
`reviewRepository.findById` inside it joins that ambient context and Hibernate returns the **managed,
now-stale** instance from the first-level cache rather than re-reading — even though `RetryManager`'s
`REQUIRES_NEW` transaction has already committed a newer status. Running the read through the same
`requiresNewTransactionTemplate` (`ResultProcessor.java:168-172`) suspends the outer `EntityManagerHolder`
and gives the read a brand-new persistence context, which is a genuine re-read, not a hopeful one.

I audited every other site with the same shape — an outer transaction that reads an entity, calls
something that commits in `REQUIRES_NEW`, then reads or returns that entity — across the whole codebase,
not just this feature:

- **`QueueManager.submitResult`** (`:537-565`) — the caller. Its `idempotentNoop` branch reads
  `review.getStatus()` **before** any inner transaction runs, so it cannot be stale; its `accepted`
  branch now takes the status from the fixed `currentReviewStatus`. **Clean.**
- **`QueueManager.heartbeat`** (`:503-531`) — `@Transactional`, but opens no inner transaction at all.
  **Clean.**
- **`QueueManager.claim`** (`:130-179`) and **`reportFailure`** (`:591-…`) — both deliberately
  *non*-`@Transactional` orchestrating methods; every step is its own `REQUIRES_NEW`, and the returned
  values come from the step outcomes (`ClaimAttempt`, `RequeueOutcome`), never from a re-read entity.
  **Clean by construction.**
- **`RetryManager.requeueOrFail`** (`:109-120`) — non-transactional; hands `outcome.reviewId()` (a `Long`)
  to `ChunkCoordinator`, reads no entity afterwards. **Clean.**
- **`ChunkCoordinator.recomputeAndApply` / `completeChunkAndRecompute`** (`:115-143`) — both bodies run
  wholly inside their own `REQUIRES_NEW` template, so `findByIdForNoKeyUpdate` executes against a fresh
  persistence context. This matters more than it looks: Hibernate resolves a *query* result to an
  already-managed instance without refreshing its state, so had these joined an ambient transaction the
  `FOR NO KEY UPDATE` would have returned stale field values despite taking a real row lock. They do not.
  **Clean.**
- **`GitLabPublisher.publishReview`** (`:62-88`) — `@Transactional(readOnly = true)`, loads `review`, then
  runs N `publishOneComment` and one `finalizePublished` in `REQUIRES_NEW`. This is the closest
  structural match to the QA bug, and it is **correct**: `finalizePublished` re-reads by id *inside* the
  new transaction (fresh context), and the outer stale `review` is thereafter used only for
  `getProjectId()`/`getMergeRequestId()`, both immutable. **Clean.**
- **`TimeoutManager.sweepStaleHeartbeats` / `enforceMaxDuration`** (`:47-77`) — `@Transactional(readOnly
  = true)` around a `retryManager.requeueOrFail` loop, but they only ever handle `Long` ids and return a
  count. **Clean.**
- **`ReviewService.createReview`** (`:140-190`) — non-transactional; `sweepObsolete` and
  `persistNewReview` are each `REQUIRES_NEW`, and `created` comes *out of* the inner transaction rather
  than being re-read after it. The one historical instance of this bug class here (F-DC-12,
  `sweepObsolete`'s re-check reading a stale entity) is still fixed. **Clean.**

One structural note that is not a finding: `submitResult` being `@Transactional` while everything inside
it is `REQUIRES_NEW` means the outer transaction holds a Hikari connection purely as overhead across
three nested inner transactions (phase 1, `ChunkCoordinator`/`RetryManager`, and now the
`currentReviewStatus` re-read), i.e. two pool connections are held simultaneously for the duration of
each result submission. `reportFailure`'s own javadoc explains why *it* is deliberately not
`@Transactional` for exactly this reason. The QA fix does **not** deepen the nesting (its transaction
runs strictly after phase 1 has committed), so this is pre-existing and unchanged — but if
`submitResult` is ever revisited, dropping its `@Transactional` would both remove the connection
doubling and make the whole bug class structurally unreachable there.

### 3. The two flaky tests — **QA is right: they predate the feature and this feature cannot reach them. Refuted as feature-caused.**

I confirmed this structurally rather than by re-running (both passed in my run anyway, 776/776 green):

- **`ReviewRepositoryConcurrentClaimTest.nThreadsRacingOverNQueuedReviewsEachClaimExactlyOneDistinctRow`**
  — `git diff 1957f2b..HEAD -- src/main/java/com/review/gateway/repository/` is **empty**. The
  `SELECT … FOR UPDATE SKIP LOCKED` claim query, its predicate, its ordering and every other repository
  method are byte-for-byte unchanged by this branch, and the test exercises the repository directly with
  no service in the path. It is structurally impossible for this feature to affect it.
- **`ClaimCancelObsoleteConcurrencyTest.multiChunkConcurrentResultSubmitAndCancelNeverDeadlockOrLeakAnUnhandled500`**
  — the test file itself, `ChunkCoordinator` and `RetryManager` are all untouched by this branch
  (`git diff --stat` verified). The scenario uses `promptVersion: v2`, so
  `StructuredOutputSupport.isStructured` is `false` at every decision point:
  `ResultProcessor.processJobPhase:204` routes to `processLegacyJobPhase`, the new
  `validationFailure`/`RetryManager` branch in `process` is unreachable, `QueueManager.claimJobRow`'s
  `structuredVersion` block is skipped, and `buildChunkContext` takes the identical
  `chunkCount > 1 → render()` path as before. I checked the three places where v1/v2 behavior *does*
  change and none of them touches locking: (i) `parseFilePaths` now runs unconditionally at claim time
  rather than only when `chunkCount > 1` (a pure CPU/JSON change, no query, no lock); (ii)
  `CommentParser.parse` increments an in-memory counter; (iii) `ResultProcessor.currentReviewStatus`
  opens one extra short transaction per result submission — but it is a plain `SELECT` with **no**
  `FOR UPDATE`, and under PostgreSQL MVCC a plain read never blocks behind a row lock, so it cannot add
  contention to the `cascadeCancelSiblings`-vs-result race the test targets, and it runs strictly after
  phase 1 has released the job lock. The test's own remaining nondeterminism is documented in its source
  (`:197-209`, the F-PM-12 capacity-leak arithmetic: an iteration where the cancel loses the lock race
  leaves chunk 2 `RUNNING` and permanently consumes one unit of backend capacity, so a later iteration
  can starve on `204`). That is a test-arithmetic residual from the Prompt Manager round, not a
  production race and not this feature's. **Confirmed as QA stated.** Both are still worth de-flaking,
  but on their own ticket.

---

## Other positive verifications (general SAST pass)

- **Injection.** No new query anywhere: `git diff` shows zero changes under `repository/`, and the only
  new SQL is the V5 DDL (static, no parameters). The `SET LOCAL lock_timeout` native queries are
  constants. Semgrep `p/sql-injection` over both modules: 0 findings. The JSON Schema — the one new
  document assembled from attacker-influenced strings — is built exclusively from Jackson `ObjectNode`
  trees and serialized with `writeValueAsString` (`ReviewSchemaBuilder.java:87-92`, and the same
  discipline is voluntarily extended to `DecoderConstraintRenderer`'s wrapper at `:98-116`); I grepped
  the class for `StringBuilder`/`String.format`/`+`-concatenation of JSON and there is none, so
  `SOR-05a` is met and JSON escaping of a path is structural. `ReviewSchemaBuilderTest` pins this with an
  architecture assertion.
- **Access control.** No new endpoint, no change to `SecurityConfig`, no change to the SR-16 role matrix.
  The two new claim-time fail-closed paths and the new result-time branch all sit behind the existing
  `WORKER` role. `POST /jobs/{id}/result`'s response body still carries no `last_error` (WOR-20) and its
  ownership-mismatch branch still returns an outcome indistinguishable from `NOT_FOUND` (F02-05) — the
  new `finishReason` field changed neither.
- **Resource exhaustion.** Mostly good, with F-SRO-04 the exception. Bounded and verified: the schema is
  byte-capped before dispatch (65 536) with a job-failing backstop; the Worker independently caps the
  constraint (69 632 UTF-8 bytes, pre-parse); the parser's `StreamReadConstraints` bound nesting, name
  length and string length; the coverage list is bounded by construction at the edge (SRO-66b rejects an
  over-large or truncated list with `422 DIFF_TOO_LARGE` **before** any Review, chunk or job is created —
  I traced `enforceStructuredCoverageBounds` and it runs immediately after `parseSections`, before the
  single-chunk shortcut and before packing, exactly as specified); `Section.addPath` bounds accumulation
  unconditionally at 64 (the F-DC-01 lesson applied at the right layer this time); the two metrics maps
  have a maximum cardinality of nine.
- **Information disclosure.** Every new log statement in both modules passes ids, counts, lengths, byte
  counts, Gateway-constant text or `getClass().getSimpleName()` — I read all of them
  (`CommentRenderer` ×3, `DecoderConstraintRenderer` ×2, `QueueManager`'s new block ×4,
  `ResultProcessor`'s new branches ×5, `DecoderConstraintResolver`'s exception paths, `LlamaClient`).
  Nothing logs a schema, a diff, a prompt, a rendered body, a file path or a response. Masking is present
  on all five DTOs that gained fields and is regression-tested in both modules. The `422` messages for
  SRO-16/17/65 name only the property or character class, never the offending path — with the single
  exception filed as F-SRO-09.
- **Error handling.** `OnInvalidResponse.fromNullable` and `StructuredOutputMode.fromNullable` are used
  everywhere (never `Enum.valueOf` on DB or config text), with a startup fail-fast on a typo'd
  `on-invalid-response` *and* a runtime defensive default — the belt-and-braces split PMR-22 established.
  `DecoderConstraintRenderer.render`'s unreachable re-parse failure degrades to "no constraint" rather
  than failing the claim, which is the correct direction given validation is unconditional.
- **The SRO-68 fallback is genuinely restricted, and I attacked it specifically.**
  `parseStructuredFallback` is a **separate entry point** that returns `List.of()` — never the
  raw-transcript placeholder, never the empty-response or no-publishable-content placeholders — when
  `tryParseJsonArray` yields nothing (`CommentParser.java:110-146`), and `ResultProcessor.java:305-315`
  treats an empty return as "fail exactly as `RETRY_THEN_FAIL`". So the `{"files":{…}}`-shaped response
  that `SOT-05` showed would *always* publish the raw transcript now publishes nothing. Anything the
  fallback does publish is prefixed with a Gateway-constant `**UNVALIDATED**` first line
  (`ResultProcessor.java:61-62,327-336`) at position 0, before any model text. It fires only on the last
  attempt, inside the job-row lock, and transitions `RUNNING → COMPLETED` directly — deliberately never
  as a `FAILED → COMPLETED` resurrection after `RetryManager` has committed a terminal state, which
  `JobStateMachine` would refuse. It logs WARN and increments `structuredFallbackUsed`. It also correctly
  applies `fairShareCommentCap` (which the main structured path does not — F-SRO-04). The only gap is
  SRO-68d's documentation (F-SRO-05).
- **SRO-36 lock ordering.** Verified line by line and it is right: `processJobPhase` returns a
  `JobPhaseOutcome.validationFailure(...)` and **leaves the job `RUNNING`** (`ResultProcessor.java:294-297`
  — no `jobStateMachine.transition`, no `save`), and `process` — which has no `@Transactional` of its own
  — calls `retryManager.requeueOrFail` only at `:140`, after the phase-1 template has returned and
  committed. `RetryManager` then takes the same job-row lock in its own `REQUIRES_NEW` and does its own
  CSR-17 parent recompute. `applyLockTimeout()` (`SET LOCAL lock_timeout = '3s'`) is present on both
  sides. `StructuredValidationRetrySweepConcurrencyTest` (205 lines, Zonky, real HTTP) exercises a
  validation failure racing a stale-heartbeat sweep on the same job and asserts exactly one `RETRY`
  event — the T-4.8 regression QA was asked for, and it is a genuine test, not a tautology.
- **Config drift (the F-DC-04/F-PM-02 lineage).** I diffed `application.yml`'s `gateway.structured.*`
  and `gateway.review.*` blocks against the Java defaults field by field: all 15 values agree, and
  `gateway.diff.max-paths-per-section: 64` agrees with `GatewayProperties.java:565`.
  `ApplicationYamlBootTest` boots the real shipped file and now exercises the new startup validation
  against it. The three new env-var placeholders bind correctly; they are simply undocumented
  (F-SRO-05).
- **Dependencies / secrets / CI.** Zero-byte `pom.xml` diff in both modules — the "no new dependencies"
  claim is literally true, and the hand-rolled validator genuinely replaced a JSON-Schema library.
  `gitleaks` over 122 commits → no leaks. `semgrep` gate config → 0 findings.
  `.github/workflows/security-gate.yml` untouched, so SR-23 covers this branch unchanged. The threat
  model's three suggested Semgrep rules were not added; that is optional and I am not filing it (the
  properties they would guard are covered by the architecture tests in `ReviewSchemaBuilderTest` and
  `StructuredResponseParserTest`, and by this review).

---

## Must-fix list for the next dev pass

1. **F-SRO-02 first**, then **F-SRO-01** — in that order, in the same commit. F-SRO-02 is the edge check
   (`distinct` comparison + builder invariant + iterate `expectedSet` + `trim()` the `diff --git`
   branch); F-SRO-01 is the parser flag. Enabling strict duplicate detection before the duplicate-key
   *source* is closed converts a silent-loss defect into a deterministic, filename-triggered `FAILED` +
   sibling cascade.
2. **F-SRO-03** — thread the structured answer reserve and the computed coverage reserve into
   `DiffSizeValidator.budgetTokens`/`DiffChunker.split` (keeping the existing overloads so v1/v2
   arithmetic stays byte-identical), extract the `coverageReserveTokens` formula to one shared place so
   the startup check and the runtime cannot drift, and apply it to the single-chunk shortcut too.
3. **F-SRO-04** — apply `fairShareCommentCap` on the structured path, and hoist the per-finding
   whole-diff splitting out of the render loop (and preferably out of the locked transaction).
4. **F-SRO-05** — architecture §14's documentation, with the six doc-only TRACKED requirements
   (`SOR-17`, `SOR-19`, `SOR-20`, `SOR-23`, SRO-08's recipe + negative control, SRO-68d) as the
   acceptance criteria, plus `SOR-08`'s Workers-first deployment order and the two cross-module
   couplings.
5. **F-SRO-06 … F-SRO-10** while in the same files — all five are small and three of them
   (F-SRO-07, F-SRO-08, F-SRO-10) touch code the must-fixes already open.

F-SRO-11 … F-SRO-18 are Info-grade; none blocks. F-SRO-14 must be resolved **before** SRO-26 is ever
wired up, and F-SRO-15 should be fixed in V4 and V5 together.

---

## Bottom line

`feature/structured-review-output` is **not yet ready to merge to `master`**, but it is close and the
remaining work is mechanical. The parts that are genuinely hard to get right — the Worker-side untrusted
blob handling, the Jackson-structural schema construction, the un-shortcuttable validator, the
fail-closed claim paths, the SRO-36 lock ordering, the restricted fallback, the closed-vocabulary
metrics, the rollback-tolerant migration and the fail-fast startup budget check — are all done, and done
to the standard the previous three rounds set. There is no injection surface, no access-control change,
no information disclosure, no dependency delta, and no logging-discipline regression anywhere in
+5558 lines.

What blocks is that the feature's headline guarantee — *"a response literally cannot omit or invent a
file"* — has two remaining silent bypasses, both reachable in the shipped default configuration and both
influenceable by the MR author (a duplicate JSON key, F-SRO-01; a sanitization-collided file name,
F-SRO-02); that the token budget the branch computes at startup is never the one it uses at runtime
(F-SRO-03); that the result path renders unboundedly inside a job-row lock and lost its per-chunk
comment cap (F-SRO-04); and that none of the operational documentation this feature depends on to be
turned on safely was written (F-SRO-05).

Send it back for one more `backend-developer` pass on F-SRO-01 … F-SRO-05 (with F-SRO-06 … F-SRO-10
riding along in the same files), then a short appsec re-verification round focused on those five and on
the new tests. Nothing else in this branch needs to be revisited.
