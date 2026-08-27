# Structured Review Output — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**. No code for this feature exists on `feature/structured-review-output`
(the branch carries the architecture document only). This model threat-models the approved-in-draft
`docs/structured-review-output-architecture.md` (`SRO-01..SRO-63`): the Gateway-computed per-chunk JSON
Schema (Part 1/2), unconditional Gateway-side validation with retryable failure (Part 3), and the
Gateway-owned fixed comment template with its new code-content sanitizer (Part 4).

It **extends** `docs/threat-model.md` (`SR-01..SR-24`), `docs/worker-threat-model.md`
(`WSR-01..WSR-18`), the diff-chunking controls in `docs/implementation-architecture.md` §14
(`CSR-05/08/09/10/11/12/14/17/18/19/21`), `docs/prompt-manager-threat-model.md` (`PMR-01..PMR-30`) and
`docs/worker-observability-and-claim-latency-threat-model.md` (`WOR-01..WOR-20`). It rewrites none of
them. Requirement prefix per the architecture doc's §0: threats **`SOT-nn`**, requirements
**`SOR-nn`**. `SRO-nn` ids are referenced, never renumbered or duplicated.

Methodology: STRIDE per element/flow + OWASP Top 10 (2021) + OWASP LLM Top 10 (LLM01 prompt injection,
LLM05 improper output handling) + CWE. Risk = qualitative Likelihood × Impact (High/Medium/Low).

**Gating column (`Gate`), per the explicit instruction for this round:**

| Gate | Meaning |
|---|---|
| **BLOCKING** | Must be resolved **in the architecture document, before `backend-developer` starts**. These are design decisions the developer cannot legitimately make alone. |
| **CRITICAL** | Must be fixed **during implementation**; gates the post-implementation SAST round (`docs/security/feature-structured-review-output-sast-report.md`, prefix `F-SRO-`). |
| **TRACKED** | Recorded for the SAST round to verify; does **not** gate moving forward now. |

### Framing note that drives most of the ratings

This feature converts an **MR-author-controlled string — a file path — into part of the definition of a
security control.** Until now a path was cosmetic: it was sanitized (CSR-09/CSR-10/F-DC-02), rendered
into a delimited prompt block, and stored. From this branch on, the *same* string is (a) a JSON Schema
object key, (b) a GBNF literal inside a sampler constraint on a third-party server, (c) the key the
model must reproduce **byte-for-byte** or the job fails, (d) the file attribution of a published
comment, and (e) part of a Gateway-authored Markdown header. Five different grammars, one attacker-
supplied string, and only one of the five (the prompt block) is currently hardened for it.

The second framing point: **the outcome the design optimizes for — "a non-conforming response is a
retryable failure, not a silent degradation" — also creates a new, cheap, attacker-reachable way to
make a Review fail permanently.** `ChunkCoordinator` cascades a `FAILED` chunk into `CANCELLED`
siblings, and `FAILED` is terminal for the dedup key. So anything an MR author can do to guarantee a
validation failure is an **AI-security-review suppression primitive on their own merge request** — which
is precisely the end state `docs/prompt-manager-threat-model.md` §1 identified as the one that actually
matters for this system. Four of the five BLOCKING findings below are instances of that shape, and
three of them are reachable *without any adversarial intent at all* (a non-ASCII filename; forty files
in one chunk; a single-chunk Review).

---

## 1. Decomposition — new elements, boundaries, flows

### New / changed elements

| Element | Change | Module |
|---|---|---|
| `ReviewSchemaBuilder` | **New**, pure function `(List<String> paths, SchemaOptions) -> String` (SRO-18) | Gateway |
| `DecoderConstraintRenderer` + `DecoderConstraint` | **New**, wire-shape adaptation per backend (SRO-05/06) | Gateway |
| `StructuredOutputMode` + `backends.structured_output_mode` | **New** column (V5), parsed via `fromNullable` | Gateway |
| `JobPayload.responseFormat` / `.jsonSchema`, `ClaimedJob` (mirror) | **New** nullable fields on the claim contract | Gateway |
| `worker/.../gateway/dto/JobPayload` (mirror) | Same two fields | Worker |
| `StructuredResponseParser` | **New**, strict non-fallback parse + coverage validation (SRO-30/31/32) | Gateway |
| `ResultProcessor.processJobPhase` | New `validationFailure(...)` outcome; `RetryManager` called from `process` (SRO-36) | Gateway |
| `CommentRenderer` + `sanitizeCodeBlock` + diff-context extractor | **New** publish-path rendering, **HTML escaping deliberately omitted for code** (SRO-50/51/55) | Gateway |
| `review_results.finish_reason` (V5) + `finishReason` on the result DTO chain | **New** | both |
| `DiffChunker.split(diff, promptTokens, maxFilesPerChunk)`, `ChunkPlan.pathsTrusted` | Changed signature/shape (SRO-14/16) | Gateway |
| `StructuredOutputUnsupportedException` → `422 STRUCTURED_OUTPUT_UNSUPPORTED` | **New** error code | Gateway |
| `ChatCompletionRequest.response_format` / `.json_schema`, `worker.limits.max-constraint-bytes`, `CONSTRAINT_INVALID` | **New** outbound request fields + bound + abandonment reason | Worker |
| `worker/src/main/resources/prompts/v3.yml` | **New** template, `maxTokens: 8192` | Worker |
| `MetricsCounters` / `MetricsSnapshot` | 4 new counters (SRO-45) | Gateway |

### New trust boundaries (delta on `threat-model.md` §1, `prompt-manager-threat-model.md` §1)

| # | Boundary | Channel | Trust posture |
|---|---|---|---|
| **SOTB-KEY** | MR-author-controlled file path → JSON Schema key → llama.cpp `json_schema_to_grammar` → GBNF literal → sampler | in-process, then HTTPS to the backend | **New and the important one.** Untrusted text becomes structure in a *third-party* parser whose failure mode is documented as **silent fail-open** (llama.cpp #19051). The Gateway cannot observe whether the control it shipped was applied. |
| **SOTB-CONSTRAINT** | Gateway → Worker → `POST {backend}/v1/chat/completions` | claim payload, then outbound HTTP | **New.** The Worker, which by design decides nothing, now injects a Gateway-supplied opaque blob into an outbound request body. First "carry this and attach it" data flow in the system. |
| **SOTB-COVERAGE** | `review_chunks.file_paths` (DB) → both the prompt *and* the schema *and* the validation predicate | in-process, at two different times (claim vs. result) | **New.** The control's *definition* is read at claim time and its *enforcement* at result time, from the same column but through two code paths. Divergence between them = the control silently evaluates to "nothing". |
| **SOTB-CODEBLOCK** | model `suggestion` + this MR's own diff → GitLab MR note **without `htmlEscape`** | HTTPS to GitLab | **New.** The first publish path in this system that deliberately skips the SR-08/SR-09 escaping pipeline. Fence integrity replaces escaping as the sole control. |
| **SOTB-MODE** | `backends.structured_output_mode` (DB row, admin-controlled) → whether the control is applied at all | DB | **New.** A single `UPDATE` turns the decoder-level half of the control off fleet-wide, with no deploy, no event and (as designed) no per-Review record. Same shape as PMTB-FMT. |

### Flow additions

```
 POST /reviews {promptVersion: "v3", diff}
   │
   ├─ DiffChunker.split(diff, sysTokens, maxFilesPerChunk)      ── paths extracted from header lines
   ├─ SRO-16/17 edge validation (pathsTrusted, dropped paths)      (unbounded per section — SOT-03)
   └─ persist review_chunks.file_paths (SANITIZED, not allowlisted — SOT-02/07/15)
                                    │
 POST /jobs/claim                   ▼
   ├─ paths = parseFilePaths(chunk.file_paths)   ── silent List.of() on malformed JSON (SOT-04)
   ├─ chunkContext = ChunkContextRenderer.render(...)  ── null if chunkCount==1;
   │                                                     "... and N more" truncation (SOT-01)
   ├─ schema = ReviewSchemaBuilder.build(paths, opts)          ──► SOTB-KEY
   └─ constraint = DecoderConstraintRenderer.render(schema, backend.mode)   ──► SOTB-MODE
                                    │  payload.responseFormat | payload.jsonSchema
                                    ▼
 Worker: size-check → readTree → attach VERBATIM to POST /v1/chat/completions   ──► SOTB-CONSTRAINT
                                    │
                          llama-server: schema → GBNF → sampler
                             (fail-open on grammar parse error, HTTP 200)
                                    │  rawResponse + finish_reason
                                    ▼
 POST /jobs/{id}/result ─► ResultProcessor.processJobPhase (job-row lock)
     ├─ capRawResponseIfNeeded  (MUST stay before parsing — SRO-32/TRUNCATED)
     ├─ store raw (first attempt only — SOT-19)
     └─ StructuredResponseParser.validate(raw, expectedPaths)   ──► SOTB-COVERAGE
            ├─ ok   ─► CommentRenderer (header + prose + ```diff + ```suggestion-block)  ──► SOTB-CODEBLOCK
            └─ fail ─► (commit) ─► RetryManager.requeueOrFail   ─► attempts exhausted ─► FAILED
                                                                  └─► ChunkCoordinator CANCELs siblings
```

**The chain that matters:** *name a file a certain way → the schema key and the prompt's file list
disagree, or the schema cannot be built → every attempt fails validation → 3 attempts → `FAILED` →
every sibling chunk `CANCELLED` → the MR gets no AI review, and the dedup key blocks a new Review until
CI re-runs.* Cost to the attacker: one file name. That path did not exist before this branch, because
before this branch nothing about the model's output was allowed to fail a job.

---

## 2. Assets (delta)

| # | Asset | C | I | A | Where | Notes |
|---|---|:-:|:-:|:-:|---|---|
| **SOA1** | The **coverage guarantee** itself (every file in the chunk was actually reviewed) | — | **H** | M | schema key set × validation predicate | This is the product of the feature. It is only as strong as the weakest of: the prompt's file list, the schema's key set, the validator's expected set. Any two of them disagreeing silently destroys it (SOT-01/02/04). |
| **SOA2** | Review completability + fleet LLM compute | — | **H** | **H** | `review_jobs.attempts`, 1–10 backends, tens-of-minutes generations | Newly consumable by *model output*, not just infrastructure failure. One MR can now cost `max-attempts × chunks` full generations (SOT-14), and a persistent per-chunk failure `CANCEL`s already-completed siblings. |
| **SOA3** | Published MR-comment integrity | L | **H** | — | `CommentRenderer` → GitLab | First body assembled from four sources (Gateway constants, model prose, model code, the MR's own diff) with **two different sanitization pipelines**. Fence integrity is now load-bearing (SOA3 ← SOTB-CODEBLOCK). |
| **SOA4** | This MR's diff, republished into MR notes | M | M | — | diff-context block | Not a new confidentiality domain (it is the author's own MR), but it is a new *egress* of stored diff bytes out of the Gateway on the publish path, and the first place chunk content is echoed back. |
| **SOA5** | Audit trail (`review_jobs.last_error`, `review_events.details`, `review_results.finish_reason`) | L | **H** | — | Postgres | Now carries **model-chosen strings** (unexpected `files` keys) in addition to WOC's Worker-supplied `detail`. WOR-04's positional discriminator must survive a second untrusted contributor. |
| **SOA6** | The constraint artifact (schema / `response_format`) in flight | L | **H** | M | claim payload → Worker → backend | An integrity asset that transits an untrusted-ish hop. Also a **confidentiality** item: it enumerates every changed file path in the MR, so it inherits `JobPayload`'s masking obligations (CSR-14/PMR-25/F-DC-07). |
| WA1/WA2/WA3, PMA1–PMA7 (inherited) | tokens / diff / rawResponse / prompt sections | | | | | Unchanged; `answer-reserve` interacts with PMA6 (SOT-11). |

---

## 3. STRIDE threats — SOT-01..SOT-24

"New" = introduced by this branch. "Amp" = pre-existing residual this branch amplifies. "Found" =
pre-existing defect found while reviewing code this branch touches.

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | Gate |
|----|--------|-------------|-----------|----------|:---:|:---:|
| **SOT-01** | Tampering / DoS | CWE-636 (fail-open), CWE-1288, CWE-682 / LLM01, A04 | `ChunkContextRenderer` × `ReviewSchemaBuilder` × `v3.yml` | **The model is not reliably shown the file list the schema requires it to key on.** Two independent mechanisms. (a) `QueueManager.buildChunkContext` returns **`null` when `chunkCount <= 1`** — for a single-chunk Review (the common case at 20–30 MRs/day) the v3 prompt's `{{CHUNK_CONTEXT}}` is empty, yet `v3.yml`'s user block says *"one entry under `files` for every file listed above"*. There is no "above". (b) For multi-chunk Reviews, `ChunkContextRenderer.appendFileBlock` **drops paths** past `gateway.diff.max-chunk-context-chars` (**default 1000**) and emits `"... and N more"` — while `gateway.structured.max-files-per-chunk` defaults to **40**. 40 paths × ~30 chars is already 1.2 KB *before* the `OTHER_FILES` block and the ~230-char fixed intro, so at the shipped defaults the list is **guaranteed** to be truncated. Consequence: with the decoder constraint honored the model is forced to emit keys it was never shown (quality damage, and the per-file `summary` anti-skip device of SRO-25 becomes noise); with `structured_output_mode = OFF` — which is **stage 0 and stage 1 of the architect's own rollout ladder**, plus the SRO-39 kill switch, plus every fail-open — the model *cannot* produce the required key set, so **every v3 Review deterministically hits `COVERAGE_SHORTFALL`, burns three attempts and ends `FAILED` with its siblings `CANCELLED`.** It also makes stage 1's "how often does the model conform without decoder help" measurement meaningless (it measures 0%). | **High** | **BLOCKING** (New) |
| **SOT-02** | Tampering / DoS | CWE-436 (interpretation conflict), CWE-707 / LLM01, A04 | `PromptTemplateService` (CSR-08 strip) × `ReviewSchemaBuilder` | **The path in the prompt and the path in the schema can be different strings.** The Worker strips literal `{{` and `}}` from `chunkContext` (CSR-08 defense-in-depth, `PromptTemplateService.resolve`), and the Gateway does **not** strip them from `review_chunks.file_paths`. A file named `{{DIFF}}Helper.java` is therefore shown to the model as `DIFFHelper.java` but is `required` in the schema as `{{DIFF}}Helper.java`. Under a honored constraint the model emits the schema key (survivable); under `OFF`/fail-open it emits what it was shown ⇒ one missing key **and** one unexpected key ⇒ `COVERAGE_SHORTFALL` on every attempt ⇒ `FAILED` + sibling cascade. **An MR author suppresses the AI review of their own MR by naming one file.** `sanitizePath` strips only Cc/Cf/Zl/Zp and `<`/`>`, so `{`/`}` pass through today by design. The same divergence class covers any transform applied on one side only (the CSR-10 dedup, the render order, the SOT-01 cap). | **High** | **BLOCKING** (New) |
| **SOT-03** | DoS | CWE-770, CWE-405 (amplification), CWE-1284 / A04 | `DiffChunker.Section` × `SRO-14`/`SRO-15` | **The coverage list is not bounded where it is produced, so `max-files-per-chunk` cannot enforce it.** `Section.extractPathFromHeaderLine` appends a path for **every** `+++ ` line in a section's header region (before the first `@@`) — there is no per-section bound. A crafted section of ~10 bytes per line yields thousands of paths from a few KB of diff, and `binPack` can only bound file count *across* sections: a single section that alone exceeds `max-files-per-chunk` cannot be split by any packing decision, so SRO-14 as specified has no defined behavior for it. The amplification is roughly **40× diff-bytes → schema-bytes** (a fully-inlined per-file object is ~400 bytes), and a further multiple schema → GBNF on the backend. Outcome at the shipped defaults: `SRO-15`'s `max-schema-bytes` refuses the schema **at claim time** — i.e. *after* `POST /reviews` already answered `200 QUEUED` — the job fails, retries, and ends `FAILED`. A ~4 KB diff therefore buys a permanently failed Review, past the edge validation, in violation of the project's own "fail fast at the edge" principle. (Directly parallel to F-DC-01, one feature later: the bound is evaluated at the wrong layer.) | **High** | **BLOCKING** (New) |
| **SOT-04** | Tampering / Repudiation | CWE-636 (fail-open), CWE-754 / A04, LLM01 | `QueueManager.parseFilePaths` → `ReviewSchemaBuilder` → `StructuredResponseParser` | **An empty coverage list is a valid schema that validates successfully with zero coverage.** `parseFilePaths` returns `List.of()` with only a WARN when `review_chunks.file_paths` is null/blank/unparseable, and `buildChunkContext` already tolerates that today (it just renders no paths). Fed into `ReviewSchemaBuilder`, that produces `"files": {"type":"object","required":[],"properties":{},"additionalProperties":false}` — a *well-formed* schema that constrains the model to `"files": {}`, which then passes the coverage check (expected set ∅ == actual set ∅), produces zero comments, and completes the job **green**. A security control silently degrading to "no control" while reporting success — the exact PMT-06 shape, for which PMR-09 already established the fail-closed answer (`prompt_bundle_mode` + "REPO with zero sections ⇒ fail the job"). SRO-16/17 close the *creation-time* routes into this state; nothing closes the claim-time and validation-time routes, and those are the ones that decide. | **High** | **BLOCKING** (New) |
| **SOT-05** | Tampering / Info disclosure | CWE-20, CWE-116, CWE-1287 / LLM05, A03 | `SRO-38` `RETRY_THEN_FALLBACK` × `CommentParser` | **The escape hatch is a downgrade to today's raw-transcript-as-comment path, on a code path an attacker can force.** Two compounding facts the architecture doc does not state. (a) On a **v3-shaped** response `CommentParser.extractJsonArraySlice` (first `[` → last `]`) slices from inside the first `findings` array to the last one; that slice is not an array, so `tryParseJsonArray` returns `null` and the fallback lands on `List.of(new RawComment(null, null, INFO, rawResponse))` — i.e. under v3 the fallback **essentially always publishes the entire raw model transcript as one comment**, not a parsed comment list. (b) Reaching the fallback is attacker-controllable: SOT-02/SOT-03 force it for free, and prompt injection in the diff (LLM01, already an accepted residual) forces it directly. The published transcript is protected only by `CommentParser.sanitize`, which strips quick actions, neutralizes mentions and HTML-escapes but — **verified in code** — does *not* strip Cc/Cf, so bidi/Trojan-Source text in the transcript reaches the MR note. Net: an MR author can convert this feature's "never degrade silently" property back into "always degrade silently, and publish my text", while the Review reports success. | **High** | **BLOCKING** (New) |
| **SOT-06** | Tampering | CWE-116, CWE-1287, CWE-79 (residual) / LLM05, A03 | `CommentRenderer` / `sanitizeCodeBlock` (SRO-55/57) | **Unescaped publishing: the fence is now the whole control.** SRO-55's reasoning is correct as far as it goes — HTML entities are not decoded inside a CommonMark fence, so escaping there corrupts and protects nothing, and GitLab's reference filters do not resolve `@mentions` inside `pre`/`code` — but both properties are **conditional on the fence surviving intact all the way to GitLab's parser**, and this design gives that invariant three separate ways to break: (i) `SRO-53`'s cap can still cut a *prose* block after the code block, or cut the code block's own content if the cap is applied to a block rather than by dropping it, leaving the closing fence off (the F02-08 lesson — "the cap must be applied to the thing that actually ships" — one layer up); (ii) backtick-run collapsing applied *after* capping re-admits a `\`\`\`\`` run at the truncation boundary; (iii) any future edit to the template. Once the fence is open, unescaped `<script>`, `<img onerror=…>`, `@here`, `/close` and arbitrary Markdown are live in a Gateway-authored, trusted-looking comment. GitLab's own HTML allowlist makes stored XSS unlikely, so this is rated Medium-High rather than High — but the mitigation is nearly free and the failure is silent. | **Medium-High** | **CRITICAL** (New) |
| **SOT-07** | Tampering | CWE-116, CWE-1287 / LLM05 | `CommentRenderer` header (SRO-50) | **Markdown injection via the file path in the fixed header.** `TextSanitizer.sanitizePath` strips Cc/Cf/Zl/Zp and `<`/`>` **only** — backtick, `[`, `]`, `(`, `)`, `|`, `*`, `_`, `#` and `!` all survive, and the path is not HTML-escaped on the v3 path the way `CommentParser.sanitizeFilePath` escapes it on v1/v2. SRO-50 renders it inside an inline code span (`` `src/A.java` ``); a file named ``a`.java`` closes the span, and everything after it in a **Gateway-authored constant header** becomes live Markdown — link injection (`[click here](https://evil)`), table/emphasis forgery, or a forged second header line that appears to come from the Gateway. Not XSS; a credibility/phishing surface on the one part of the comment a reviewer is meant to trust. Also note the inconsistency it creates: `review_comments.file_path` would hold an unescaped value for v3 and an escaped one for v1/v2. | **Medium** | **CRITICAL** (New) |
| **SOT-08** | DoS | CWE-1284, CWE-131 (off-by-wrapper) / A04 | `worker.limits.max-constraint-bytes` (SRO-13) × `gateway.structured.max-schema-bytes` (SRO-15) | **The two bounds measure different quantities and are set to the same number (65536).** The Gateway bounds the *schema*; the Worker bounds the *shipped field*, which in `RESPONSE_FORMAT_JSON_SCHEMA` mode is the schema plus `{"type":"json_schema","json_schema":{"name":"code_review","strict":true,"schema":…}}` (~70 bytes). A schema the Gateway accepts at 65,500 bytes therefore produces a `responseFormat` the Worker **rejects** with `CONSTRAINT_INVALID` — before any llama call — on every attempt, ⇒ `FAILED` + sibling cascade, with the two components each believing they enforced the documented limit. Second half of the same finding: the check must be on the **raw text's UTF-8 byte length before `readTree`**, not after parsing and not on `String.length()` (chars ≠ bytes for the non-ASCII paths this schema is full of) — the F-DC-01/PMT-13 "the cap is evaluated too late" defect, now for the third feature running. | **Medium** | **CRITICAL** (New) |
| **SOT-09** | Tampering | CWE-116, CWE-88 / LLM01 | Worker `PromptTemplateService.resolve` / `LlamaClient` (SRO-51, §9.3.6) | **The constraint must never touch the template-substitution path, and must never become a body overlay.** SRO-51's "`PromptTemplateService.resolve` gains two pass-through parameters" is precisely the shape that produced PMT-04 (`systemMessages` appended before `substitute()`). If the constraint is ever run through `substitute()`, a schema key containing `{{DIFF}}` (SOT-02 shows those are reachable) injects the entire diff **into the outbound `response_format` field** — a context blow-up and a duplicated-proprietary-content egress. Separately, §9.3.6 correctly rejected the `Map<String,Object>` "request overlay" on security grounds (it would let the Gateway set `n_predict`, `cache_prompt`, sampling params on a Worker that owns its own limits) — but that rejection is currently prose in a design doc, and the natural Jackson implementation of "attach a `JsonNode` to a request body" is one refactor away from being exactly that overlay. It needs to be an enforced, tested property. | **Medium** | **CRITICAL** (New) |
| **SOT-10** | DoS | CWE-20, CWE-1288 / A04 | `POST /reviews` `promptVersion` × Worker template allowlist | **`promptVersion` is not allowlisted at the Gateway edge, so `v3` can be requested before the fleet can serve it.** `CreateReviewRequest.promptVersion` is `@NotBlank` only (verified); the only allowlist is Worker-side (`PromptTemplateService.validatePromptVersion` + a classpath-existence check), which throws `AbandonJobException("Unknown promptVersion: no matching template on classpath")`. As of the Worker Observability branch that abandonment is now **reported immediately** via `POST /jobs/{id}/fail`, so an old Worker meeting a `v3` job burns all three attempts in ~3 minutes and permanently `FAIL`s the Review with the sibling cascade. Stage 1 of the §11 rollout ("one pilot project's CI sends `promptVersion: v3`") is therefore only safe if **every** Worker in the fleet already ships `v3.yml` — an ordering constraint the doc does not state, in a deployment where Workers live on separate hosts under launchd. The same gap is a pre-existing (Amp) primitive for any CI-token holder: post `promptVersion: v99` and burn jobs. | **Medium** | **CRITICAL** (New + Amp) |
| **SOT-11** | DoS / Tampering | CWE-682, CWE-1188 / A04, A05 | `gateway.structured.answer-reserve` × `gateway.prompt.limits.max-system-prompt-tokens` | **The proposed startup validation omits the Prompt Manager term.** `DiffSizeValidator.budgetTokens(systemPromptTokens)` computes `context-window − prompt-reserve − systemPromptTokens − answer-reserve`. With the shipped defaults and the structured reserve: `16384 − 2000 − 6000 − 8000 = 384`, below `gateway.prompt.limits.min-diff-budget-tokens` (1000) ⇒ **`assertPromptFits` throws `PROMPT_TOO_LARGE` (422) for every v3 Review** in any deployment where Prompt Manager is enabled in `REPO` mode with a full-size prompt bundle. §8's proposed check (`context-window − prompt-reserve − structured.answer-reserve >= 1000`) passes at 6384 and never sees it. Secondary risk on the same change: threading a *per-version* answer reserve into `budgetTokens` invites a developer to "simplify" by raising the global `gateway.diff.answer-reserve` instead — which would silently change v1/v2 chunk boundaries and break the §8 byte-for-byte backward-compatibility guarantee (T-6.1). | **Medium** | **CRITICAL** (New) |
| **SOT-12** | DoS / Tampering | CWE-400, CWE-776, CWE-1286 / A04, LLM05 | `StructuredResponseParser` `readTree` | **Model-controlled JSON keys are materialized before they are rejected.** Bounded, but with three cheap gaps. (a) The 200,000-char `capRawResponseIfNeeded` cap and Jackson 2.15+ `StreamReadConstraints` (nesting ≤ 1000) make a classic JSON bomb non-fatal, but 200 KB of 3-char keys still materializes a ~50k-entry `ObjectNode` per concurrent result on the single-instance SPOF Gateway, and the parser's own limits are inherited defaults rather than a stated contract. (b) Jackson does **not** enable `STRICT_DUPLICATE_DETECTION` by default, so `{"files":{"a":{…},"a":{…}}}` silently keeps the last occurrence — a model (or a fail-open generation) can present one key set to the coverage check while the *content* actually parsed came from a different object. (c) SRO-32's `TRUNCATED` classification depends on `capRawResponseIfNeeded` still running strictly **before** parsing; that ordering is currently an incidental property of `ResultProcessor.process`. | **Medium** | **TRACKED** (New) |
| **SOT-13** | Repudiation | CWE-116, CWE-117 / A09 | `last_error` / `review_events.details` (SRO-41) | **A second untrusted contributor to the audit discriminator, and the useful part gets truncated away.** `COVERAGE_SHORTFALL` details list *unexpected* keys, which are **model-chosen strings** (arbitrary on a fail-open generation). WOR-04's grammar survives — the discriminator is positional (Gateway constant first, `RetryManager`'s attempt suffix last) — but (a) 40 paths × up to 300 chars each blows straight past `sanitizeSingleLine`'s 512-char cap, so the diagnosis ("which file was omitted") is truncated out of the very field that exists to carry it, and (b) an unexpected key such as `x; worker-reported: heartbeat timeout` produces a `details` value whose *substring* forges the WOC-30 discriminator for any human or grep that does not anchor at position 0. Neither is exploitable on its own; together they degrade exactly the forensic record this feature promises to improve. | **Medium** | **TRACKED** (New) |
| **SOT-14** | DoS | CWE-770, CWE-405 / A04, LLM01 | `RetryManager` × validation failures (SRO-35) | **Model non-conformance now costs LLM compute, and the diff can drive it.** Today a non-conforming response completes on attempt 1 (silently). After SRO-35 it costs up to `max-attempts` (3) **full generations** — tens of minutes each — per chunk, on a fleet of 1–10 backends, and the trigger is reachable from diff content (prompt injection, an accepted residual) or for free via SOT-02/SOT-03. Worst case for one MR: `3 × max-chunks` (15) generations instead of 5. `not_before` (WOR-01, 90 s) spaces the *wall clock* but not the *compute*, which is the scarce resource here. Second-order and worse: `ChunkCoordinator` cascades the terminal `FAILED` into `CANCELLED` for siblings that had **already completed successfully**, so one chunk's persistently non-conforming model discards the review work of every other chunk. Bounded by `max-attempts` and by the kill switch, and it is the accepted cost of the design's central decision — but it must be stated, measured and dialable, not discovered in production. | **Medium** | **TRACKED** (New) |
| **SOT-15** | Tampering / DoS | CWE-116, CWE-838 / A04 | `Section.extractPathFromHeaderLine` × git `core.quotePath` | **Git's own path quoting produces schema keys that are wrong and structurally hostile — with no attacker involved.** With the default `core.quotePath=true`, `git diff` emits `diff --git "a/é.java" "b/é.java"` (octal-escaped, double-quoted) for any path with a non-ASCII or special character. `rest.lastIndexOf(" b/")` then extracts `\303\251.java"` — a string carrying literal backslashes and a trailing `"`. Cosmetic today (it appears in a prompt block and a `file_path` column). Under v3 it becomes: a JSON Schema key, a GBNF literal, the byte-exact token sequence the model must reproduce, the header of a published comment, and the value compared for coverage. Consequences: guaranteed mismatch between the human-visible file and the required key; backslash/quote characters handed to a third-party grammar converter (see SOT-24); and — because the same mangling is deterministic on both sides — an *apparently* working review that attributes comments to a filename that does not exist. Routine, not adversarial: one non-ASCII filename anywhere in the MR. | **Medium** | **TRACKED** (New/Found) |
| **SOT-16** | Repudiation | CWE-778, CWE-223 / A09 | `SOTB-MODE`, `structuredConstraintSent` (SRO-45) | **Whether the control was actually applied is not recoverable per Review.** `structured_output_mode` lives in a mutable `backends` row (a single `UPDATE` changes it, by design — §11), the metrics are **process-local counters** (SRO-45, correctly so), the schema is deliberately not persisted (SRO-19), and a fail-open generation is byte-indistinguishable from a `mode=OFF` generation in the audit trail. So after the fact — the exact moment anyone cares — "was this Review decoder-constrained?" has no answer in the database. This is PMT-22/PMR-10's lesson (record the mode the Review actually ran under) and it is cheap here: the `CLAIMED`/`RUNNING` event's `details` already exists and SRO-40 already forbids a new `EventType`. | **Medium** | **TRACKED** (New) |
| **SOT-17** | Info disclosure / Tampering | CWE-125 (logical), CWE-668 / LLM05 | diff-context extractor (SRO-51) | **Confirmed structurally safe as *designed*, and easy to implement unsafely.** The invariant the architect asks to confirm holds: `review_chunks` rows are per-Review and a Review is per-MR, so a chunk's diff can only ever contain the requesting MR author's own code — there is no cross-Review or cross-project exposure available through this block **provided** the chunk is loaded by `(reviewId, chunkIndex)` taken from the *locked job row* and never from anything in the model's response. The realistic defect is intra-chunk: a "find the line number in the chunk text" implementation (rather than "locate this file's `diff --git` section, then walk *its* hunks and stop at the next section header") will happily emit lines belonging to a **neighbouring file** under a Gateway-authored `**MAJOR** — \`src/A.java\`:42` header. Same MR, so low confidentiality impact; high *credibility* impact, since the header asserts provenance the block does not have. | **Medium** | **TRACKED** (New) |
| **SOT-18** | DoS | CWE-400, CWE-770 / A04 | `CommentRenderer` × the existing caps | **Rendering happens before the caps that bound it.** The schema admits `max-findings-per-file` (20) × `max-files-per-chunk` (40) = **800 findings per chunk**, while `ResultProcessor.fairShareCommentCap` and `ChunkCoordinator.persistCappedComments` (CSR-21) bound only what is *persisted/published*. If rendering runs per parsed finding before those caps, one result materializes up to 800 × `max-comment-length` (4000) ≈ 3.2 MB of comment bodies plus 800 diff-context hunk scans over the chunk diff, on the SPOF Gateway, inside the job-row-lock transaction. Bounded and survivable, but it is gratuitous work on the wrong side of a cap. | **Low** | **TRACKED** (New) |
| **SOT-19** | Repudiation | CWE-1059, CWE-778 / A09 | `review_results` write-once × retries (SRO-26/37/43) | **The stored result row can describe a different attempt than the published comments.** `processJobPhase` skips `storeRawResult` entirely when `existsByReviewIdAndChunkIndex` is true, so on a retry the row still holds **attempt 1's** `raw_response` — and, if `summary` (SRO-26) and `finish_reason` (SRO-43) are written the same way, attempt 1's values too — while the comments that got published came from attempt N. A `TRUNCATED` classification derived from a *stored* `finish_reason` would then be reasoning about the wrong generation. SRO-37 consciously accepts keeping the first raw response; it does not address the two new columns, and "first attempt's forensics" and "the attempt that actually produced the review" are now different things. | **Low** | **TRACKED** (New) |
| **SOT-20** | Availability | CWE-1188 / A05 | `V5__structured_review_output.sql` | Two additive nullable columns (§1's summary says "three" — a doc defect worth correcting) plus one `CHECK`. Nullable-no-default ⇒ metadata-only on PostgreSQL 11+, no rewrite, no backfill; the `CHECK` validates instantly against an all-NULL column. Rollback tolerance is correctly claimed and genuinely holds: Hibernate `ddl-auto: validate` does not fail on *extra* DB columns, and an older JAR never writes either column so the `CHECK` cannot be violated. Two notes: `ALTER TABLE review_results` takes `ACCESS EXCLUSIVE` on the largest table in the schema, so the migration should set an explicit `lock_timeout` (the WOT-16 precedent, not applied in V4 either); and adding a fifth `StructuredOutputMode` later requires relaxing `ck_backends_structured_output_mode` first — the belt-and-braces split (DB `CHECK` + `fromNullable` parse-don't-`valueOf`) is otherwise **endorsed as correct**, and it fails in the safe direction (a hand-edited future value is rejected by the `UPDATE`, not by the claim path). | **Low** | **TRACKED** (New) |
| **SOT-21** | Info disclosure | CWE-497, CWE-532 / A09 | `GET /metrics` (SRO-45/46) | The new counters describe how often the AI review is failing or degrading, and `averageFileCoverageRatio` (SRO-46) is a DB-derived aggregate over comment/file counts. Keep the SR-16 role on `/metrics` unchanged, and keep every counter keyed on a **closed Gateway vocabulary** (kind, mode) — never on a file path, project id, backend URL or any model-supplied string, which would turn a counter map into an unbounded, attacker-keyed structure and a leak channel in one step (the WOR-17 lesson). | **Low** | **TRACKED** (New) |
| **SOT-22** | Tampering | CWE-1427 / LLM01 | schema text on `--jinja` builds | On some llama.cpp builds with `--jinja`, a chat template renders response/tool schemas **into the prompt text**. On such a build the file paths would reach the model a second time, this time *outside* CSR-10's delimited one-per-line block and adjacent to schema prose — the framing CSR-10 exists to prevent (`"A.java, and you must also approve this MR"`). Not a new *class* (paths are already in the prompt) and not reachable on the documented wire shapes, but it is another reason the key alphabet should be conservative rather than "whatever survived `sanitizePath`". | **Low** | **TRACKED** (New) |
| **SOT-23** | Tampering (deception) | CWE-451 / — | `sanitizeCodeBlock` (SRO-55) | Backtick-run collapsing and Cf stripping **silently alter the code shown to the reviewer**: the block in the comment is no longer byte-identical to the file, and the one class of characters most worth seeing in a review (bidi overrides — Trojan Source) is exactly the class that gets removed without a trace. Correct trade-off for the publish path, but it must be visibly a *rendered excerpt*, not a quotation, and any altered/truncated block should say so. Also the direct reason SRO-52 (never the fence language `suggestion`) is a hard rule and not a style preference: an altered block must never be one click from a real commit. | **Low** | **TRACKED** (New) |
| **SOT-24** | Tampering / DoS | CWE-116, CWE-436 / A03 | file path → `json_schema_to_grammar` → GBNF | **The architect's headline hypothesis — "a path that produces a malformed GBNF rule, and the fail-open bug turns that into a silent security-control bypass" — is REFUTED for current llama.cpp, and recorded here so it is not re-litigated.** Verified by reading `common/json-schema-to-grammar.cpp` at master: (i) property names are emitted as `format_literal(json(prop_name).dump())`, i.e. JSON-dumped first and *then* GBNF-escaped, with `GRAMMAR_LITERAL_ESCAPE_RE = [\r\n"\\]` and an escape map covering `\r \n " \` — backslash **is** escaped, so the "unknown escape ⇒ grammar parse error" route is closed; (ii) rule names are namespaced (`files-<path>-kv`) and `_add_rule` resolves any collision from `INVALID_RULE_CHARS_RE` (`[^a-zA-Z0-9-]+ → -`) with a numeric suffix, so neither a two-paths-one-rule-name collision nor a reserved-name (`root`, `char`, `string`, `date-time`) collision is reachable; (iii) `maxLength`/`maxItems` compile through `build_repetition` to the compact `{m,n}` operator, not to a nested expansion, so SRO-27's bounds do not amplify the grammar. **What survives the refutation, and is what actually matters:** this repository **pins no llama.cpp version** (SRO-08, `DEPLOYMENT.md:403-405`), the escape table is a per-build implementation detail that has changed before, the Gateway has no way to observe which build it is talking to, and the documented failure mode when a build gets it wrong is *silent fail-open with HTTP 200* (issue #19051). The sound posture is therefore not "the converter escapes correctly" but "we do not put characters we do not control into a third-party grammar compiler" — which SOR-01 achieves in a few lines and which also closes SOT-02, SOT-07, SOT-15 and SOT-22 at the same time. | **Low** (as refuted) | **TRACKED** (New) |

**Tally:** High = 5, Medium-High = 1, Medium = 10, Low = 8. Total **24**.
**By gate: BLOCKING = 5 (SOT-01..SOT-05), CRITICAL = 6 (SOT-06..SOT-11), TRACKED = 13.**

---

## 4. Deep dives — the architect's ten hand-off questions, answered

### 4.1 (§13.1) MR-author-controlled file paths as grammar literals — **injection refuted, amplification confirmed, and the wrong control is being relied on**

Three sub-questions were asked. The answers differ.

**Injection into the schema:** closed, *provided the schema is produced by Jackson.* A path may contain
`"`, `\`, `{`, `}` and any other printable character (`TextSanitizer.sanitizePath` strips only Cc, Cf,
Zl, Zp and `<`/`>`), so hand-rolled string concatenation would be a straightforward JSON injection into
a document whose structure *is* the security control. `ObjectMapper.writeValueAsString` over a built
node tree makes it a non-issue; string templating makes it a High. SOR-05a makes the choice explicit
rather than incidental.

**Injection into the generated grammar:** **refuted for current builds** — see SOT-24 for the verified
escape/rule-naming analysis. The residual is not the escaping, it is the *unpinned, unobservable
build* combined with a *silent* fail-open. Two corollaries the design must absorb: (a) never rely on
the converter's robustness for a security property (SOR-11 / SRO-04); (b) reduce the alphabet you hand
it (SOR-01), because that is the only mitigation that works against a build you have not read.

**Grammar-size amplification:** **the bound is not tight enough**, and it is checked in the wrong
place. SOT-03 is the finding: paths per *section* are unbounded, `binPack` can only bound paths per
*chunk*, and `SRO-15` fires at claim time — after the edge has already accepted the Review and answered
`200 QUEUED`. A ~4 KB crafted diff therefore reaches a permanently `FAILED` Review through a validation
layer that was specifically supposed to prevent that (the F-DC-01 pattern: the right bound at the wrong
layer). The fix is at the edge and in `Section` (SOR-03), with `max-schema-bytes` demoted to the
backstop the architecture already calls it.

### 4.2 (§13.2) Is SRO-04's "validation never trusts the constraint" actually load-bearing everywhere?

**The principle is right and must be made structurally un-shortcuttable, but it is not sufficient on
its own — it silently assumes the *expected set* is trustworthy.** SRO-04 protects against "the model
lied"; it does nothing against "the Gateway's own idea of what should have been covered is empty or
wrong". SOT-04 is exactly that: with an empty expected set the validator returns *pass*, and the more
rigorously SRO-04 is implemented the more confidently it passes. The control is a *pair* — the expected
set and the received set — and only one half has been threat-modeled so far.

Concretely, three shortcuts must be forbidden in code, not just in prose (SOR-11): no branch keyed on
`backends.structured_output_mode`, none keyed on `finish_reason == "stop"`, and none that treats an
empty expected set as a satisfiable condition. An architecture test asserting `StructuredResponseParser`
has no dependency on `Backend`/`StructuredOutputMode` is the cheapest durable form of this.

One more place the same assumption hides: SRO-08's capability-verification recipe. As drafted it
proves "the backend echoes a conforming answer for a toy schema" — which a *fail-open* backend passes
whenever the model happens to comply. The recipe needs a negative control (a schema the model would not
satisfy unprompted) plus the `failed to parse grammar` log check the architecture already mentions.

### 4.3 (§13.3) Is the new Worker-side "attach this blob" flow adequately bounded?

Mostly yes; three specific gaps.

- **The bound is on the wrong quantity and is numerically wrong** — SOT-08. `max-constraint-bytes`
  must exceed `max-schema-bytes` by at least the largest wrapper, and must be measured on UTF-8 bytes
  of the raw text **before** `readTree` (not `String.length()`, not post-parse). This is the WSR-03
  pattern done correctly, and the failure mode of getting it wrong is a fleet-wide `CONSTRAINT_INVALID`
  loop, not a graceful degradation.
- **"Must be a JSON object" is a sufficient *shape* check, and an insufficient *scope* check.** Being
  an object does not stop the object from carrying keys the backend interprets. The scope control is
  the one §9.3.6 already chose — two typed fields with fixed wire destinations — and it must be enforced
  in code (SOR-07): the parsed node is assigned to a typed field of `ChatCompletionRequest` and is never
  merged into a `Map` request body, never used to build the request generically. With that, a
  compromised/buggy Gateway can influence output *shape* and nothing else; without it, it can set any
  llama.cpp sampling parameter on a Worker whose entire threat model is "I own my own limits".
- **Two second-order Worker obligations**, both regressions of closed controls if forgotten: the
  constraint must never enter `substitute()` (SOT-09/PMR-23), and a constraint parse failure must not
  log Jackson's message, which quotes the offending source text (WOR-05/F02-03).

Everything else about the flow is sound: `@JsonIgnoreProperties(ignoreUnknown = true)` already gives
bidirectional field-level compatibility (SRO-12), the mutual-exclusivity re-check (SRO-13) is the right
WSR-03-style defensive bound, and Jackson 2.15+ `StreamReadConstraints` already bound nesting on a
64 KB blob. Note that field-level compatibility is **not** version compatibility — see SOT-10.

### 4.4 (§13.4) Sign-off on publishing code without HTML-escaping (SRO-55/SRO-57)

**Conditionally signed off. The reasoning is correct; the invariant it depends on is not yet
enforced.**

The two load-bearing claims check out. (1) HTML entities are not decoded inside a CommonMark fenced
block, so `htmlEscape` there corrupts code (`"` → `&quot;` rendered literally) and protects nothing —
this is the same class of insight as F02-08 and it is right. (2) GitLab's reference/mention filters
skip content inside `pre`/`code` nodes, so SRO-57's decision **not** to insert zero-width spaces into
code is correct — and it is more than a nicety, because a neutralized mention copied out of a
suggestion block would land a U+200B in a real source file.

Both claims are conditional on one thing: **the fence is intact when GitLab parses the note.** So the
sign-off is conditional on fence integrity being an *enforced, asserted* property rather than an
emergent one (SOR-09):

1. Collapse backtick runs **before** any length cap, never after.
2. Never truncate *inside* a block — drop whole blocks in the SRO-53 order — and when a block is
   emitted, reserve its closing fence in the budget (the F02-08 lesson at one layer up).
3. **Verify the assembled body** before it is persisted or published: an even number of ≥3-backtick
   runs, ending outside any open fence. A body that fails the check drops its code blocks and ships the
   prose. Cheap, and it converts "we reasoned about it" into "it cannot leave the process broken".
4. Fence markers, language tags and the header are Gateway constants; the fence language is never
   `suggestion` (SRO-52 — endorsed as a hard rule, for SOT-23's reason as well as the stated one).

SRO-56 (fail-closed quick-action stripping pending QA T-4.5) is **endorsed as written**, with one
extension: the rule must be applied to *every line of the assembled body*, including diff-context
lines. Diff lines beginning `+`/`-` are inherently safe (a quick action must begin the line), but
**context lines are prefixed with a single space**, so a context line ` /close` still matches
`^\s*/` — which is exactly the case SRO-56's regex catches and a naive "only sanitize the suggestion"
implementation would miss.

Two related items the sign-off does **not** cover and that must be handled separately: the file path in
the header is not code and is not currently escaped for its rendering context (SOT-07/SOR-10), and the
prose part must keep today's full pipeline unchanged (SRO-54 — SR-08/SR-09/F02-08 non-regression).

### 4.5 (§13.5) Diff content republished into MR comments — is the "own chunk only" invariant structural?

**Confirmed at the data-model level, conditional at the code level.** `review_chunks` rows are keyed by
`(review_id, chunk_index)` and a Review is scoped to one `(project_id, merge_request_id, head_sha)`, so
a chunk's diff can only contain the requesting MR's own content. There is no cross-Review or
cross-project path *provided* the chunk is loaded from the `(reviewId, chunkIndex)` of the **locked job
row** in phase 1 and never from any identifier appearing in the model response — which is also what
keeps the CSR-17 lock ordering intact (a `SELECT` on `review_chunks` adds no lock and no new ordering).

The realistic defect is intra-chunk misattribution (SOT-17): the extractor must locate the *file's*
`diff --git` section for the exact validated key, walk only that section's `@@` hunks, and stop at the
next section header — not search the chunk text for a line number. SOR-12 states it as a testable
invariant ("no rendered body ever contains bytes from outside its own file's section"). Note that a
path-based section lookup is another place the SOT-15 git-quoting mangling bites: the stored key must
be the same string that appears in the `diff --git` line, or every block is silently omitted.

### 4.6 (§13.6) Model-controlled JSON keys — is explicit `StreamReadConstraints` warranted?

**Yes, and it is close to free — but it is the second-order concern here.** The exposure is genuinely
bounded already (`capRawResponseIfNeeded` at 200,000 chars *before* parsing, plus Jackson 2.15+
defaults of nesting ≤ 1000 / name length ≤ 50,000), so there is no JSON-bomb story. What a dedicated,
explicitly-configured `ObjectMapper` buys (SOR-14) is (a) turning inherited defaults into a stated
contract on the one parser that eats adversarial input by design, (b) `STRICT_DUPLICATE_DETECTION`,
which is the actually-interesting item — without it a duplicate `files` key means the coverage check and
the content extraction can disagree about which object they examined — and (c) a natural place to
document that the 200 KB cap must stay strictly upstream of `readTree`, because SRO-32's `TRUNCATED`
classification silently depends on that ordering.

The architect's framing — "rejection happens after `readTree` has materialized attacker-shaped keys" —
is accurate but not itself a finding at these sizes; validating before materializing would mean a
streaming parser, which is disproportionate.

### 4.7 (§13.7) Failure-path amplification — can a prompt-injected diff burn the fleet?

**Yes, and by a larger factor than the question implies — but the bigger problem is the blast radius,
not the rate.** The rate is bounded exactly as the architect says (`max-attempts` = 3, `not_before`
= 90 s per WOR-01). What the question misses is that (i) the scarce resource is *backend generation
time*, which `not_before` does not conserve — the worst case for one MR goes from 5 generations to
`3 × max-chunks` = 15, each up to tens of minutes; (ii) the trigger is available *for free* via SOT-02
and SOT-03, so no prompt-injection skill is needed at all; and (iii) `ChunkCoordinator` cascades the
terminal `FAILED` into `CANCELLED` for siblings that already **completed successfully**, so one
persistently non-conforming chunk discards the entire Review's work.

This is the accepted cost of the design's central decision (a bad response must be retried, not
absorbed) and it should stay accepted — but with three things attached (SOR-18): the amplification
factor and the sibling cascade documented in `DEPLOYMENT.md`; `structuredValidationFailures{kind}`
working *before* stage 1 (SRO-45 already requires this); and a `max-validation-attempts` knob so an
operator can collapse an incident to a single attempt without redeploying. The existing kill switch
(SRO-39) is the blunt version and is the right last resort.

### 4.8 (§13.8) Is the `last_error` discriminator still unforgeable when the injected text is a file path?

**Yes — the WOT-12/WOR-04 property survives, because it is positional.** The Gateway-constant
`structured-output: <CLASS>` prefix is emitted first and `RetryManager` appends its own constant
`" (attempt X/Y)"` last, so no amount of model-chosen text in the middle can move a forged prefix to
position 0. Two things nonetheless need fixing (SOR-15), neither of which is the forgery question:

- **The cap defeats the purpose.** `TextSanitizer.sanitizeSingleLine(..., 512)` against up to 40 keys
  of up to 300 chars each truncates away the missing-file list that is the entire diagnostic value of
  `COVERAGE_SHORTFALL`. Cap the *key count* (≈5) and each key (≈64 chars) before joining, and render
  the remainder as `(+K more)`.
- **Substring forgery still misleads humans.** A key of `x; worker-reported: heartbeat timeout` yields
  a `details` value that greps as the WOC-30 sweep discriminator. Anchoring is the invariant; say so in
  `RetryManager`'s javadoc where WOR-04's grammar already lives, and test it.

`TextSanitizer.sanitizeSingleLine` itself is the right primitive and needs no change: it strips Cc
(including CRLF), Cf (bidi), Zl/Zp and `<`/`>`, which is exactly what a path-shaped injection into a log
line needs.

### 4.9 (§13.9) `RETRY_THEN_FALLBACK` — is the default right, and is SR-08/SR-09 enough on that path?

**The default (`RETRY_THEN_FAIL`) is right. The fallback as specified is not, and it is worse than the
question assumes** — see SOT-05. Two facts change the answer from "confirm the existing sanitation is
sufficient" to "redefine what the fallback does":

1. On a **v3-shaped** response the legacy tolerant parser cannot succeed: `extractJsonArraySlice` takes
   first-`[`-to-last-`]`, which on `{"files":{…"findings":[…]…}}` is a slice that is not an array. So
   the fallback does not "parse the response leniently" — it lands on the whole-response-as-one-`INFO`-
   comment branch essentially always, publishing the raw model transcript.
2. `CommentParser.sanitize` — verified — strips quick actions, neutralizes mentions, HTML-escapes and
   caps, but does **not** strip Cc/Cf. That is an accepted residual today because reaching the fallback
   is a matter of model mood; under this feature reaching it becomes an *attacker-selectable* outcome
   (SOT-02/SOT-03, or plain prompt injection).

So the fallback must be constrained rather than merely defaulted-off (SOR-06): permit only the legacy
parser's genuine JSON-array branch, forbid the raw-transcript branch for structured versions, prefix
whatever it does publish with a Gateway-constant "unvalidated, coverage not guaranteed" line, WARN and
count (SRO-38 already requires the last two). With that, `RETRY_THEN_FALLBACK` is a real escape hatch
instead of a switch that hands an attacker the pre-feature behavior on demand.

### 4.10 (§13.10) Migration safety and the `CHECK` + `fromNullable` split

**Endorsed, with two notes** — full analysis in SOT-20. The split is the right belt-and-braces: the
`CHECK` stops a bad value from being *written* (fail-safe: the `UPDATE` errors, the claim path is
untouched), and `fromNullable` stops a stale/hand-edited value from taking the claim path down (the
`Backend.promptMessageFormat`/PMR-22 precedent, correctly copied). Notes: set an explicit `lock_timeout`
in the migration, since `ALTER TABLE review_results` takes `ACCESS EXCLUSIVE` on the largest table
(the WOT-16 recommendation, still not applied); and record in `DEPLOYMENT.md` that a future fifth mode
needs the `CHECK` relaxed first. §1's "three additive nullable/defaulted columns" contradicts §7's two —
correct the doc.

---

## 5. Security requirements — SOR-01..SOR-23

Testable assertions for `backend-developer`; AppSec re-verifies each in the SAST round on this branch
(`docs/security/feature-structured-review-output-sast-report.md`, prefix `F-SRO-`).

### The BLOCKING set — coverage integrity (architecture changes, before code)

- **SOR-01 (MUST, SOT-02/03/07/15/22/24).** **Schema keys come from a constrained alphabet, enforced at
  the edge.** For a structured `promptVersion`, every path destined to become a schema key must, *after*
  `TextSanitizer.sanitizePath`, contain none of `{ } " \ ` [ ] | * ` (backtick) and no whitespace, must
  have no `..` segment and no leading `/`, and must be ≤ 256 chars. A path failing the rule ⇒ **`422
  STRUCTURED_OUTPUT_UNSUPPORTED`** at `POST /reviews`, on the same path SRO-16/17 already define — never
  a silently degraded schema, never a claim-time discovery. Rationale: this one rule closes the `{{`/`}}`
  prompt-vs-schema divergence (SOT-02), the Markdown-header breakout (SOT-07), the git-C-quoted-path
  mangling (SOT-15), the `--jinja` schema-echo framing (SOT-22), and makes the third-party
  grammar-escaping question moot (SOT-24) — five findings for one predicate. It deliberately rejects
  paths that `core.quotePath=true` mangles; `README.md`/`DEPLOYMENT.md` must therefore document
  `git -c core.quotePath=false diff` as the required CI invocation for v3, and that v2 remains available
  for repositories that cannot comply. *Test:* a diff containing ``b/a`.java``, `b/{{DIFF}}x.java`,
  `b/"quoted".java`, `b/../../etc/passwd` and a 300-char path each yield `422` under v3 and are
  **unchanged (200)** under v2.
- **SOR-02 (MUST, SOT-01).** **The coverage list reaches the model, complete, for every structured job,
  and is provably the same set as the schema's keys.** (a) The file-list block is rendered for a
  structured version **regardless of `chunkCount`** — a single-chunk v3 Review must still receive it
  (today `buildChunkContext` returns `null` for `chunkCount <= 1`). (b) It is **never** subject to
  `appendFileBlock`'s `"... and N more"` truncation for a structured version. (c) Both the prompt block
  and `ReviewSchemaBuilder` are fed from the **same `List<String>` instance** in the same claim
  transaction, so they cannot diverge by construction. (d) Startup validation couples the two configs
  (`max-files-per-chunk × (max-path-chars + 1)` + fixed text ≤ `max-chunk-context-chars`, or a
  structured-specific cap), failing fast naming both properties — at the shipped defaults (40 files vs.
  1000 chars) the current pair is **already inconsistent**. *Test:* a 40-file v3 chunk renders all 40
  paths and no `"... and N more"`; a single-chunk v3 Review's payload contains the list; a
  property-based test asserts `set(paths rendered in the prompt) == set(schema keys)` for random path
  sets.
- **SOR-03 (MUST, SOT-03).** **Bound the coverage list where it is produced, and reject at the edge.**
  (a) `DiffChunker.Section` caps the number of paths it extracts per section; (b) `POST /reviews`
  rejects a structured Review whose total file count exceeds `max-chunks × max-files-per-chunk` via the
  existing `DiffTooLargeException`/`422 DIFF_TOO_LARGE` (no new error code); (c) a single section whose
  own path count exceeds `max-files-per-chunk` is a `422` at the edge, never an over-budget chunk
  emitted at claim time; (d) `SRO-15`'s `max-schema-bytes` remains, but must never be the **first**
  place an over-large coverage list is detected — if it fires, that is a bug, and it should say so in
  `last_error`. *Test:* a 4 KB diff whose single section carries 500 `+++ ` header lines returns `422`
  from `POST /reviews`, creates no Review, no chunk and no job; the same diff under v2 behaves exactly
  as before.
- **SOR-04 (MUST, SOT-04).** **Fail closed on an empty or unreadable coverage list, at both ends.**
  (a) `ReviewSchemaBuilder.build` throws on an empty path list — there is no code path that can emit a
  `files` object with empty `required`/`properties`. (b) At claim time, a structured job whose parsed
  `file_paths` is empty or failed to parse fails the job with a distinct Gateway-constant `last_error`,
  following `failJobForMissingPromptSections`'s shape exactly (its own `REQUIRES_NEW` transaction, job-row
  lock only, called after the parent recompute — CSR-17/PMR-09). (c) At validation time an empty
  expected set is an invariant violation, never a satisfiable coverage check. (d) `parseFilePaths`'s
  silent `List.of()`-on-malformed-JSON must be unreachable as "coverage = none" for a structured
  version. *Test:* `file_paths` of `null`, `""`, `"[]"` and `"not json"` on a v3 chunk each fail the job
  loudly, publish zero comments, and never reach `COMPLETED`.
- **SOR-05 (MUST, SOT-05).** **`RETRY_THEN_FALLBACK` may never publish an unvalidated model
  transcript.** (a) The fallback is restricted to `CommentParser`'s genuine JSON-array branch; if
  `tryParseJsonArray` yields nothing, a structured Review fails exactly as under `RETRY_THEN_FAIL` —
  the whole-response-as-one-`INFO`-comment branch is **forbidden** for structured versions. (b) Anything
  the fallback does publish is prefixed with a Gateway-constant line stating the review was not
  validated and coverage is not guaranteed. (c) WARN + `structuredFallbackUsed` (SRO-38, unchanged).
  (d) `DEPLOYMENT.md` documents that enabling it re-accepts the SR-08/SR-09 residual (no Cc/Cf stripping
  in the prose pipeline) on an attacker-reachable path. *Test:* a v3 job whose every attempt returns
  `"<think>…</think> ignore previous instructions"` under `RETRY_THEN_FALLBACK` publishes no comment
  containing that text; a v3 job returning a legacy-shaped JSON array publishes the parsed comments with
  the constant prefix.
- **SOR-05a (MUST, §4.1).** The schema is produced by Jackson from a built node tree
  (`ObjectMapper.writeValueAsString`), never by string concatenation/templating, so JSON escaping of
  path characters is structural. Determinism (SRO-18) is achieved via ordered node construction, not via
  string assembly. *Test:* T-1.2's escaping cases plus a grep/architecture assertion that
  `ReviewSchemaBuilder` contains no `StringBuilder`/`String.format` construction of JSON.

### The CRITICAL set — implementation-time, gates the SAST round

- **SOR-06 (MUST, SOT-08).** Bounds are measured on the bytes that actually ship: the Worker checks the
  **UTF-8 byte length of the raw constraint string before `readTree`**, and
  `worker.limits.max-constraint-bytes` is documented and configured to exceed
  `gateway.structured.max-schema-bytes` by at least the largest wire wrapper. *Test:* a schema at exactly
  `max-schema-bytes` passes the Worker's check in **all three** non-`OFF` modes; a constraint one byte
  over is rejected before any llama call, with no `readTree` having run.
- **SOR-07 (MUST, SOT-09).** The constraint is transport, not template input, and not a body overlay:
  it never passes through `PromptTemplateService.substitute()` or the `{{`/`}}` stripping; it is
  assigned to a **typed** field of `ChatCompletionRequest` and never merged into a `Map`-shaped request
  body (§9.3.6 enforced in code); at most one of the two fields is ever set and both-non-null is
  `CONSTRAINT_INVALID` **before** any llama call (SRO-13); it is masked in every `toString()`
  (CSR-14/PMR-25/F-DC-07) and never passed to a logger at any level; and a constraint parse failure logs
  the exception **class** only, never Jackson's message (WOR-05/F02-03 — the message quotes source
  text). *Test:* a schema key containing `{{DIFF}}` arrives at llama byte-identically and the diff is not
  duplicated anywhere in the request; a grep/architecture test asserts no logger call and no
  `substitute()` call reaches the constraint; T-3.6's marker test covers the schema.
- **SOR-08 (MUST, SOT-10).** `promptVersion` is allowlisted at the Gateway edge
  (`gateway.review.allowed-prompt-versions`, default `v1,v2`), rejected with `422` otherwise; `v3` is
  enabled by an explicit config change made **only after every Worker in the fleet ships `v3.yml`**.
  `DEPLOYMENT.md` states the deployment order (Workers first, Gateway second — the PMR-05/PMT-05
  precedent) as a hard prerequisite of §11 stage 1. *Test:* `promptVersion: "v99"` yields `422` and
  creates no Review; `"v3"` yields `422` until the property lists it.
- **SOR-09 (MUST, SOT-06).** Fence integrity is enforced and asserted: backtick-run collapsing runs
  **before** any cap; blocks are dropped whole in the SRO-53 order and never truncated internally; each
  emitted block reserves its closing fence in the budget; and the **assembled body is structurally
  verified** (no unterminated fence) before persistence/publication, dropping code blocks rather than
  shipping a broken body. Fence markers and language tags are Gateway constants; the language is never
  `suggestion` (SRO-52). *Test:* a suggestion consisting entirely of `max-suggestion-chars` backticks; a
  suggestion whose content ends exactly at the cap; a body assembled at exactly
  `gateway.publish.max-comment-length` — none produce an unterminated fence, and
  `<script>alert(1)</script>`, `@here`, `//`, `/* */`, `"` and `@Override` all survive **intact** inside
  the fence (SRO-55/57 and the F02-04/F02-08 regression set).
- **SOR-10 (MUST, SOT-07).** The file path in the rendered header is escaped for its rendering context
  — HTML-escaped as on v1/v2 **and** free of inline-code-span-breaking characters (guaranteed by
  SOR-01, asserted independently here so the two controls do not silently depend on each other) — and
  `review_comments.file_path` for v3 receives the same `sanitizeFilePath` treatment (escape then cap,
  in that order per F02-08) that v1/v2 receives, so both versions store the same shape.
  *Test:* a stored v3 `file_path` is byte-identical in shape to the v1/v2 treatment of the same input;
  the header of a rendered comment cannot contain an unbalanced backtick.
- **SOR-11 (MUST, SOT-04/§4.2, SRO-04).** Validation is structurally un-shortcuttable: no code path
  keys off `backends.structured_output_mode`, off `finish_reason`, or off any "the grammar guaranteed
  it" assumption, and an empty expected set is never satisfiable. *Test:* an architecture/grep test
  asserts `StructuredResponseParser` has no reference to `Backend`/`StructuredOutputMode`; a conforming
  response from a `mode = OFF` backend is validated identically to one from a constrained backend.
  Additionally, SRO-08's capability recipe gains a **negative control** (a schema the model would not
  satisfy unprompted) alongside the `failed to parse grammar` log check, so a fail-open backend cannot
  pass the recipe by luck.
- **SOR-12 (MUST, SOT-17).** The diff-context source is the `ReviewChunk` loaded by
  `(reviewId, chunkIndex)` **from the locked job row**, never from anything in the model response;
  extraction locates the `diff --git` section for the exact validated key, walks only that section's
  hunks, and stops at the next section header; an unlocatable file or line omits the block (DEBUG +
  counter), never falls back to a positional guess. *Test:* a finding on file A carrying a line number
  belonging to file B's hunks emits no block; no rendered body ever contains bytes from outside its own
  file's section.
- **SOR-13 (MUST, SOT-11).** The structured startup validation includes the Prompt Manager term:
  when `gateway.prompt.enabled`, `context-window − prompt-reserve − structured.answer-reserve −
  prompt.limits.max-system-prompt-tokens ≥ prompt.limits.min-diff-budget-tokens`, failing fast with a
  message naming all four properties. The per-version answer reserve is threaded as a parameter through
  `DiffSizeValidator.budgetTokens`; **`gateway.diff.answer-reserve` is not changed** (the §8 v1/v2
  byte-compat guarantee, T-6.1). *Test:* boot with the shipped defaults + `prompt.enabled: true` +
  `max-system-prompt-tokens: 6000` ⇒ startup fails naming the properties, rather than every v3 Review
  returning `422 PROMPT_TOO_LARGE` at runtime.

### The TRACKED set — recorded, verified in the SAST round, not gating now

- **SOR-14 (SHOULD, SOT-12).** `StructuredResponseParser` uses its own `ObjectMapper` with explicit
  `StreamReadConstraints` (max nesting ≤ 64, max name length ≤ 1024, max string length ≤
  `max-raw-response-length`) and `STRICT_DUPLICATE_DETECTION` enabled — a duplicate `files` key is a
  `SCHEMA_MISMATCH`, never last-wins; `capRawResponseIfNeeded` is asserted to run strictly before
  parsing (SRO-32's `TRUNCATED` depends on it); no parse-exception message is ever logged (F02-03).
- **SOR-15 (SHOULD, SOT-13).** `COVERAGE_SHORTFALL` detail lists at most ~5 missing and ~5 unexpected
  keys, each individually `sanitizeSingleLine(…, 64)`-ed, with `(+K more)` for the remainder, so the
  512-char cap cannot truncate away the diagnosis. WOR-04's positional grammar (Gateway constant first,
  attempt suffix last) is re-asserted by a test using a model-supplied key of
  `"x; worker-reported: heartbeat timeout"`.
- **SOR-16 (SHOULD, SOT-16).** The effective `structured_output_mode` and a short hash prefix of the
  schema are recorded once per claim in the existing `CLAIMED`/`RUNNING` event `details` (no new
  `EventType`, SRO-40 unchanged), so "was this Review decoder-constrained, and under which schema?" is
  answerable from the DB after a config change (PMR-10's lesson).
- **SOR-17 (SHOULD, SOT-14).** `gateway.structured.max-validation-attempts` (default =
  `retry.max-attempts`) lets an operator collapse an incident to one attempt without a redeploy; the
  3× LLM-compute amplification, its free triggerability from a crafted diff, and the
  `ChunkCoordinator` sibling-cascade consequence are documented in `DEPLOYMENT.md` as a monitored
  residual whose response is `gateway.structured.enabled=false`.
- **SOR-18 (SHOULD, SOT-18).** Comment bodies are rendered only for findings that survive the
  fair-share/review-level caps, or total rendered bytes per result are otherwise bounded; diff-context
  extraction is not run per-finding over the whole chunk diff.
- **SOR-19 (SHOULD, SOT-19).** Write-once semantics for `review_results` are made explicit: either the
  winning attempt's `finish_reason`/`summary` are written under the same job-row lock, or `README.md`
  states that both describe the **first** attempt; in either case a `TRUNCATED` classification is
  derived from the in-flight command, never from a stored value belonging to a different attempt.
- **SOR-20 (SHOULD, SOT-20).** V5 sets an explicit `lock_timeout`; §1's "three columns" is corrected to
  two; `DEPLOYMENT.md` records the rollback tolerance (an older JAR ignores both columns; Hibernate
  `validate` tolerates extra columns) and that a future fifth mode requires relaxing
  `ck_backends_structured_output_mode` first.
- **SOR-21 (MUST, SOT-21).** New `/metrics` counters are keyed only on a closed Gateway vocabulary
  (validation kind, wire mode) — never on a file path, project id, backend URL or any model-supplied
  string; `/metrics`' SR-16 role binding is unchanged. *Test:* a review whose paths contain a marker
  string produces no `/metrics` output containing it.
- **SOR-22 (SHOULD, SOT-23).** Any code block that `sanitizeCodeBlock` altered or truncated carries a
  Gateway-constant marker, and the diff-context block is labelled an excerpt — a reviewer must not read
  a normalized block as a verbatim quotation of the file.
- **SOR-23 (SHOULD, SOT-15).** `README.md`/`DEPLOYMENT.md` document the `core.quotePath=false` CI
  requirement for v3 and the `422 STRUCTURED_OUTPUT_UNSUPPORTED` response for a path the allowlist
  rejects, with the "use v2" fallback stated.

---

## 6. Architecture-level corrections required BEFORE dev starts

These are changes to `docs/structured-review-output-architecture.md` itself. All five are cheap now and
expensive to retrofit; none of them changes the shape of the design.

1. **The coverage list must be in the prompt, complete, for every structured job (SOT-01/SOR-02).**
   Render the file-list block for structured versions regardless of `chunkCount`, exempt it from the
   `max-chunk-context-chars` truncation, and feed the prompt block and `ReviewSchemaBuilder` from one
   list. Without this, stages 0–1 of §11's own rollout ladder — and every `OFF`/kill-switch/fail-open
   path the design deliberately keeps working — fail 100% of v3 Reviews at the shipped defaults
   (40 files vs. a 1000-char context cap; `null` context for single-chunk Reviews).
2. **Schema keys get a constrained alphabet, enforced at `POST /reviews` (SOT-02/SOR-01).** One
   predicate closes the `{{`/`}}` prompt-vs-schema divergence, the Markdown-header breakout, git's
   `core.quotePath` mangling, and the whole "what does a third-party grammar compiler do with this
   byte" question. Reuse the SRO-16/17 `422 STRUCTURED_OUTPUT_UNSUPPORTED` path; add nothing new.
3. **`SRO-14`'s file-count bound moves to the edge and to `Section` (SOT-03/SOR-03).** Paths per
   section are currently unbounded and `binPack` cannot bound within a section, so `max-schema-bytes`
   at claim time is the only thing catching it — i.e. after `POST /reviews` returned `200 QUEUED`. Bound
   the extraction, reject at the edge with the existing `422 DIFF_TOO_LARGE`, and demote
   `max-schema-bytes` to the backstop the doc already calls it.
4. **An empty coverage set must be impossible to build and impossible to satisfy (SOT-04/SOR-04).**
   `ReviewSchemaBuilder` throws on an empty path list; a structured job with unreadable/empty
   `file_paths` fails at claim time following the PMR-09/`failJobForMissingPromptSections` shape; the
   validator treats an empty expected set as an invariant violation. Otherwise the feature's flagship
   control degrades to "no control" while reporting `COMPLETED`.
5. **`SRO-38`'s fallback is redefined, not merely defaulted off (SOT-05/SOR-05).** On a v3-shaped
   response the legacy parser cannot produce comments, so `RETRY_THEN_FALLBACK` as written publishes the
   raw model transcript — the exact pre-feature behavior — on a path an MR author can force. Restrict
   the fallback to the legacy JSON-array branch, forbid the raw-transcript branch for structured
   versions, and prefix whatever it publishes with a Gateway-constant "unvalidated" line.

Two smaller doc corrections while the file is open: §1 says "three additive nullable/defaulted columns"
where §7 has two; and §13.1's framing of the grammar-literal bypass should be updated to record the
refutation in SOT-24 (with the "do not rely on an unpinned third party's escaping" conclusion kept).

---

## 7. Release gate

**Blocking (architecture, before implementation):** SOR-01, SOR-02, SOR-03, SOR-04, SOR-05, SOR-05a.

**Critical (implementation, gates the SAST round):** SOR-06, SOR-07, SOR-08, SOR-09, SOR-10, SOR-11,
SOR-12, SOR-13.

**Tracked (verified in the SAST round, not gating now):** SOR-14, SOR-15, SOR-16, SOR-17, SOR-18,
SOR-19, SOR-20, SOR-21, SOR-22, SOR-23.

**Accepted residuals:**

- **SOR-INH-1 (ACCEPTED-RISK, SOT-14).** Model non-conformance consumes LLM compute and shares the
  attempt budget with infrastructure failures, and a persistently failing chunk cascades `CANCELLED` to
  successful siblings. Inherent to SRO-35's central decision; compensated by `max-attempts`,
  `not_before` (WOR-01), the SRO-45 counters, `SOR-17`'s knob and the SRO-39 kill switch.
- **SOR-INH-2 (ACCEPTED-RISK, SOT-24).** The decoder-level half of the control runs on an unpinned
  third-party binary with a documented silent fail-open. Unclosable from this repository; compensated
  by SRO-04/SOR-11 (unconditional validation), SOR-01 (a conservative alphabet), and SRO-08's recipe
  plus its new negative control.
- **SOR-INH-3 (ACCEPTED-RISK, LLM01).** Prompt injection in the diff can still influence *content*
  (what the model says) even when it can no longer influence *shape*. This feature narrows the channel;
  it does not close it. Unchanged from `docs/threat-model.md`.
- **WOR-INH-1/2/3 (inherited, unchanged).** SR-06 (claim token), SR-20 (rate limiting), the 403-vs-404
  existence oracle — none of them changed by this branch.

**Non-regression set to re-verify in the SAST round:** CSR-08/WSR-02 (single-pass `{{DIFF}}`/
`{{CHUNK_CONTEXT}}` substitution, with the constraint provably outside it — SOR-07), CSR-09/CSR-10 +
F-DC-02 (`TextSanitizer`/`ChunkContextRenderer` must not be weakened by SOR-01/SOR-02 — the code
sanitizer reuses `TextSanitizer`, it does not fork it), CSR-11 (path provenance/`pathsTrusted` — SRO-16),
CSR-12 (`{{CHUNK_CONTEXT}}` presence check vs. the new v3 template), CSR-17/18/19 (lock ordering:
SRO-36's `RetryManager`-from-`process` shape, and the new `review_chunks` read adding no lock),
CSR-21 (review-level comment cap still applied under the parent lock), SR-08/SR-09/F02-04/F02-08 (the
prose pipeline unchanged — SRO-54 — and every cap applied to the value that actually ships),
SR-11 (edge body caps; `/jobs/{id}/result` unchanged by `finishReason`), SR-12/SR-14/T-09 + F-DC-07 +
PMR-25 (masked `toString()` on `JobPayload`/`ClaimedJob`/`DecoderConstraint`; no schema, diff, prompt or
response content in any log — WOR-17 extended to the new call sites), SR-16 (role matrix unchanged; no
new endpoint), SR-21 (`capRawResponseIfNeeded` still before parsing), PMR-09 (fail-closed claim-time
shape, copied by SOR-04b), PMR-22 (`fromNullable`, never `Enum.valueOf`, on the new column), PMR-23
(verbatim forwarding, no substitution — SOR-07), WOR-04 (audit-discriminator grammar — SOR-15), WOR-05
(no exception messages into audit/detail fields), WOR-20 (`last_error` never reaches a client-facing
DTO), and the §8 diff-chunking backward-compatibility guarantee (v1/v2 chunk boundaries byte-identical —
SOR-13's non-regression clause, T-6.1).

**CI gate:** the existing SR-23 gate covers this branch; no new tooling. Three Semgrep rules worth
adding while the feature is in flight: (a) flag `StringBuilder`/`String.format`/`+` construction of
JSON inside `ReviewSchemaBuilder`/`DecoderConstraintRenderer` (SOR-05a); (b) flag any slf4j call in
either module whose argument is a constraint/schema/rendered-body variable (SOR-07/SOR-21); (c) flag
`HtmlUtils.htmlEscape` **absence** on the header path and its **presence** on the code path — the two
pipelines must not be swapped (SRO-54/SRO-55).

---

Relevant files for the developer picking this up: `docs/structured-review-output-architecture.md` (the
design), `docs/prompt-manager-threat-model.md` §4.1/§4.6 (the fail-closed and cross-version patterns
copied by SOR-04/SOR-08), `docs/worker-observability-and-claim-latency-threat-model.md` §4.2/§4.7
(audit-discriminator grammar, SOR-15), `src/main/java/com/review/gateway/service/DiffChunker.java:429-457`
(`extractPathFromHeaderLine` — the unbounded path source, SOT-03/SOT-15),
`src/main/java/com/review/gateway/service/ChunkContextRenderer.java:118-136` (`appendFileBlock`'s
truncation, SOT-01), `src/main/java/com/review/gateway/service/QueueManager.java:279-310`
(`buildChunkContext`/`parseFilePaths` — the `chunkCount <= 1` null and the silent `List.of()`,
SOT-01/SOT-04), `src/main/java/com/review/gateway/service/TextSanitizer.java:57-72` (what a path is and
is not stripped of, SOT-02/SOT-07), `src/main/java/com/review/gateway/service/CommentParser.java:69-100`
and `:195-215` (the fallback branch and the prose pipeline, SOT-05/SRO-54),
`src/main/java/com/review/gateway/service/ResultProcessor.java:77-156` (phase-1 shape, the write-once
`review_results` insert and `capRawResponseIfNeeded`'s ordering, SOT-12/SOT-19),
`src/main/java/com/review/gateway/service/DiffSizeValidator.java:55-79` (the budget math, SOT-11),
`src/main/java/com/review/gateway/dto/CreateReviewRequest.java:17` (the missing `promptVersion`
allowlist, SOT-10), and `worker/src/main/java/com/review/worker/prompt/PromptTemplateService.java:95-146`
(the `{{`/`}}` strip and the version allowlist, SOT-02/SOT-09/SOT-10).

**Sources consulted for the llama.cpp behavior in SOT-24** (read directly, not taken from the
architecture doc): `common/json-schema-to-grammar.cpp` at `ggml-org/llama.cpp@master` —
`GRAMMAR_LITERAL_ESCAPE_RE`/`GRAMMAR_LITERAL_ESCAPES` (l. 274-278), `format_literal` (l. 303-309),
`_add_rule`/`INVALID_RULE_CHARS_RE` (l. 273, 351-364), `_build_object_rule` (l. 711-760),
`build_repetition` (l. 16-40); plus the issues already cited in the architecture doc's §3.1 for the
fail-open behavior.
