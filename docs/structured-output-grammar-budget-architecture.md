# Architecture: Structured Output Grammar Budget (`fix/structured-output-grammar-budget`)

Status: **PROPOSED — appsec pre-implementation round complete.** Threat model:
`docs/structured-output-grammar-budget-threat-model.md` (threats `SOGT-nn`, requirements `SOGB-nn`).
Five inline corrections below were made by appsec during that round and are marked
**[appsec correction]**; everything else is the architect's text. The design direction (SGB-01 delete
`maxLength` + SGB-03 truncate on receipt) is **endorsed**; SGB-03/SGB-04/SGB-06 are endorsed **with
mandatory additions** — see the threat model's §5 release gate before starting implementation.

Slug: `structured-output-grammar-budget`. Branch: `fix/structured-output-grammar-budget`, cut from `feature/structured-review-output` (or from `master` once that branch merges — it touches only files that branch introduced). Requirement prefix: **`SGB-nn`**. Companion artifacts, pre-named so the next steps plug in without renaming churn:

- `docs/structured-output-grammar-budget-threat-model.md` (appsec; if appsec judges the delta small, an amendment section appended to `docs/structured-review-output-threat-model.md` is acceptable — state which in that doc's Status line).
- `docs/security/feature-structured-output-grammar-budget-sast-report.md`, finding prefix **`F-SOGB-`**.

This document **amends** `docs/structured-review-output-architecture.md` (SRO-02, SRO-08, SRO-15, SRO-27, SRO-32/33, §10, §12). It does not supersede it. Everything not named here is unchanged.

---

## 1. The incident, and the root cause as measured

Canary: `structured_output_mode='RESPONSE_FORMAT_JSON_SCHEMA'` on `llama-01`/`llama-02`, an MR with 4 changed files, defaults `max-findings-per-file=20`, `max-comment-chars=1200`, `max-suggestion-chars=2000`. All 3 attempts failed in 230–280 ms — before any token was generated — with llama-server logging:

```
parse: error parsing grammar: number of rules that are going to be repeated multiplied by
the new repetition exceeds sane defaults, please reduce the number of repetitions or rule complexity
srv send_error: ... error: Failed to initialize samplers: failed to parse grammar
```

### 1.1 What actually blows up

Two distinct llama.cpp stages, and the design doc reasoned about only the first:

1. **`common/json-schema-to-grammar.cpp` (converter)** — turns the JSON Schema into GBNF *text*. `maxLength: N` on a string compiles to `"\"" char{0,N} "\""`; `maxItems: N` on an array compiles to `item ("," item){0,N-1}`. Both use the **compact `{m,n}` operator**, which is what `docs/structured-review-output-architecture.md` §13.1 observed when appsec read this file.
2. **`src/llama-grammar.cpp` (runtime grammar parser)** — parses that GBNF and **expands `{m,n}` into rules**: `S{m,n} → S S … S'(n−m)`, `S'(x) ::= S S'(x−1) |`, i.e. **one new rule per optional repetition**. It carries a DoS guard (`MAX_REPETITION_THRESHOLD = 2000`, added by upstream PR #18604):

```
if (n_prev_rules * total_rules >= MAX_REPETITION_THRESHOLD) { throw ...same message we saw... }
```

**§13.1's conclusion — "`maxLength`/`maxItems` compile through `build_repetition` to the compact `{m,n}` operator, so SRO-27's bounds do not amplify the grammar" — is refuted by production.** It is true of the emitted GBNF text and false of the parsed grammar. This correction is recorded here so it is not re-derived, and so the next appsec pass knows that *reading the converter is not sufficient evidence about grammar size*.

### 1.2 Per-site cost at the shipped defaults

For a single-rule-reference item (`char`), `n_prev_rules = 1` and `total_rules = N`:

| Schema site | Compiles to | Budget consumed | Verdict |
|---|---|---|---|
| finding `suggestion`, `maxLength: 2000` | `char{0,2000}` | **2000** | **≥ 2000 ⇒ hard throw, on its own** |
| finding `comment`, `maxLength: 1200` | `char{0,1200}` | 1200 | passes alone, 60 % of budget |
| chunk `summary`, `maxLength: 500` | `char{0,500}` | 500 | passes |
| per-file `summary`, `maxLength: 200` | `char{0,200}` | 200 | passes |
| `findings`, `maxItems: 20` | `(… item){0,19}` | ~20 | **1 % of the problem** |
| `files` object, N keys, all required | plain sequence, no repetition | **0** | file count is not a repetition site |

**Therefore the primary conclusion: this failure does not need four files. A one-file v3 schema at today's defaults hits `1 × 2000 >= 2000` and is rejected identically.** The canary MR is a red herring; the feature's decoder-constrained mode has very likely never compiled on any real backend, because no test on this machine ever compiled a grammar (no Docker, no llama-server — all v3 tests are unit tests and `mockwebserver`).

**One residual ambiguity, and it is the only thing the probe in §6 must settle:** upstream's `n_prev_rules` may be *cumulative across repetition sites already emitted* rather than the size of the immediately-preceding item. Under that reading, file count *does* multiply in (40 files × 20-rule `findings` sites would exceed 2000 around the 5th file even after this fix). §6's P-3 tests exactly that, and its result — not a guess — binds `gateway.structured.max-files-per-chunk`.

### 1.3 The second-order damage, which is its own finding

A grammar rejection is a **deterministic, compile-time, backend-side refusal**. Today it arrives as an undifferentiated `LLM_ERROR`, so:

- all 3 attempts burn in well under a second (each ~250 ms, `not_before` delay aside),
- `ChunkCoordinator` then cancels *successful* sibling chunks,
- `last_error` says `"llama-server call failed"`, and the actual cause is only in the llama-server log on another host.

This is `SOR-INH-1` (failure-path amplification) realized, with the worst possible diagnosability. `SGB-06` addresses the diagnosability half; the amplification half is fixed by making the grammar uncompilable-by-construction impossible (`SGB-01`/`SGB-02`).

---

## 2. Chosen approach

**Stop emitting bounded string repetition. Length is a presentation bound, not a decoder bound — and the Gateway already enforces it on receipt.**

| Id | Requirement |
|---|---|
| **SGB-01** | **`ReviewSchemaBuilder` MUST NOT emit `maxLength` anywhere.** `comment`, `suggestion`, per-file `summary` and chunk `summary` become plain `{"type":"string"}`. The converter then routes them to its shared `string` primitive (`"\"" char* "\""`), whose `*` is an unbounded repetition — **one rule, zero expansion, no interaction with `MAX_REPETITION_THRESHOLD` at all** (for `*`, `total_rules` is the *minimum* count, i.e. 0). Concretely: `stringSchema(int)` loses its parameter and its `node.put("maxLength", …)` line. Nothing is added. |
| **SGB-02** | **`maxItems` stays.** It is the only structural bound this feature actually needs at the decoder (it is what makes "20 findings" not "200"), and it costs ~20 of a 2000 budget. Startup validation (the existing `@PostConstruct` block, §8 of the base doc) gains one assertion: `gateway.structured.max-findings-per-file <= 200`, failing fast with a message that names the property, the value, and llama.cpp's `MAX_REPETITION_THRESHOLD = 2000` as the reason. `200` is a deliberate ~10× margin below the threshold, not a tuned number; §6/P-3 may lower it. |
| **SGB-03** | **`max-comment-chars` / `max-suggestion-chars` survive as *receipt-side* bounds, and their over-length outcome changes from reject to truncate.** `StructuredResponseParser.validateFindingAndCollect:335-340` currently returns `SCHEMA_MISMATCH` when a comment/suggestion exceeds the cap — a **whole-chunk retryable failure**. That was tolerable while the decoder made it unreachable; with SGB-01 it becomes reachable, and it is the wrong response either way: an over-long comment is a *formatting* problem the renderer already solves. It MUST instead be truncated to the cap, with a WARN-free DEBUG line and a new `MetricsCounters` counter `structuredFieldTruncated`. **[appsec correction]** Truncating in the parser makes `CommentRenderer`'s `SanitizedCode.altered` flag compute against the *already-truncated* string, so a truncated suggestion would ship **without** the `ALTERED_CODE_MARKER` — a silent regression of `SOR-22`/`SOT-23` (a rendered excerpt read as a verbatim quotation). The truncation flag MUST be carried to the renderer: see **SOGB-02**, which is BLOCKING (closed Gateway vocabulary, no field content, no path — the `SOR-21` rule). **Note this also closes a defect that exists in production today**: on the shipped default (`mode=OFF`, no constraint) an unconstrained model writing a 1300-char comment already fails the chunk deterministically. |
| **SGB-04** | **Truncation ordering is load-bearing and must be asserted.** Truncate at parse (SGB-03) **before** `CommentRenderer`/`sanitizeCodeBlock`/fence assembly, so `SOR-09`'s obligations still hold on the final body: collapse backtick runs before any cap, reserve closing fences, and structurally verify the assembled body last (SRO-53). The truncation itself MUST NOT split a UTF-16 surrogate pair and MUST NOT be applied to already-fence-wrapped text. The existing SRO-53 block-dropping order is unchanged and still runs after. |
| **SGB-05** | **`gateway.structured.max-files-per-chunk` is left at `40` pending §6/P-3, and is the designated knob if P-3 shows file-count sensitivity.** No number is guessed here. If P-3 shows the first failing file count `F`, the property is set to `floor(F/2)` and P-3 is re-run at that value as the acceptance gate. `gateway.diff.max-paths-per-section` and every SRO-14/SRO-66 edge bound are unchanged. |
| **SGB-06** | **A grammar-compile rejection MUST be nameable.** `LlamaClient` classifies a non-2xx llama-server response whose body/error text matches a fixed, Gateway/Worker-constant token set (`failed to parse grammar`, `Failed to initialize samplers`) into a distinct abandonment reason `CONSTRAINT_REJECTED`, reported through the **existing** `POST /jobs/{id}/fail` (V4). **[appsec correction]** `LlamaClient.parseResponse:159-162` currently throws on a non-2xx **without reading the body at all**; SGB-06 therefore introduces a *new* ingestion of backend-controlled bytes on a path that previously had none, on the worker-loop thread, after `awaitLlamaResponse`'s timeout has already elapsed. It is conditionally approved subject to **SOGB-05/SOGB-06/SOGB-07** in the threat model (bounded bytes, bounded *time*, matched text never stored/logged/forwarded) — read those before implementing this row. This is **audit-only** — per CLAUDE.md and WOC, `reason`/`detail` never influence the retry decision, and this one does not either. It exists so `review_jobs.last_error` says *"the backend refused the grammar"* instead of *"llama-server call failed"*. No new endpoint, no new column, no new `EventType`. |
| **SGB-07** | **`DEPLOYMENT.md`'s SRO-08 capability recipe MUST use the production schema shape, not a toy.** A "two-field toy schema" cannot detect this failure class by construction — it has no repetition sites. The recipe MUST post the schema the Gateway would actually build for (a) a 1-file chunk and (b) a `max-files-per-chunk`-file chunk, at the deployment's own `gateway.structured.*` values, and MUST keep its negative control and its `failed to parse grammar` log check. The branch ships those two schemas as committed fixtures emitted by `ReviewSchemaBuilderTest`, so the recipe cannot drift from the builder. |

**Net diff shape:** one line deleted in `ReviewSchemaBuilder`, one signature simplified, one startup assertion, one reject→truncate in `StructuredResponseParser`, one counter, one Worker error classification, doc/fixture updates. **No migration, no new config key, no new dependency, no new endpoint, no state change.**

### 2.1 Interim mitigation, if this branch slips

Both backends are already `OFF` (correct, and sufficient). If structured mode must be re-enabled before this branch lands, the **zero-code** mitigation is `gateway.structured.max-suggestion-chars: 1500` (any value keeping every individual `maxLength` and `maxItems` strictly below 2000). **[appsec correction]** That formulation assumes the *per-site* reading of `n_prev_rules` — the exact reading §1.2 says it could not settle from source. Under the cumulative reading the four sites sum to `1200 + 1500 + 500 + 200 = 3400 ≥ 2000` and this mitigation does not work at all. It is therefore only usable **after P-2 (and P-1) have confirmed the per-site formula on the specific backend**; until then the only sound interim mitigation is the one already in force, `structured_output_mode = 'OFF'`. This is explicitly **not** the fix — see rejected alternative R-4 — because it leaves an operator-reachable landmine and depends on an upstream constant this repository does not pin.

---

## 3. Rejected alternatives

| Id | Alternative | Why rejected |
|---|---|---|
| **R-1** | **Define the finding/file subtree once under `$defs` and `$ref` it per file** (the brief's direction 1) | **Rejected on correctness, before portability even comes up.** Sharing removes *duplication*, and duplication is not what throws: a `char{0,2000}` site consumes `1 × 2000 ≥ 2000` **whether it appears once or forty times**. `$defs` therefore cannot fix a schema that keeps `maxLength: 2000`, and is unnecessary for a schema that drops it. **[appsec correction]** The parenthetical originally read *"SGB-01 makes the per-file subtree repetition-free"* — that is **false and contradicts this document's own §1.2 and §6/P-1**: SGB-02 deliberately *keeps* `maxItems: 20` on `findings`, which is a repetition site, present once **per file**. After SGB-01 a 40-file schema therefore still carries 40 repetition sites of ~20 each. Whether that matters is exactly the per-site-vs-cumulative question P-1 settles, and it is why SGB-05 exists. The correct statement is: SGB-01 removes every *bounded-string* repetition site (the ones that blow the budget on their own); the per-file `maxItems` sites remain and are bounded by measurement (P-1/P-3), not by this argument. Three further reasons not to spend the portability floor on it: (a) upstream ggml-org/llama.cpp **issue #21228** reports `$ref`/`$defs` schemas hitting *this exact threshold* and, worse, **silently falling back to unconstrained generation** — i.e. the `SOR-INH-2` fail-open, on a schema shape we deliberately avoided; (b) a build that does not resolve local `$ref` renders the constraint **vacuous rather than absent**, which is the worst failure mode this feature has (a vacuous constraint still reports `structuredConstraintSent`); (c) SRO-02's inlining rule stands, and is now justified by evidence rather than by the server README's caveat. §6/P-5 still probes `$defs` **for the record only** — the result must not gate this fix. |
| **R-2** | **Cap the number of files per *structured* chunk, lower and independently of the general chunking knob** (direction 2) | Rejected **as the fix**: it does not address a per-site `≥ 2000`, so a 1-file MR would still fail; and any number chosen today would be a guess against an upstream constant we have not measured. **Retained as a contingent secondary bound** (SGB-05), bound by P-3's measurement rather than by judgement. |
| **R-3** | **Lower `max-findings-per-file` from 20** (direction 3) | Rejected as the fix: at ~20 of a 2000 budget it is ~1 % of the problem, and lowering it directly weakens the feature's product value (findings the model is allowed to report). Kept at 20; bounded by a startup assertion instead (SGB-02). Becomes relevant only if P-3 shows cumulative accounting. |
| **R-4** | **Keep `maxLength` but clamp it below 2000** (in config, or in the builder) | Rejected as the durable fix; accepted only as the interim mitigation (§2.1). It preserves a config value whose safe range is defined by an unpinned third-party constant, in a repository whose own `DEPLOYMENT.md:403-405` says llama-server versioning is out of scope. An operator raising `max-comment-chars` to 2500 for better reviews would take the whole fleet down with a 250 ms deterministic failure and a message about GBNF. Structural impossibility beats a documented range. |
| **R-5** | **Compute a "grammar rule budget" in the Gateway per claim and refuse over-budget schemas** | Rejected as premature and as a maintenance trap: it reimplements a third-party parser's internal accounting (including the `n_prev_rules` ambiguity §1.2 could not resolve from source) inside the Gateway, and it is wrong the day upstream lands #21216/#21003 (raising or making the threshold configurable). Once no schema we emit contains a large bounded repetition, there is no budget to compute. SGB-02's single startup assertion is the whole of what this would have bought. |
| **R-6** | **Emit GBNF `grammar` ourselves instead of JSON Schema** | Still rejected — §9.3.1 of the base doc is unchanged and is now *stronger*: we would have to reimplement the very repetition expansion whose behavior we just discovered by production incident, and a GBNF string still cannot serve SRO-30's validation role. |
| **R-7** | **Auto-demote a backend to `structured_output_mode='OFF'` when it rejects a grammar** | Rejected. `POST /jobs/{id}/fail`'s `reason`/`detail` are **audit-only by contract** (CLAUDE.md, WOC) and must never drive a decision; letting a Worker-reported, backend-influenced string mutate fleet configuration inverts "Gateway is the sole owner of business logic and state" in the one direction that matters. The operator `UPDATE` resolved today's incident in minutes and is already the documented §11 rollback. SGB-06 gives that operator the sentence they were missing. |
| **R-8** | **On the first grammar rejection, silently retry the same job with the constraint suppressed** | Rejected. It converts a loud, correct, 250 ms infrastructure failure into a silent quality degradation — precisely the behavior this feature exists to remove — and it would have *hidden* today's incident instead of surfacing it. `gateway.structured.enabled=false` (SRO-39) remains the deliberate, operator-owned version of the same thing. |
| **R-9** | **Pin llama.cpp, or run it with a raised/removed threshold** | Rejected as *our* fix: out of this repository's scope by explicit statement, and it makes correctness depend on a backend flag the Gateway cannot observe (SRO-09). Recorded in `DEPLOYMENT.md` as an operator option once upstream ships a `--grammar-max-repetitions`-style knob. |

---

## 4. Impact on the existing SRO requirements — precise target for the appsec pass

| Requirement | Status after this fix |
|---|---|
| **SRO-01** (exactly one constraint artifact, never `grammar`, never both fields) | **Unchanged.** |
| **SRO-02** (fully inlined; no `$ref`/`$defs`/`allOf`/`oneOf`/`anyOf`) | **Upheld and re-justified** — see R-1. **Amended in one detail:** the portable keyword list drops `maxLength`, becoming `type`, `properties`, `required`, `additionalProperties:false`, `enum`, `items`, `maxItems`. The rule "no `$ref`/`$defs`" is now backed by a correctness argument, not only a portability floor. |
| **SRO-04** (never trust the constraint; validation unconditional and un-shortcuttable) | **Unchanged, and strengthened in practice**: one more property (length) moves from "enforced twice" to "enforced where it is actually enforceable". `SOR-11`'s architecture test (no `StructuredResponseParser` dependency on `Backend`, no branch on `structured_output_mode`/`finish_reason`) is untouched and must still pass. |
| **SRO-08** (capability-verification recipe) | **Amended by SGB-07** — the toy schema is replaced by the production schema at 1 and `max-files-per-chunk` files; negative control and log check retained. The recorded llama.cpp build id of each backend (`GET /props`) is added to `DEPLOYMENT.md`. |
| **SRO-15** (`max-schema-bytes` backstop, 65536) | **Unchanged.** The schema gets strictly *smaller* (four `"maxLength": N` pairs removed per file). `worker.limits.max-constraint-bytes` (69632) is likewise unchanged. |
| **SRO-18 / `SOR-05a` (SOTB-KEY)** — Jackson `ObjectNode` tree only, no string templating, deterministic byte-identical output | **Unchanged and structurally untouched.** The diff *deletes* a `put` call; it adds no construction path. T-1.2's grep assertion (no `StringBuilder`/`String.format`/`+`) still holds verbatim, and T-1.5's determinism property is unaffected. **This is the guarantee appsec should be able to clear fastest.** |
| **SRO-20/21/22/23/24** (keyed-object coverage, everything required, no `file` field, `line: 0`, `severity` enum) | **All unchanged.** None of them is a repetition site. The coverage guarantee — the feature's actual product — is **not touched by this fix at all**. |
| **SRO-25** (per-file `summary` as the anti-skip device) | **Semantically unchanged**; it loses only its 200-char decoder cap. Brevity is now carried by the v3 prompt's "one-sentence" wording plus SGB-03's receipt truncation. |
| **SRO-27** (`maxItems` + `maxLength` as structural response-length bounds) | **Materially amended — the main change, and the main appsec target.** `maxItems` remains a decoder bound (SGB-02). `maxLength` is **demoted from a decoder bound to a receipt-side truncation bound** (SGB-01/SGB-03). Consequence for §10: the worst-case response size is no longer computable *from the schema*; it is bounded by `v3.yml`'s `maxTokens` (**[appsec correction]: 12000, not 8192** — `worker/src/main/resources/prompts/v3.yml:14`; the 8192 figure predates `chore/answer-reserve-consolidation` and must not be repeated in §10), then by `worker.limits.max-response-bytes` (200000) and `gateway.publish.max-raw-response-length` (200000) with `capRawResponseIfNeeded` → `TRUNCATED` classification. §10 must be rewritten to say so. |
| **SRO-32** (validation steps and their classified outcomes) | **Amended:** an over-length `comment`/`suggestion` is no longer a `SCHEMA_MISMATCH` outcome. It is not an outcome at all — it is a truncation with a counter. The four `FailureKind`s are otherwise unchanged, and `maxItems` overflow remains `SCHEMA_MISMATCH`. |
| **SRO-33** (findings → `ParsedComment`) | **Amended** only in that `comment`/`suggestion` may arrive pre-truncated (SGB-03/SGB-04). Fair-share and review-level caps unchanged. |
| **SRO-53 / `SOR-09`** (fence integrity, block-drop order) | **Unchanged, but newly load-bearing on an extra input**: truncated text is now a normal input to the renderer, so SGB-04 fixes the ordering explicitly and the assembled-body fence check still runs last. |
| **SRO-64 / 65 / 66 / 67** (coverage list in prompt, path alphabet, edge bounds, empty-set fail-closed) | **All unchanged.** |
| **SRO-19** (schema not persisted) | **Unchanged** — still reconstructible from `review_chunks.file_paths` + `reviews.prompt_version` + config. |

**New residuals for the threat model to rule on** (stated here so they are not discovered during SAST):

1. **Unbounded string generation at the decoder.** Removing `maxLength` lets a constrained model emit an arbitrarily long `comment`. Bounded by `maxTokens: 8192` → `finish_reason: length` → the existing `TRUNCATED` classification and retry. Assess whether that is a materially different DoS/token-burn profile from today's `OFF` default (where the same is already true and always has been).
2. **Truncation as a new text-mutation point.** SGB-04's ordering, surrogate-pair safety, and the interaction with `sanitizeCodeBlock`'s backtick-run collapsing (a truncation must not be able to *create* an unbalanced fence that `SOR-09`'s final check then has to drop).
3. **`CONSTRAINT_REJECTED` string matching** (SGB-06): the match tokens are Worker-constant and matched against a backend-controlled body. Confirm no backend-supplied text reaches `last_error` un-sanitized, and that the classification cannot alter the retry decision.

---

## 5. Check against the CLAUDE.md non-negotiables

| Principle | Compliance |
|---|---|
| Gateway is the sole owner of business logic and state | The schema is still built by `ReviewSchemaBuilder` in the Gateway; no decision moves anywhere. SGB-06 adds an audit-only string on an existing endpoint. R-7 was rejected specifically to keep this. |
| PostgreSQL is the single source of truth | No new state, no new column, no migration. The schema remains unpersisted and reconstructible (SRO-19). |
| Worker is a fully stateless HTTP client | Unchanged: it still attaches what it is handed and decides nothing. SGB-06 is an error-message classification on a response it already receives, feeding an endpoint it already calls. |
| Queue in PostgreSQL / no extra infrastructure | Nothing added. No new dependency in either module. |
| Idempotency everywhere a retry can happen | No touched path is newly retryable; SGB-03 *removes* a deterministic retry trigger. `RetryManager`, `not_before`, lock ordering (SRO-36 / CSR-17) untouched. |
| Fail fast at the edge | SGB-02 moves an operator misconfiguration from a runtime 250 ms fleet-wide failure to a startup failure naming the property. SGB-03 is not an edge decision — it is a presentation cap — and is deliberately not a rejection. |
| Restarts must not disturb in-flight work | No change to job state handling. |
| Boring over clever | The whole fix is a deletion plus one assertion. |

---

## 6. Empirical verification — required before any backend leaves `OFF`

Against the real backends **`192.168.1.82:8000`** and **`192.168.1.83:8000`** (both must pass independently; record `GET /props` `build_info` for each in `DEPLOYMENT.md`). Every probe is a plain `curl` to `POST /v1/chat/completions` with a tiny prompt; **every probe must also `grep` the llama-server log for `failed to parse grammar`**, because an HTTP 200 alone proves nothing (`SOR-INH-2`).

| Id | Probe | Pass/fail criterion |
|---|---|---|
| **P-0** | Reproduce: today's exact 4-file schema, unchanged defaults. | **Must fail** with `failed to parse grammar`. If it does not, the diagnosis in §1 is wrong and this design is void — stop and re-analyse. |
| **P-1** | **Decisive experiment.** The same schema for **one** file, unchanged defaults. | If it **also fails** ⇒ file count is not the trigger, §1.2 confirmed, SGB-01 is the fix and SGB-05 stays at 40 pending P-3. If it **succeeds** ⇒ `n_prev_rules` is cumulative, file count *is* a factor, and P-3 becomes a hard gate on `max-files-per-chunk`. **Either outcome is informative; the design branches only on SGB-05's value.** |
| **P-2** | Boundary: one file, `suggestion` `maxLength` at **2000**, then **1999**, then **1500**. | Expected: 2000 fails, 1999 passes. Confirms the `>= MAX_REPETITION_THRESHOLD` per-site formula exactly and validates §2.1's interim mitigation. A different boundary means the constant differs on these builds — record the measured value in `DEPLOYMENT.md`. |
| **P-3** | **Acceptance gate.** The **proposed** schema (SGB-01: no `maxLength`, `maxItems: 20`) at **1, 4, 10, 20, 40** files. | **All five must (a) compile — no grammar error in the log — and (b) return a response that `StructuredResponseParser` accepts, with every file key present.** If any file count fails, binary-search the first failing count `F`, set `gateway.structured.max-files-per-chunk = floor(F/2)` per SGB-05, and re-run. Constrained mode stays `OFF` fleet-wide until this passes on the specific backend being enabled. |
| **P-4** | Negative control (SRO-08, retained): a schema whose shape the model would not produce unprompted (e.g. a required key the prompt never mentions), plus a prompt actively asking for prose. | **The response must conform anyway.** A conforming-looking response with a grammar error in the log = fail-open; that backend stays `OFF`. |
| **P-5** | **Informational only, must not gate the fix.** The proposed schema re-expressed with the per-file entry under `$defs` + `$ref`. | Record three facts per backend: does it compile; does the response conform; does the log show a grammar error while HTTP is 200. Purpose: convert the base doc's *"Local `$defs` support is build-dependent"* from an unverified caveat into a recorded fact for the fleet we actually run, so SRO-02 is settled by evidence. **No outcome of P-5 changes this design** (R-1). |

Deliverable: a short `## Grammar probe results` section appended to `DEPLOYMENT.md` with each probe, each backend, build id, date, and outcome — this is the evidence the next canary is authorized against.

---

## 7. Rollout

Re-enter §11 of the base doc at **stage 2**, not stage 3: after P-3 and P-4 pass, set `structured_output_mode='RESPONSE_FORMAT_JSON_SCHEMA'` on **one** backend (`UPDATE`, no restart), on **one** pilot project. Watch `structuredConstraintSent{mode}`, `structuredValidationFailures{kind}` and the new `structuredFieldTruncated`. Rollback remains a single `UPDATE … = 'OFF'`. Stages 3 and 4 unchanged.

---

## 8. Hand-off

- **Next:** `appsec-engineer` — pre-implementation round against §4's amendments and its three new residuals; then `backend-developer` on `fix/structured-output-grammar-budget`; then `qa-engineer`; then the SAST round (`docs/security/feature-structured-output-grammar-budget-sast-report.md`, prefix `F-SOGB-`); then the fix round and final verification.
- **`docs/structured-review-output-architecture.md` edits required by this branch:** SRO-02 keyword list; SRO-08 → SGB-07; SRO-27 rewritten; SRO-32/33 amended; §10 first bullet ("`maxItems`/`maxLength` make the worst case computable") rewritten; §13.1's `maxLength`/`maxItems` sentence marked **REFUTED — see `docs/structured-output-grammar-budget-architecture.md` §1.1**.
- **§12 test-guidance deltas:** **T-1.4 is inverted** — assert the emitted schema contains **no** `maxLength` at any level, and that `maxItems` equals the configured value. **New T-1.10:** a golden-fixture test emitting the 1-file and `max-files-per-chunk`-file schemas used by SGB-07's recipe. **New T-4.11:** an over-length `comment`/`suggestion` is **truncated and published**, never a `SCHEMA_MISMATCH`, and the truncated body still passes the SRO-53/`SOR-09` fence-integrity check (including a suggestion whose truncation point falls inside a backtick run and inside a surrogate pair). **New T-2.9:** startup fails fast when `max-findings-per-file > 200`.
- **`README.md:267` is now wrong** and must change: `max-comment-chars`/`max-suggestion-chars` are **receipt-side truncation bounds only**, no longer `maxLength` in the schema. `DEPLOYMENT.md:924-925` likewise.

---

## 9. Unrelated observation

`docs/structural-exhaustiveness-gate-architecture.md` (untracked, working tree) describes a **different lineage** — a Worker-side schema in `ChatCompletionRequest.java:42-47`, `chat_template_kwargs.enable_thinking`, branch `feature/diff-anchored-comments`, findings prefixed `F-JS-`. None of that matches this repository's `worker/.../ChatCompletionRequest.java` (typed `JsonNode` pass-through, no schema) or its git history. Flagging it because if that work is real and lands, its `reviewedUnits` array is a **new repetition site** (`minItems: 1` + whatever `maxItems`) subject to the same 2000 budget, and its author needs §1 of this document. It is otherwise out of scope here.

**Sources:**
- [ggml-org/llama.cpp issue #20867 — `MAX_REPETITION_THRESHOLD` (2000) breaks tool-calling grammars](https://github.com/ggml-org/llama.cpp/issues/20867)
- [ggml-org/llama.cpp issue #21228 — json_schema with `$ref`/`$defs` silently fails: grammar rule count exceeds `MAX_REPETITION_THRESHOLD`](https://github.com/ggml-org/llama.cpp/issues/21228)
- [ggml-org/llama.cpp issue #20860 — Bug: Failed to parse grammar](https://github.com/ggml-org/llama.cpp/issues/20860)
- [`src/llama-grammar.cpp` — `MAX_REPETITION_THRESHOLD`, `handle_repetitions`, the `S{m,n}` rewrite](https://github.com/ggml-org/llama.cpp/blob/master/src/llama-grammar.cpp)
- [`common/json-schema-to-grammar.cpp` — `maxLength`/`maxItems` → `build_repetition`, local `$ref` resolution](https://github.com/ggml-org/llama.cpp/blob/master/common/json-schema-to-grammar.cpp)

---

Skipped: a Gateway-side grammar-cost simulator (R-5) and backend auto-demotion (R-7) — add only if P-3 shows the threshold is cumulative *and* upstream keeps the constant unconfigurable. The one thing that could not be settled from source is whether `n_prev_rules` is per-site or cumulative; P-1 answers it in about five minutes against `192.168.1.82:8000` and is the only probe that changes any number in this design.
