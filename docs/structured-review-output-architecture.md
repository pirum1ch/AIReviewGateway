# Architecture: Structured Review Output (decoder-constrained, coverage-enforced LLM responses)

Status: **DRAFT** — hand-off to `appsec-engineer` for the pre-implementation threat-model round. Branch: `feature/structured-review-output` (cut from the tip of `fix/worker-observability-and-claim-latency`, i.e. on top of diff chunking V2, Prompt Manager V3 and Worker Observability V4 — **not** from `master`).

Requirement prefix used in this document: **`SRO-nn`**. Appsec will layer its own `SOT-`/`SOR-` ids on top, same convention as `PMT-`/`PMR-` (Prompt Manager) and `WOT-`/`WOR-` (Worker Observability).

---

## 0. Scope, non-goals, and why this needs the full pipeline

Two production symptoms with today's review pipeline, both treated here as **given** (not re-derived):

1. **The model silently skips findings/files** it was expected to report on.
2. **The model does not reliably follow the required response format** — compliance is inconsistent.

Today's entire format-compliance mechanism is a *plain-text instruction inside the prompt template*. Confirmed against the code:

- `worker/src/main/resources/prompts/v1.yml:13-14` and `v2.yml:19-20` — `system:` block, verbatim: *"You always respond with valid JSON and nothing else -- no markdown fences, no prose before or after the JSON."*
- `worker/src/main/resources/prompts/v1.yml:19-26` / `v2.yml:31-38` — `user:` block, verbatim: *"Respond with ONLY a JSON array … where each element is an object with exactly these fields: file, line, severity, comment."*
- `worker/src/main/java/com/review/worker/llama/dto/ChatCompletionRequest.java:8-13` — exactly four fields (`model`, `messages`, `temperature`, `max_tokens`). **No `response_format`, no `grammar`, no `json_schema` field has ever existed in this codebase.**
- `src/main/java/com/review/gateway/service/CommentParser.java:142-149` — `extractJsonArraySlice` is a first-`[`-to-last-`]` scan; `:74-77` — anything that fails becomes **one `INFO`-severity comment containing the whole raw response**. Non-compliance is therefore *silently absorbed*, never surfaced.

So: the enforcement layer is "asking nicely", and the detection layer is "silently degrade". This feature replaces both.

### 0.1 What this feature is

Two complementary mechanisms, neither of which touches the Worker's statelessness or the shape of the Gateway↔Worker job contract:

- **(a) Decoder-level constrained output**: the Gateway computes a JSON Schema per claimed chunk job and ships it in `ClaimJobResponse.payload`; the Worker attaches it **verbatim** to its single `POST /v1/chat/completions` call. llama-server converts it to a GBNF grammar and constrains sampling, so a non-conforming response is *not representable*.
- **(b) Structural per-file coverage**: the schema's `files` object is keyed by **exactly** the chunk's known file paths (Gateway-owned, already-sanitized data from `review_chunks.file_paths`), all `required`, `additionalProperties: false`. A response literally cannot omit or invent a file.
- **(c) Unconditional Gateway-side validation** of the received response against the same schema/file list. This is *not* redundant with (a): llama.cpp is documented to **fail open** when grammar construction fails (see §3.3), and some builds ignore structured-output fields entirely. (a) makes conformance the default; **(c) is what makes the guarantee real** and turns "the model skipped a file" from an invisible outcome into a retryable, counted, logged event.

### 0.2 Explicit non-goals (do NOT implement on this branch)

- **GitLab-native positioned `suggestion:` threads** ("Apply suggestion" → real commit). Explicitly deferred to a separate feature — full rationale and the list of prerequisites in §6.2. This branch ships the *content-field* form of a suggestion block only.
- **Moving per-file decomposition into the Worker** (N LLM calls per chunk). Already evaluated and rejected in a prior architecture review; nothing here revisits it. The model here remains **one llama-server call per claimed job**, one `POST /jobs/{id}/result` per job, unchanged claim → heartbeat → infer → submit loop shape.
- **A GBNF `grammar` generator in the Gateway.** See §9.2 for why JSON Schema is the only artifact this feature produces.
- **Changing `DiffChunker`'s packing behavior for existing prompt versions.** The new file-count bound (SRO-14) is applied *only* for structured prompt versions, so v1/v2 chunking stays byte-for-byte identical (the §8 backward-compat guarantee of the diff-chunking feature).
- **Any new infrastructure** (Redis/Kafka/Prometheus/a JSON-Schema validation library that pulls a transitive tree): forbidden by CLAUDE.md. Validation is hand-rolled against the same builder that produced the schema — see SRO-30.
- **Retiring `CommentParser`.** It stays, untouched in behavior, as the parser for v1/v2 Reviews and as the documented emergency-fallback path (SRO-36).

### 0.3 New dependencies

**None.** Jackson (`ObjectMapper`/`JsonNode`) is already on both modules' classpaths (`CommentParser`, `LlamaClient`, `QueueManager.parseFilePaths`). No `pom.xml` change in either module.

---

## 1. Summary

- **Gateway computes, Worker forwards verbatim.** A new `ReviewSchemaBuilder` (business logic) renders a per-chunk JSON Schema from `review_chunks.file_paths`; a new `DecoderConstraintRenderer` (transport adaptation) wraps it in the wire shape the *target backend* understands, chosen by a new `backends.structured_output_mode` column — exactly the precedent set by `backends.prompt_message_format` + `PromptMessageFormatter` (Prompt Manager V3, PMR-22). `QueueManager.claimJobRow` attaches the result to `ClaimedJob`; `JobPayload` gains **two nullable String fields** (`responseFormat`, `jsonSchema`), at most one ever non-null.
- **The Worker gains no new logic.** `ChatCompletionRequest` gains two `NON_NULL`-included fields; `PromptTemplateService.resolve` gains two pass-through parameters; `LlamaClient.startChatCompletion` gains one `DecoderConstraint` parameter with a delegating overload so no existing call site or test changes. The Worker parses the Gateway-supplied text into a `JsonNode` (bounded, must be an object) and attaches it. It never builds, edits, inspects, or re-derives it.
- **Coverage is structural, not instructional.** `files` is a JSON object keyed by the chunk's exact file paths, every key `required`, `additionalProperties: false`. Not an array-of-enum: an array cannot express "each file exactly once" (`uniqueItems` is **not** expressible in GBNF), so an array+enum schema still permits "same file twice, other file omitted" — the exact failure mode we are eliminating. See §9.3.
- **Every property is `required`; "nothing to say" is expressed by a sentinel value**, never by omission (`line: 0` = file-level, `suggestion: ""` = none). This removes the last piece of *structural* bookkeeping from the model: the grammar becomes one fixed, fully-determined token sequence skeleton, and the model spends its budget only on content (user priority 1).
- **New `promptVersion` `v3`.** `v1.yml`/`v2.yml` are not touched — the README's "byte-for-byte identical for existing versions" guarantee holds. `v3.yml` ships its own `maxTokens: 8192` override (templates already support this).
- **Parsing becomes strict for v3.** A new `StructuredResponseParser` does a direct, non-fallback `readTree` + structural validation; there is no `[`-scan and no whole-response fallback. A non-conforming response is a **retryable job failure** routed through the existing `RetryManager.requeueOrFail` (attempts-bounded, `not_before`-delayed), *not* a terminal `FAILED` and *not* a silent single-comment fallback. Escape hatch: `gateway.structured.on-invalid-response=RETRY_THEN_FALLBACK`.
- **Truncation becomes detectable.** `finish_reason` (already parsed into `Choice` and then thrown away — `worker/.../llama/dto/Choice.java:8`) is propagated Worker → Gateway → `review_results.finish_reason`, so `length` (max-tokens exhausted) is distinguishable from "the model produced garbage" and produces a precise operator message instead of a mystery.
- **Comment assembly becomes a fixed Gateway-owned template** (`CommentRenderer`): severity/file header + prose comment + optional `diff`-fenced context block extracted from the already-stored `review_chunks.diff` + optional plain-fenced suggestion block. Code content gets its own sanitizer (`sanitizeCodeBlock`) — today's prose pipeline (`stripQuickActionLines` → `neutralizeMentions` → `htmlEscape`) would corrupt code (see §6.3).
- **One Flyway migration (`V5`), three additive nullable/defaulted columns, no new endpoint, no new `EventType`, no change to `ck_event_type`.**

---

## 2. Why prompt-only enforcement cannot be fixed by a better prompt

Recorded here so the decision is not relitigated later.

| Observation | Evidence in this repo |
|---|---|
| Format compliance is a *sampling* property, not an instruction-following property. Every token is drawn from an unconstrained distribution; the instruction only shifts probabilities. | The instruction is already maximally explicit (`v1.yml:13-14`, `:19-26`) and still fails in production. |
| The failure is silently absorbed, so there is no feedback signal to tune against. | `CommentParser.parse:74-77` — a non-JSON response becomes one `INFO` comment; `ResultProcessor` transitions the job to `COMPLETED` ("`parsed=1`"). No metric, no event, no `last_error`. |
| Coverage cannot be expressed as an instruction at all. | `v2.yml:22-24` already tells the model *"Only comment on the files actually shown to you in the current part"* — a negative constraint. There is no prompt phrasing that makes *omission* observable, because an omitted file produces no token. |
| A "checklist" reminder in prompt text is the same category of mechanism that already failed. | Stated directly in this feature's brief; the design therefore expresses the file list **in the grammar**, not in prose. |
| The Gateway already owns the exact ground truth. | `DiffChunker.DiffChunk.filePaths()` (`src/main/java/com/review/gateway/service/DiffChunker.java:32`), sanitized in `ReviewService.persistNewReview:322-327`, persisted to `review_chunks.file_paths`, and already re-read at claim time by `QueueManager.buildChunkContext:289`. Nothing new has to be computed or trusted. |

---

## 3. Part 1 — Decoder-level constrained output (transport)

### 3.1 What llama-server actually accepts

Verified against llama.cpp's current server documentation and issue tracker (this repository pins nothing: `DEPLOYMENT.md:403-405` explicitly states *"running/configuring `llama-server` itself is out of this repository's scope"*, and there is **no version pin anywhere** in `worker/README.md` or `DEPLOYMENT.md` — see SRO-08):

| Wire shape | Endpoint | Notes |
|---|---|---|
| `response_format: {"type":"json_schema","json_schema":{"name":…,"strict":true,"schema":{…}}}` | `/v1/chat/completions` | OpenAI-canonical. Accepted by recent builds. |
| `response_format: {"type":"json_object","schema":{…}}` | `/v1/chat/completions` | llama.cpp-native legacy shape, documented in `tools/server/README.md`. |
| `json_schema: {…}` (top level) | `/completion`, and passes through on `/v1/chat/completions` on builds where `params_from_json_cmpl` reads it | llama.cpp-native. |
| `grammar: "<GBNF>"` (top level) | both | **Mutually exclusive with `json_schema`** — the server rejects with *"Either \"json_schema\" or \"grammar\" can be specified, but not both"*. Not used by this design (§9.2). |

**Known footguns this design must survive:**

- **Mutual exclusivity.** `grammar` and `json_schema` cannot both be present. Some builds' chat templates (with `--jinja`) inject a `grammar` internally for tool-calling, which has produced spurious "both specified" errors even when the client sent only `response_format`.
- **Silent fail-open.** If schema→grammar conversion succeeds but grammar *parsing* then fails (e.g. an unsupported regex in `pattern`), llama-server logs the error and **continues generating unconstrained, returning HTTP 200**. This was reported and closed as *not planned*. A client that trusts the constraint is therefore trusting a mechanism with a documented silent-failure mode.
- **Structured output silently ignored** on older/minimal builds that do not implement the field at all.
- **No `$ref`/`$defs` guarantee.** The server README itself says *"For schemas w/ external `$ref`s, use `--grammar` + `json_schema_to_grammar.py` instead."* Local `$defs` support is build-dependent.
- **Required properties are emitted in schema-declaration order**, optional properties trail them. Property *order* is therefore part of the contract, not cosmetic.

| Id | Requirement |
|---|---|
| **SRO-01** | The Gateway MUST produce **exactly one** constraint artifact per claim: a JSON Schema. It MUST NOT ever emit a `grammar` field, and MUST NOT emit both `responseFormat` and `jsonSchema` on the same payload — mirroring llama.cpp's own mutual-exclusivity rule at the source rather than discovering it as a 400 at inference time. |
| **SRO-02** | The generated schema MUST be **fully inlined**: no `$ref`, no `$defs`, no external references, no `allOf`/`oneOf`/`anyOf`. It uses only the most portable keyword set: `type`, `properties`, `required`, `additionalProperties: false`, `enum`, `items`, `maxItems`, `maxLength`. This is a deliberate portability floor (SRO-08), paid for with schema verbosity, bounded by SRO-14/SRO-15. |
| **SRO-03** | Property declaration order is **normative**, not incidental: within each object, properties are declared in the exact order the model must emit them. Specifically `findings` **before** `summary` at every level — so a summary is written *after* the findings it summarizes, never guessed before them. Covered by a QA test (§12, T-1.6). |
| **SRO-04** | Structured output MUST be treated as a **best-effort optimization at the transport layer**. Nothing in the Gateway may assume the constraint was applied. §5's validation is unconditional and runs identically whether the backend honored the constraint, ignored it, or failed open. |

### 3.2 Where the wire shape is decided: `backends.structured_output_mode`

The wire shape is a **per-llama-server-build quirk**, and this codebase already has exactly one place for per-backend LLM quirks: the `backends` table. Prompt Manager V3 put `prompt_message_format` (`MULTI`/`SINGLE`) there and parses it with `PromptMessageFormat.fromNullable`, never `Enum.valueOf`, precisely so a bad value degrades with a WARN instead of taking the claim path down (`Backend.java:61-72`). This feature follows that precedent byte-for-byte.

| Id | Requirement |
|---|---|
| **SRO-05** | New column `backends.structured_output_mode VARCHAR(32)` (V5, §7), nullable, `NULL` = use `gateway.structured.default-mode`. Mapped as a **plain `String`** on `Backend`, never `@Enumerated` — parsed via `StructuredOutputMode.fromNullable`, an unrecognized value degrading to `OFF` with a WARN (same rationale as `promptMessageFormat`). |
| **SRO-06** | `StructuredOutputMode` values: <br>• `OFF` — no constraint field is sent at all (today's request body, byte-identical). <br>• `RESPONSE_FORMAT_JSON_SCHEMA` — `payload.responseFormat = {"type":"json_schema","json_schema":{"name":"code_review","strict":true,"schema":<S>}}`. <br>• `RESPONSE_FORMAT_SCHEMA` — `payload.responseFormat = {"type":"json_object","schema":<S>}` (llama.cpp-native legacy). <br>• `TOP_LEVEL_JSON_SCHEMA` — `payload.jsonSchema = <S>`. |
| **SRO-07** | Default (`gateway.structured.default-mode`) is **`OFF`**. A fresh deployment of this branch changes *nothing* about the bytes sent to llama-server until an operator opts a backend in. This is the first stage of the rollout ladder in §11. |
| **SRO-08** | `DEPLOYMENT.md` MUST gain a **capability-verification recipe** (one `curl` against the operator's actual `llama-server`, sending a two-field toy schema and asserting the response conforms) plus an explicit statement that **this repository pins no llama.cpp version** and that structured output is a *backend capability*, discovered per backend, not assumed. The recipe MUST include the instruction to check the llama-server log for `failed to parse grammar` — the only client-visible symptom of the fail-open bug. |
| **SRO-09** | The Gateway MUST NOT probe a backend's structured-output capability itself. `DEPLOYMENT.md:82-85,120-121` and the baseline architecture both state the Gateway calls **only** `GET {backend.url}/health` and never the chat-completions endpoint; adding an inference call to the health probe would break that invariant, cost a model-load-sized latency inside a `@Scheduled` tick, and duplicate what §5's validation already measures continuously. Capability is inferred from the *validation-failure rate* (SRO-45), which is the honest signal anyway — it also catches fail-open, which a capability probe would not. |

### 3.3 Contract change: `ClaimJobResponse.payload`

Follows the `systemMessages` precedent exactly (Gateway-computed, Worker-verbatim, `null` = "not applicable", forward-compatible in both directions).

```
POST /jobs/claim  ->  200
{
  "jobId": 456,
  "reviewId": 123,
  "payload": {
    "diff":           "...",              # unchanged
    "promptVersion":  "v3",               # unchanged
    "chunkContext":   "...",              # unchanged (V2)
    "systemMessages": ["...", "..."],     # unchanged (V3)
    "responseFormat": "{\"type\":\"json_schema\",\"json_schema\":{...}}",   # NEW, nullable
    "jsonSchema":     null                                                   # NEW, nullable
  }
}
```

| Id | Requirement |
|---|---|
| **SRO-10** | `src/main/java/com/review/gateway/dto/JobPayload.java` gains `String responseFormat, String jsonSchema` (canonical record components, appended last). Its masked `toString()` (CSR-14/PMR-25) MUST be extended to render both as `<masked, N chars>` — the schema embeds MR-author-controlled file paths and must not be dumped whole into an accidental `log.debug("{}", payload)`. |
| **SRO-11** | `src/main/java/com/review/gateway/service/dto/ClaimedJob.java` gains the same two components, same masking treatment. |
| **SRO-12** | `worker/src/main/java/com/review/worker/gateway/dto/JobPayload.java` (the mirror) gains the same two components, same masking. It already carries `@JsonIgnoreProperties(ignoreUnknown = true)` (PMT-05/PMR-24), so an **old Worker against a new Gateway** ignores both fields and runs exactly as today; a **new Worker against an old Gateway** sees `null`/`null` and likewise runs exactly as today. No coordinated deploy is required in either direction. |
| **SRO-13** | Exactly one of the two MUST be non-null, or both null. The Gateway guarantees this by construction (`DecoderConstraintRenderer` returns a sealed two-shape result); the **Worker re-checks it** and abandons the job with the new reason `CONSTRAINT_INVALID` if both are non-null — a defensive bound against a misbehaving/compromised Gateway, exactly like `worker.limits.max-diff-bytes` (WSR-03). |

---

## 4. Part 2 — The schema (coverage + fixed shape)

### 4.1 Shape

For a chunk whose known paths are `src/A.java`, `src/B.java` (illustrative; the real document is fully inlined for **every** path):

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["files", "summary"],
  "properties": {
    "files": {
      "type": "object",
      "additionalProperties": false,
      "required": ["src/A.java", "src/B.java"],
      "properties": {
        "src/A.java": {
          "type": "object",
          "additionalProperties": false,
          "required": ["findings", "summary"],
          "properties": {
            "findings": {
              "type": "array",
              "maxItems": 20,
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["line", "severity", "comment", "suggestion"],
                "properties": {
                  "line":       { "type": "integer" },
                  "severity":   { "type": "string", "enum": ["critical", "major", "minor", "info"] },
                  "comment":    { "type": "string", "maxLength": 1200 },
                  "suggestion": { "type": "string", "maxLength": 2000 }
                }
              }
            },
            "summary": { "type": "string", "maxLength": 200 }
          }
        },
        "src/B.java": { "...identical, inlined again..." }
      }
    },
    "summary": { "type": "string", "maxLength": 500 }
  }
}
```

### 4.2 Design rules and their rationale

| Id | Requirement |
|---|---|
| **SRO-20** | `files` is an **object keyed by file path**, not an array of objects with a `file` field constrained to an `enum`. An array cannot express "each of these N files exactly once": `uniqueItems` is not expressible in GBNF, so `minItems: N` + `maxItems: N` + `file: {enum: [...]}` still permits *the same file N times and every other file omitted* — i.e. it does not actually close symptom 1. The keyed object closes it structurally: `required` lists all N keys and `additionalProperties: false` forbids any other, so the key set is provably equal to the chunk's file set. |
| **SRO-21** | **Every property at every level is `required`.** "Nothing to report" is expressed with sentinel *values*, never by omission: `findings: []`, `summary: "<one short sentence>"`, `line: 0` (= file-level, no specific line), `suggestion: ""` (= no code suggestion). Rationale (user priority 1): an optional property is a *decision the model must make*, and the converter places optional properties after required ones behind an alternation — i.e. it hands the model a branch point. With everything required, the grammar is one fixed skeleton and the model's entire remaining freedom is content. |
| **SRO-22** | There is **no `file` field inside a finding** — the enclosing key is the file. One fewer value the model can get wrong, and it makes file/finding association structural rather than asserted. |
| **SRO-23** | `line: 0` maps to `null` on the Gateway side with **zero new code**: `CommentParser.normalizeLineNumber` already returns `null` for any non-positive value (`CommentParser.java:250-252`), and `StructuredResponseParser` reuses that exact normalization. Deliberately no `minimum: 1` in the schema — numeric bounds are a less-exercised corner of the converter, and the normalization already exists. |
| **SRO-24** | `severity` is an `enum`. This alone deletes `CommentParser.parseSeverity`'s silent `unknown -> INFO` degradation (`CommentParser.java:181-190`) for v3 Reviews: an out-of-set severity is now *unrepresentable* at the decoder and a *validation failure* at the Gateway, never a silent downgrade. |
| **SRO-25** | The per-file `summary` (`maxLength` 200, required) is the anti-skip device that costs the least: it forces the model to emit content *about every file* even when it has no findings, which is exactly the token-level attention that "silently skipping a file" avoids. Toggle: `gateway.structured.per-file-summary` (default `true`) — see §10 for the token-budget trade-off. When `false`, the per-file object is `{"findings": [...]}` only. |
| **SRO-26** | The chunk-level `summary` is declared **last** (SRO-03) and populates `review_results.summary`, a V1 column that has been written as `null` since the beginning (`ResultProcessor.storeRawResult:187`). Free value from a field we now get for structural reasons anyway. |
| **SRO-27** | `maxItems` on `findings` (`gateway.structured.max-findings-per-file`, default `20`) and `maxLength` on `comment`/`suggestion`/`summary` are **structural response-length bounds**, not cosmetics: they are what makes the worst-case response size computable (§10) and what keeps the grammar from admitting an unbounded generation. |
| **SRO-28** | The keys are the **already-sanitized** paths from `review_chunks.file_paths` (sanitized at persist time via `ChunkContextRenderer.sanitizePath` — `ReviewService.java:322-325`), JSON-string-escaped when rendered into the schema. They are the same strings the model already sees in `{{CHUNK_CONTEXT}}`, and the same strings that end up in `review_comments.file_path`. One vocabulary, end to end. |

### 4.3 Preconditions the Gateway must enforce at the edge

Two cases would leave a v3 Review with a *weaker* coverage guarantee than advertised. Per "fail fast at the edge", both are rejected at `POST /reviews` rather than degraded silently mid-pipeline.

| Id | Requirement |
|---|---|
| **SRO-14** | `DiffChunker.split` gains a third parameter `maxFilesPerChunk` (`0` = unbounded). When non-zero it bounds a chunk's file count in `binPack` (emit the current chunk once `currentPaths.size()` would exceed it) **and defeats the single-chunk shortcut** (`DiffChunker.java:105-109`) when the whole-diff file count exceeds it. Passed as `gateway.structured.max-files-per-chunk` (default `40`) **only** for structured prompt versions; `0` for every other version, so v1/v2 chunking is byte-for-byte unchanged. Overflow past `gateway.diff.max-chunks` reuses the existing `DiffTooLargeException`/`422 DIFF_TOO_LARGE` path — no new error code, no new failure semantics. |
| **SRO-15** | `ReviewSchemaBuilder` MUST refuse to render a schema larger than `gateway.structured.max-schema-bytes` (default `65536`). With SRO-14 in force this is unreachable in practice; it is the backstop that makes "fully inlined, no `$ref`" (SRO-02) safe. Exceeding it at claim time is an internal invariant violation → the job fails through the existing `RetryManager` path with a precise `last_error`, never a dispatched oversized request. |
| **SRO-16** | `DiffChunker.ChunkPlan` gains a `boolean pathsTrusted` component (it is already computed internally — `ParsedDiff.pathsTrusted`, `DiffChunker.java:256`, and is `false` for the `--- `-delimiter fallback per CSR-11). If a structured `promptVersion` is requested and `pathsTrusted == false`, `POST /reviews` MUST fail fast with **HTTP 422 `STRUCTURED_OUTPUT_UNSUPPORTED`** (new `StructuredOutputUnsupportedException`, mapped in `GlobalExceptionHandler` above the generic handler). Message: *"promptVersion 'v3' requires git-style `diff --git` headers to derive the per-file coverage list; submit a `git diff` or use promptVersion 'v2'."* Rationale: a chunk with an empty `filePaths` list would produce a schema with **no coverage constraint at all** — i.e. exactly the failure this feature exists to remove, silently. |
| **SRO-17** | Same 422 if path sanitization *dropped* any path (`sanitizedPaths.size() != chunk.filePaths().size()` in `ReviewService.persistNewReview:322-325`) under a structured version — a dropped path is an uncovered file. Practically unreachable (a path made entirely of control/format characters), but "practically unreachable" is not "detectably impossible", and this is the one place a coverage hole could otherwise open without a trace. |

### 4.4 Where the schema is built, and by whom

```
ReviewService.createReview                     QueueManager.claimJobRow (TX: job row only)
  |                                                  |
  +- DiffChunker.split(diff, promptTokens,           +- chunk = review_chunks[reviewId, chunkIndex]
  |    maxFilesPerChunk)                             +- paths = parseFilePaths(chunk.file_paths)   <-- already read today
  +- SRO-16/17 edge validation                       |      (same call buildChunkContext already makes)
  `- persist review_chunks.file_paths (sanitized)    +- if promptVersion in STRUCTURED_OUTPUT_PROMPT_VERSIONS
                                                      |     and gateway.structured.enabled
                                                      |     and backendMode != OFF:
                                                      |        schema     = ReviewSchemaBuilder.build(paths, opts)
                                                      |        constraint = DecoderConstraintRenderer
                                                      |                       .render(schema, backendMode)
                                                      `- ClaimedJob(..., constraint.responseFormat(),
                                                                         constraint.jsonSchema())
```

| Id | Requirement |
|---|---|
| **SRO-18** | `ReviewSchemaBuilder` is a **pure function** over `(List<String> filePaths, SchemaOptions)` — no DB access, no HTTP, no state — exactly like `DiffChunker`/`ChunkContextRenderer`/`DiffSizeValidator`. Deterministic: the same inputs must produce byte-identical output, so §5's validator can be driven from the same builder without storing the schema. |
| **SRO-19** | The schema is **not persisted**. It is fully reconstructible from `review_chunks.file_paths` + `reviews.prompt_version` + config, which is the "nothing is cached that isn't reconstructible from the DB after a restart" rule read correctly. (Contrast with `review_prompt_sections`, which *is* persisted because its input — a Git repo at a moving ref — is *not* reconstructible.) `gateway.structured.*` config values that affect the schema are recorded once per Review in `review_events` (`details`) so a historical schema can be reconstructed after a config change — see SRO-46. |

---

## 5. Part 3 — Gateway-side validation, parsing, and retry semantics

### 5.1 The new parsing contract

| Id | Requirement |
|---|---|
| **SRO-30** | New `StructuredResponseParser` (Gateway), used **only** when `reviews.prompt_version ∈ STRUCTURED_OUTPUT_PROMPT_VERSIONS`. `CommentParser` is not modified and remains the parser for every other version. Validation is **hand-rolled against the same file list `ReviewSchemaBuilder` used** — no JSON-Schema validation library is added (CLAUDE.md "no extra infrastructure"; and the schema is machine-generated by us, so a generic validator would be validating our own output against our own generator). |
| **SRO-31** | Parsing is **direct and non-fallback**: `objectMapper.readTree(raw.strip())`. There is **no** `extractJsonArraySlice`-style first-`[`-to-last-`]` scan, **no** markdown-fence stripping, **no** prose tolerance. `strip()` (leading/trailing whitespace) is the only normalization permitted. A response with a `<think>` preamble, a markdown fence, or trailing prose is a **failure**, not something to salvage — under this feature "malformed" means *the constraint did not apply*, which is an infra/config fact the operator must see, not a model mood to work around. |
| **SRO-32** | Validation steps, in order, each producing a distinct classified outcome: <br>1. `NOT_JSON` — `readTree` threw, or the root is not an object. <br>2. `SCHEMA_MISMATCH` — missing/extra top-level key; `files` not an object; a file entry missing `findings`/`summary`; a finding missing a required field or with a wrong JSON type; `severity` outside the enum. <br>3. `COVERAGE_SHORTFALL` — `files`' key set ≠ the expected path set. Missing and unexpected keys are reported **separately** in `last_error` (sanitized, capped), because "omitted `src/B.java`" and "invented `src/Z.java`" are different diagnoses. <br>4. `TRUNCATED` — `finish_reason == "length"`, **or** the raw response was truncated by `ResultProcessor.capRawResponseIfNeeded`, **or** `readTree` failed with an unexpected-end-of-input. Checked *before* reporting `NOT_JSON` so the operator gets the actionable message. |
| **SRO-33** | On success, each finding becomes a `ParsedComment` with `filePath` = the enclosing key, `lineNumber` = `normalizeLineNumber(line)`, `severity` = `Severity.valueOf(upper(severity))`, `text` = the **rendered** comment body (§6). The existing per-chunk fair-share cap (`ResultProcessor.fairShareCommentCap`) and the review-level cap under the parent lock (`ChunkCoordinator.persistCappedComments`, CSR-21) are unchanged and still apply — SRO-27's `maxItems` bounds what the model can *emit*, those caps bound what gets *published*. |
| **SRO-34** | A file whose `findings` is empty produces **no** `ParsedComment` (we do not publish "no issues found in X" as 40 separate MR comments). Its `summary` is retained only in `review_results.raw_response` (already mandatory) and contributes to the coverage metric. |

### 5.2 A non-conforming response is retryable, not terminal

This is the load-bearing behavioral change and it needs care, because **today a parse failure is terminal with no retry**: `ResultProcessor.processJobPhase:140-149` catches the exception and transitions the job straight to `FAILED` inside the job-row lock.

| Id | Requirement |
|---|---|
| **SRO-35** | A structured-validation failure MUST be routed through `RetryManager.requeueOrFail(jobId, reason, workerId)` — i.e. it consumes one of the `gateway.retry.max-attempts` (3) attempts, is spaced by `gateway.retry.requeue-delay` / `review_jobs.not_before` (V4), and becomes `FAILED` only when the attempt budget is exhausted. Rationale: a single bad generation on a backend that *is* correctly configured should be retried (it is a sampling event); a *systematically* non-conforming backend burns its three attempts in ~2 minutes and fails loudly with a precise `last_error`. This is exactly the distinction "infra/config bug" vs. "one bad roll" that the retry counter already encodes. |
| **SRO-36** | **Lock ordering (CSR-17) is non-negotiable here.** `RetryManager.requeueOrFail` opens its own `REQUIRES_NEW` transaction and takes the **same** `review_jobs` row lock that `ResultProcessor.processJobPhase` is already holding. It MUST therefore be called **after** phase 1 has committed, from `ResultProcessor.process` (the plain, non-`@Transactional` orchestrating method) — never from inside `processJobPhase`. Concretely: on a validation failure `processJobPhase` **leaves the job `RUNNING`** and returns a new `JobPhaseOutcome` variant (`validationFailure(chunkIndex, classifiedReason)`); `process` then calls `retryManager.requeueOrFail(...)`, which does its own recompute via `ChunkCoordinator`. This mirrors, one-for-one, the pattern `QueueManager.claimJobRow`/`failJobForMissingPromptSections` established for PMR-09 and `QueueManager.reportFailure` established for WOC-26. |
| **SRO-37** | The raw response is still stored **before** parsing, unconditionally (req. 1.9, `ResultProcessor.processJobPhase:131-135`) — unchanged. Note the consequence, deliberately accepted: `existsByReviewIdAndChunkIndex` means the **first** attempt's raw response is the one preserved; a retry's raw response is not stored. That is the right one to keep for forensics ("what did the model actually emit the first time it broke"), and storing N raw responses per chunk would change the `(review_id, chunk_index)` uniqueness contract that idempotent redelivery depends on. Documented in `README.md`, not changed. |
| **SRO-38** | `gateway.structured.on-invalid-response` — `RETRY_THEN_FAIL` (**default**) or `RETRY_THEN_FALLBACK`. Under `RETRY_THEN_FALLBACK`, once the attempt budget is exhausted the Gateway makes **one** last pass through the legacy `CommentParser` (tolerant) so a Review still publishes *something* rather than ending `FAILED`. This is the documented rollout/emergency escape hatch, **not** the default: silent degradation is precisely the behavior this feature exists to remove, and a `FAILED` Review with `last_error = "structured-output: COVERAGE_SHORTFALL; missing=src/B.java"` is a far better outcome than a published comment that quietly reviewed 3 of 4 files. When the fallback fires it MUST log **WARN** and increment its own counter — it is never invisible. |
| **SRO-39** | `gateway.structured.enabled` (default `true`) is the global kill switch: when `false`, no constraint is ever emitted **and** v3 Reviews are parsed by the legacy `CommentParser`. This makes v3 behave exactly like v2 in an emergency, without a redeploy or a schema change, and without stranding Reviews already in flight. |
| **SRO-40** | `review_events` gains **no new `EventType`** and `ck_event_type` is untouched. The origin is carried in the existing `RETRY`/`FAILED` rows' `details`, standardized as `"structured-output: <CLASS>[; <detail>]"` — the same discriminator-prefix technique WOC-30 chose over a new event type, for the same reasons (no CHECK-constraint migration, no double event per failure, no `StatisticsService` ambiguity). |
| **SRO-41** | `review_jobs.last_error` MUST carry the classified reason plus a **sanitized, capped** detail (missing/unexpected file paths are MR-author-controlled text). `RetryManager` already sanitizes via `TextSanitizer.sanitizeSingleLine(..., 512)` and appends its own Gateway-constant attempt suffix last (WOR-04/WOT-12), so the origin prefix is not forgeable — reuse that machinery verbatim, add nothing. |

### 5.3 `finish_reason` propagation

| Id | Requirement |
|---|---|
| **SRO-42** | `worker/.../llama/LlamaClient.toResult` MUST propagate `firstChoice.finishReason()` into `LlamaResult` (the field is already parsed into `Choice` — `worker/.../llama/dto/Choice.java:8` — and then discarded today). `ResultRequest`/`SubmitResultRequest`/`SubmitResultCommand` gain a nullable `finishReason` (`@Size(max = 32)`). |
| **SRO-43** | The Gateway MUST **whitelist-parse** `finishReason` against its own closed set (`stop`, `length`, `content_filter`, `tool_calls`), mapping anything else to `unknown` with a DEBUG line — never `Enum.valueOf`, never a `400`. Same forward-compat rule as WOC-23/`promptMessageFormat`. Stored in the new nullable `review_results.finish_reason` column (V5). |
| **SRO-44** | Backward compatibility is bidirectional and requires no coordinated deploy: Spring Boot's Jackson defaults have `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so a **new Worker → old Gateway** is safe; an **old Worker → new Gateway** simply yields `finishReason == null`, which validation treats as "unknown" and never as a failure on its own. |

### 5.4 Metrics — the before/after instrument (answers question 5)

The rollout is worthless without a measurement, and the measurement has to start working **before** v3 is used at all.

| Id | Requirement |
|---|---|
| **SRO-45** | `MetricsCounters` (process-local, WOR-03 precedent — deliberately not persisted, since a per-event `review_events` INSERT driven by model output would be an unbounded-INSERT primitive) gains: <br>• `legacyParseFallback` — incremented by `CommentParser` **whenever the whole-response fallback path is taken** (`CommentParser.java:74-77`). This is the single cheapest instrument for symptom 2 and **it measures today's v1/v2 traffic**, giving a genuine baseline before any v3 Review exists. <br>• `structuredValidationFailures{kind}` — keyed by `NOT_JSON` / `SCHEMA_MISMATCH` / `COVERAGE_SHORTFALL` / `TRUNCATED`. <br>• `structuredConstraintSent{mode}` — how many claims actually carried a constraint, per mode (distinguishes "the model is good" from "we never turned it on"). <br>• `structuredFallbackUsed` — SRO-38 fires. <br>All surfaced through `MetricsSnapshot`/`GET /metrics`, which already carries `ownershipMismatches` and `workerFailureReportsIgnored` in exactly this style. |
| **SRO-46** | (SHOULD) `StatisticsService.computeMetrics` gains `averageFileCoverageRatio` — `distinct review_comments.file_path per review ÷ file count in review_chunks.file_paths`, DB-derived, one query, at 20–30 MRs/day. It is a *proxy* on v1/v2 (a file with genuinely no issues legitimately draws no comment) but it is directionally the symptom-1 signal, and comparing its distribution before/after v3 is the cheapest available evidence. Marked SHOULD because the strict coverage number is only meaningful under v3, where SRO-32 measures it exactly. |

---

## 6. Part 4 — Comment assembly, and the "suggestion block" ambiguity

### 6.1 Decision on question 3: **(i) in scope, (ii) explicitly deferred**

**Scoped into this feature — (i), a content field.** `suggestion` is a plain string in the model's JSON response, rendered by the Gateway as a fenced code block inside the comment body it already posts. No GitLab API change, no `position` object, no new endpoint, no `startSha`, no "Apply" affordance.

**Deferred to a separate future feature (`feature/gitlab-positioned-suggestions`) — (ii), GitLab-native positioned suggestions.** Justification against the stated priorities:

- Priority 3 asks that *"the response must always be templated/standard/identical in shape"* so that Gateway's comment assembly (diff context + comment text + suggestion block) is **trivial and uniform**. That is a statement about the *upstream shape*, and it is fully satisfied by (i): the shape is guaranteed by the schema, and assembly becomes a fixed template. Nothing in priority 3 requires the *click-to-apply* affordance.
- (ii) does not currently work at all and is not one change but five, three of which are security-relevant:
  1. `GitLabClientImpl.postDiscussion` (`src/main/java/com/review/gateway/service/GitLabClientImpl.java:63-84`) posts a **plain top-level MR note** — `DiscussionRequest` has a single `body` field and no `position` object, so there is no diff thread for a suggestion to attach to.
  2. `CreateReviewRequest` has no `startSha`; GitLab's `position[start_sha]`/`position[head_sha]`/`position[base_sha]`/`position[new_path]`/`position[new_line]` are all mandatory for a positioned discussion.
  3. `CommentParser.sanitize` (`:195-215`) would **corrupt any code it touches**: `stripQuickActionLines` (`:254-266`) removes every line whose trimmed form starts with `/` — which is every `//` comment and every `/* */` opener — and `HtmlUtils.htmlEscape` turns `"` into `&quot;`, which inside a Markdown code fence is **not** decoded and therefore renders literally. A whole new sanitization path is required.
  4. "Apply suggestion" performs a **real commit on the MR's branch**. A sanitization defect in (3) therefore becomes a write into a real repository — a materially different risk class from "a comment renders oddly", requiring its own threat model.
  5. `GitLabPublisher.publishReview` (`src/main/java/com/review/gateway/service/GitLabPublisher.java:72-80`) treats **every** `GitLabPublishException` as transient and returns `PARTIAL`, which `PublishRetryService` retries; `GitLabClientImpl.postDiscussion:75` throws that exception for **any** `RestClientException`, including a permanent `400`. A malformed `position` would therefore retry **forever**. That bug must be fixed *before* (ii), not alongside it.
- Doing (i) now costs one optional string in a schema we are building anyway and one renderer; doing (ii) now would roughly double this feature's surface and add an authenticated write-to-branch path to its threat model. Deferring it does not create rework: (ii) consumes the exact same `suggestion` field this feature defines, and inherits a *validated, shape-guaranteed* upstream — which is precisely the prerequisite that makes (ii) safe to attempt at all.

**Recorded for the future feature's scoping:** fix `GitLabPublisher`'s transient-vs-permanent classification first; add `position[*]` to `GitLabClient`; add `startSha` to `CreateReviewRequest`/`review_inputs`; add a `sanitizeSuggestionForApply` path distinct from both the prose pipeline and `sanitizeCodeBlock`.

### 6.2 The fixed comment template

| Id | Requirement |
|---|---|
| **SRO-50** | New `CommentRenderer` (Gateway) assembles the published body from a **Gateway-owned constant template**, used only for structured (v3) Reviews. v1/v2 Reviews keep today's body (the sanitized comment text alone), byte-for-byte. Layout: <br>1. header line: `**MAJOR** — \`src/A.java\`:42` (Gateway constants + sanitized path + normalized line); <br>2. the prose comment; <br>3. optional ` ```diff ` context block; <br>4. optional `Suggested fix:` + plain fenced block. |
| **SRO-51** | The **diff-context block** is extracted by a pure function over `review_chunks.diff` (already stored, same Review, read inside the existing phase-1 transaction): locate the file's `diff --git` section, walk `@@ -a,b +c,d @@` hunks tracking new-file line numbers, emit ±`gateway.structured.diff-context-lines` (default `3`) lines **with their original `+`/`-`/space prefixes**. If the line cannot be located (line is `null`/0, or outside any hunk), the block is **omitted** — DEBUG log, counter, never an error. Toggle: `gateway.structured.include-diff-context` (default `true`). No new data, no GitLab call, no cross-Review data (the source is strictly this Review's own chunk — a hard invariant for appsec). |
| **SRO-52** | The **suggestion block** MUST NOT use the fence language `suggestion`. It is a plain fence. Reason: `\`\`\`suggestion` is GitLab's native apply-syntax; emitting it from a *non-positioned* note is at best inert and at worst an accidental partial activation of the deferred feature (ii) with none of its safeguards. This is a hard rule, not a style preference. |
| **SRO-53** | Truncation to `gateway.publish.max-comment-length` (4000) MUST be done by **dropping whole blocks in a defined order** — diff context first, then the suggestion block — and only then truncating the *prose* mid-string. Never a naive `substring` of the assembled body: that can cut inside a fence and leave it unterminated, at which point GitLab renders the remainder of the comment as code. (Same class of "the cap must be applied to the thing that actually ships" defect as F02-08, applied one layer up.) |

### 6.3 Sanitization: prose and code are different problems

| Id | Requirement |
|---|---|
| **SRO-54** | The **prose** part (`comment`, `summary`) goes through today's pipeline unchanged: `stripQuickActionLines` → `neutralizeMentions` → `htmlEscape` → cap. No regression to SR-08/SR-09/F02-08. |
| **SRO-55** | The **code** parts (`suggestion`, diff-context) go through a new `sanitizeCodeBlock`, which MUST NOT html-escape (entities are not decoded inside a CommonMark fence, so escaping *corrupts* and protects nothing there) and MUST: <br>• strip Cc except `\n`/`\t`, plus all Cf (bidi/Trojan-Source), Zl, Zp — reuse `TextSanitizer`, do not write a second implementation of the F-DC-02 character-class lesson; <br>• **collapse every run of 3+ backticks** so the content cannot break out of its fence, and emit the fence itself with **four** backticks (defense in depth); <br>• cap length. |
| **SRO-56** | **Quick actions inside a fenced block — fail closed pending verification.** GitLab extracts quick actions from the raw note text before rendering; whether its extractor is code-block-aware is a GitLab-version-dependent behavior this repository must not assume. Until QA verifies it empirically against the target GitLab, `sanitizeCodeBlock` MUST strip lines matching `^\s*/(?![/*])` — i.e. a leading `/` **not** followed by `/` or `*`. This preserves `//` and `/* */` (the overwhelming majority of real code lines beginning with `/`, which today's blanket `stripQuickActionLines` destroys) while still removing `/close`, `/assign`, `/merge`. QA item T-4.5 determines whether the rule can be dropped entirely; if it can, that is a follow-up one-liner, not a redesign. |
| **SRO-57** | `@mentions` inside code blocks are **not** neutralized (neutralization inserts a zero-width space, which corrupts code and would be copied into a real file by a future "apply"). The protection relied upon is fence integrity (SRO-55). This trade-off is explicitly flagged to appsec (§13). |

---

## 7. Data model — migration `V5__structured_review_output.sql`

```sql
-- SRO-05: per-backend structured-output wire shape. NULL = use gateway.structured.default-mode.
ALTER TABLE backends
    ADD COLUMN structured_output_mode VARCHAR(32);

ALTER TABLE backends
    ADD CONSTRAINT ck_backends_structured_output_mode
    CHECK (structured_output_mode IS NULL OR structured_output_mode IN
        ('OFF', 'RESPONSE_FORMAT_JSON_SCHEMA', 'RESPONSE_FORMAT_SCHEMA', 'TOP_LEVEL_JSON_SCHEMA'));

-- SRO-43: llama-server's finish_reason for this chunk's completion. NULL = not reported
-- (old Worker, or a backend that omits it).
ALTER TABLE review_results
    ADD COLUMN finish_reason VARCHAR(32);
```

- All columns are **nullable and additive**, no backfill, no change to any existing constraint. Like V4 and unlike V2, this migration is **rollback-tolerant**: an older JAR ignores both columns and degrades to today's behavior. State that explicitly in `DEPLOYMENT.md`.
- Ordinary transactional DDL inside Flyway's single migration transaction — consistent with V1–V4, no `CREATE INDEX CONCURRENTLY`.
- **No change to `review_events`** (no new `EventType`, `ck_event_type` untouched — SRO-40), **no change to `review_jobs`**, **no change to `review_chunks`** (`file_paths` already holds everything the schema needs), **no new table**. `DEPLOYMENT.md`'s GRANT block therefore needs no change — the app role already has `UPDATE` on `backends` and `INSERT` on `review_results`.
- The `CHECK` constraint is a guard rail, not the parser: `Backend.structuredOutputMode` is a plain `String` parsed by `StructuredOutputMode.fromNullable` (SRO-05), so a stale/hand-edited row degrades to `OFF` with a WARN rather than taking the claim path down.

---

## 8. Configuration

### Gateway (`src/main/resources/application.yml`, `GatewayProperties`)

| Key | Default | Purpose |
|---|---|---|
| `gateway.structured.enabled` | `true` | SRO-39. Global kill switch: `false` ⇒ no constraint emitted **and** legacy tolerant parsing for v3. |
| `gateway.structured.default-mode` | `OFF` | SRO-07. Used when `backends.structured_output_mode IS NULL`. |
| `gateway.structured.max-files-per-chunk` | `40` | SRO-14. Bounds schema size and per-response length; applied only for structured prompt versions. |
| `gateway.structured.max-schema-bytes` | `65536` | SRO-15. Backstop for the fully-inlined schema. |
| `gateway.structured.max-findings-per-file` | `20` | SRO-27 → `maxItems`. |
| `gateway.structured.max-comment-chars` | `1200` | SRO-27 → finding `comment` `maxLength`. |
| `gateway.structured.max-suggestion-chars` | `2000` | SRO-27 → finding `suggestion` `maxLength`. |
| `gateway.structured.per-file-summary` | `true` | SRO-25. Token-budget lever. |
| `gateway.structured.on-invalid-response` | `RETRY_THEN_FAIL` | SRO-38. Alternative: `RETRY_THEN_FALLBACK`. |
| `gateway.structured.include-diff-context` | `true` | SRO-51. |
| `gateway.structured.diff-context-lines` | `3` | SRO-51. |
| `gateway.structured.answer-reserve` | `8000` | **§10.** Replaces `gateway.diff.answer-reserve` (4000) in `DiffSizeValidator.budgetTokens` **for structured prompt versions only**, so the larger guaranteed-shape response still fits the context window. |

Startup validation (same `@PostConstruct` pattern as SR-15/PMR/WOC): `max-files-per-chunk >= 1`; `structured.answer-reserve >= diff.answer-reserve` (a smaller value is always a misconfiguration) and `context-window - prompt-reserve - structured.answer-reserve >= 1000` tokens of diff budget left, else fail startup with a message naming the property.

### Worker (`worker/src/main/resources/application.yml`, `WorkerProperties`)

| Key | Default | Purpose |
|---|---|---|
| `worker.limits.max-constraint-bytes` | `65536` | SRO-13 sibling of `max-diff-bytes` (WSR-03): the Gateway-supplied `responseFormat`/`jsonSchema` text is bounded **independently** of whatever the Gateway enforces. Exceeded ⇒ `AbandonJobException(CONSTRAINT_INVALID)` before any llama call. |

No other Worker config changes. `llama.max-tokens` stays `4096` as the *global* default; **v3.yml overrides it to `8192` per-template** (SRO-60), which is the mechanism `PromptTemplateService.resolve:110-111` already supports.

### Cross-module coupling (must be documented, cannot be validated automatically)

`gateway.structured.answer-reserve` (Gateway) and `v3.yml`'s `maxTokens` (Worker) describe the same quantity from two processes. `DEPLOYMENT.md` MUST state that changing one requires changing the other, with the same emphasis the `requeue-delay` ↔ `failure-grace` coupling gets today. A mismatch is silent: too-small a Gateway reserve overflows the context window; too-small a Worker `maxTokens` truncates — which, thanks to SRO-42/43, at least now reports itself as `TRUNCATED` rather than as mystery garbage.

---

## 9. Prompt versioning, and the `v3` template

### 9.1 Decision on question 2: a new `v3`, with `v1`/`v2` untouched

| Id | Requirement |
|---|---|
| **SRO-60** | New file `worker/src/main/resources/prompts/v3.yml`. It MUST contain `{{CHUNK_CONTEXT}}` (CSR-12) and `{{DIFF}}`, and sets `maxTokens: 8192`. `v1.yml` and `v2.yml` are **not modified** — the README's byte-for-byte guarantee for existing versions holds, and a Review already in flight on v1/v2 (its `prompt_version` is immutable in `reviews`/`review_inputs`) is completely unaffected by this branch. |
| **SRO-61** | `ReviewService.CHUNK_AWARE_PROMPT_VERSIONS` becomes `Set.of("v2", "v3")` (v3 is chunk-context-aware). A **new** Gateway-side constant `STRUCTURED_OUTPUT_PROMPT_VERSIONS = Set.of("v3")` is the single switch that (a) makes `DiffChunker` apply `max-files-per-chunk`, (b) triggers SRO-16/17's edge validation, (c) makes `QueueManager.claimJobRow` build a constraint, (d) selects `StructuredResponseParser` over `CommentParser`, (e) selects `gateway.structured.answer-reserve`. **No new column is needed** for this: `reviews.prompt_version` already persists it immutably, so the decision is stable and reconstructible for the whole life of a Review, including across Gateway restarts and config changes. |
| **SRO-62** | The v3 template's field-semantics text MUST live in the **`user:` block, not `system:`**. Under Prompt Manager V3 with `prompt_bundle_mode=REPO`, `PromptTemplateService.buildMessages:211-219` ignores `template.system()` entirely and uses the Gateway-assembled `systemMessages` instead — so anything essential placed in `system:` silently disappears for exactly the Reviews the Prompt Manager was built for. (`{{CHUNK_CONTEXT}}` is already `user:`-only for a related reason — see `v2.yml:11-13`.) |
| **SRO-63** | v3's `user:` block still describes the response shape in prose — **about 80 tokens, and deliberately so.** This is not a contradiction of priority 1: priority 1 says the model must not have to *get the format right*, and it no longer does (the grammar decides). The prose exists because (a) the `OFF`/kill-switch/rollout stages run the same template *without* a constraint and must still work, (b) prompt + constraint agreeing is strictly better sampling than a constraint fighting a silent prompt, and (c) the sentinel conventions (`line: 0`, `suggestion: ""`) are **content semantics**, which no grammar can convey. What v3 removes is the *pleading* — "ONLY a JSON array, no markdown fences, no prose before or after" — because that is now structurally true rather than requested. |

### 9.2 Sketch of `v3.yml` (final wording is the developer's, these are the binding constraints)

```yaml
# Prompt template v3 -- structured review output. Used together with a Gateway-computed JSON Schema
# that is enforced at the decoder (see docs/structured-review-output-architecture.md). v1.yml/v2.yml
# are untouched; Reviews on those versions behave exactly as before.
maxTokens: 8192
system: >
  <same reviewer persona as v2, MINUS the "you always respond with valid JSON and nothing else"
  sentence -- that is now structural. Ignored entirely when Prompt Manager is in REPO mode.>
user: |
  {{CHUNK_CONTEXT}}

  Review the unified diff below. Report concrete, actionable issues: correctness bugs, security
  vulnerabilities, missing error handling, significant maintainability problems. Ignore stylistic
  nitpicks unless they materially affect correctness.

  Your answer is a JSON object with one entry under "files" for every file listed above, plus a
  short overall "summary". For each file: "findings" (possibly empty) and a one-sentence "summary".
  For each finding: "line" (line number in the NEW version of the file; use 0 for a file-level
  issue), "severity" (critical/major/minor/info), "comment" (what is wrong and why), and
  "suggestion" (replacement code for that line, or "" if you have none).

  Diff:
  {{DIFF}}
```

### 9.3 Rejected alternatives, with an audit trail

**9.3.1 — GBNF `grammar` instead of JSON Schema.** Rejected. (a) llama-server converts `json_schema` → GBNF internally with the same engine we would be reimplementing; writing a second generator in the Gateway is duplicated, hand-reviewed, hard-to-test work. (b) It is mutually exclusive with `json_schema` and more likely to collide with a `--jinja` chat template's own grammar. (c) **Decisive:** a GBNF string cannot be used to *validate* a received response, whereas one JSON Schema serves both purposes — constrain (via llama-server) **and** verify (SRO-30). Given SRO-04 (never trust the constraint), the verification role is the one we cannot give up. If a pinned build turns out to honor only `grammar`, the correct answer is `structured_output_mode = OFF` on that backend — validation-only, still a strict improvement over today — not a second artifact.

**9.3.2 — Array of findings with `file` constrained to an `enum` of the chunk's paths.** Rejected as the coverage mechanism (SRO-20): `uniqueItems` is not expressible in a grammar, so this guarantees *membership* but not *coverage*, leaving symptom 1 open. It is also strictly more tokens for a clean chunk than the keyed object.

**9.3.3 — A `"reviewed": true` boolean per file.** Rejected. It is a control flag the model owns, i.e. exactly the bookkeeping priority 1 wants removed, and `"reviewed": false` would hand the model a sanctioned way to skip a file — reintroducing symptom 1 through the front door. The *presence of the required key* is the coverage proof; a boolean adds nothing but an escape hatch.

**9.3.4 — Optional properties (`suggestion` present only when there is one).** Rejected (SRO-21). The converter places optional properties behind an alternation after the required ones; every alternation is a decision point. `""` is one token and no decision.

**9.3.5 — Persisting the generated schema per job.** Rejected (SRO-19): it is reconstructible from data we already store, which is precisely the CLAUDE.md test for "don't cache it". Contrast `review_prompt_sections`, which *is* persisted because a Git ref moves.

**9.3.6 — A `Map<String,Object>` "llama request overlay" the Worker merges into its request body.** Maximally dumb-conduit, and rejected on security grounds: it would let the Gateway (or anyone who could influence it) set arbitrary sampling parameters — `n_predict`, `cache_prompt`, and whatever a future llama.cpp adds — on a Worker that is supposed to own its own limits. Two typed, allow-listed fields with fixed wire destinations (SRO-10) give the same verbatim-forwarding property with none of that.

**9.3.7 — Making the Worker choose the wire shape from its own config.** Rejected in favor of `backends.structured_output_mode` (SRO-05). The project already places per-backend LLM quirks in the Gateway's `backends` table (`prompt_message_format`, PMR-22), and doing it there keeps the Worker's rule absolute — *attach what you are handed, decide nothing* — which is the load-bearing constraint for this whole design.

**9.3.8 — Terminal `FAILED` on a non-conforming response (no retry).** Rejected as the default (SRO-35): it converts one bad sample into a dead Review, and the attempts counter already exists to distinguish "unlucky" from "misconfigured". Terminal failure *after* the attempt budget is exhausted is retained, and is the default end state (SRO-38).

**9.3.9 — Keeping today's single-comment fallback for v3.** Rejected as the default (SRO-38), retained as a configurable escape hatch. Publishing a comment that contains a raw model transcript is the exact behavior that made both symptoms invisible for as long as they have been.

---

## 10. Token budget, response length, and truncation (answers question 6)

**Is the "explicit entry for every clean file" cost acceptable?** Yes, and it is the intended cost. Quantified with the defaults above: a clean file entry is `"src/A.java":{"findings":[],"summary":"…"}` ≈ 25–40 tokens including the path. At `max-files-per-chunk = 40` that is ~1.6k tokens of pure coverage scaffolding in the worst case, against a `gateway.structured.answer-reserve` of 8000. Priority 2 ("must not let a file go unreviewed without that being visible") is explicitly ranked above token efficiency, and the cheaper alternatives all buy their savings by making omission representable again (§9.3.1–9.3.3). `gateway.structured.per-file-summary=false` is the provided lever for an operator who needs the tokens back and will accept the weaker attention guarantee.

**Does constrained decoding change the truncation risk profile?** Yes — in both directions, and the net is strongly positive:

- **Risk up:** the guaranteed shape is more verbose than a bare JSON array, so a given review is closer to `max_tokens`. Mitigated structurally by SRO-27 (`maxItems`/`maxLength` make the worst case *computable*, not open-ended), by SRO-14 (bounded file count per chunk), and by the v3 template's own `maxTokens: 8192`.
- **Risk down, materially:** a grammar applied from the first token makes a `<think>…</think>` preamble **unrepresentable**. That is precisely the failure the previous feature's operational note called out (`docs/worker-observability-and-claim-latency-architecture.md` §9: *"An empty `content` after a 122–210s generation is the signature of the completion budget being consumed by `<think>…</think>`"*). Structured output removes that failure mode by construction on any backend that honors the constraint.
- **Risk becomes visible:** today a truncated response is silently absorbed into a one-comment fallback. After SRO-42/43 it is classified `TRUNCATED`, retried, counted, and — if persistent — surfaced as `last_error = "structured-output: TRUNCATED (finish_reason=length)"`, whose operator action ("raise `LLAMA_MAX_TOKENS` / lower `max-files-per-chunk`") is unambiguous.
- **Interaction with the existing caps:** `worker.limits.max-response-bytes` (200000) and `gateway.publish.max-raw-response-length` (200000) are both far above any schema-bounded response at the default `max-files-per-chunk`/`max-findings-per-file`. If an operator raises those knobs far enough to approach the caps, `capRawResponseIfNeeded` truncation is itself now a classified `TRUNCATED` input to SRO-32 rather than a silent mangling. Note for the developer: `ResultProcessor.capRawResponseIfNeeded` runs **before** parsing today (`ResultProcessor.java:83`) and must keep doing so — `StructuredResponseParser` must be told *that* truncation happened, not left to infer it from a syntax error.
- **A quality risk worth measuring, not a correctness one:** on some builds a grammar suppresses a reasoning model's thinking entirely, which may reduce finding quality even as it improves conformance. This is exactly what the staged rollout in §11 is designed to detect (compare findings-per-review and reviewer-accepted-comment rate between stages), and it is the one outcome that could justify keeping a backend on `OFF`.

---

## 11. Rollout and validation plan (answers question 5)

Opting in is per-Review, at `POST /reviews`, via the existing `promptVersion` field — the same mechanism versioning already uses. Nothing about a Review in flight changes: its `prompt_version` was persisted at creation and every downstream decision (SRO-61) reads that immutable value.

| Stage | Change | What it measures | Rollback |
|---|---|---|---|
| **0 — baseline** | Deploy this branch. Every backend is `OFF`, all CI still sends `promptVersion: v2`. | `legacyParseFallback` (SRO-45) on real v2 traffic = today's format-compliance failure rate. `averageFileCoverageRatio` (SRO-46) = today's coverage proxy. **This is the "before" number, and it costs nothing.** | Redeploy previous JAR; V5 is rollback-tolerant. |
| **1 — schema validation only** | One pilot project's CI sends `promptVersion: v3`. Backends stay `OFF`. | How often the model conforms **without** decoder help. Failures are retried and counted per kind, so `NOT_JSON` vs `COVERAGE_SHORTFALL` is visible for the first time. | Switch that project's CI back to `v2`, or `gateway.structured.enabled=false`. |
| **2 — canary constraint** | Set `structured_output_mode = 'RESPONSE_FORMAT_JSON_SCHEMA'` on **one** backend (a single `UPDATE`, no restart). | Whether that llama-server build honors the constraint at all (`structuredValidationFailures` should collapse toward zero; if it does not move, the build is ignoring it or failing open — SRO-08's `curl` recipe plus the server log then tell you which). Compare findings-per-review against stage 1 for the §10 quality risk. | `UPDATE backends SET structured_output_mode='OFF'`. |
| **3 — fleet** | All backends on the mode that worked. | Steady-state failure rate; decide whether `on-invalid-response` can stay `RETRY_THEN_FAIL`. | Per-backend `UPDATE`. |
| **4 — default** | CI templates switch to `promptVersion: v3`. `README.md` documents v3 as recommended; v1/v2 remain supported indefinitely. | — | Revert the CI variable. |

**Every stage is a data change or a CI variable, never a redeploy.** That is the point of putting the mode in `backends` (SRO-05) and the opt-in in `promptVersion` (SRO-61).

---

## 12. Test guidance for `qa-engineer`

Environment note (unchanged): **no Docker on this machine** — use Zonky embedded-postgres for anything touching Flyway/`FOR UPDATE`/claim ordering, `okhttp3:mockwebserver` for Worker↔Gateway and Worker↔llama, plain mocks elsewhere.

**Part 1 — schema construction (`ReviewSchemaBuilder`, pure).** **T-1.1** N paths ⇒ `files.required` and `files.properties` key sets both equal the path set, `additionalProperties:false` at all three levels. **T-1.2** paths containing `"`, `\`, spaces, non-ASCII are correctly JSON-escaped and round-trip through `readTree`. **T-1.3** every property is `required` at every level (SRO-21) — a regression guard, since "just make it optional" is the tempting wrong fix. **T-1.4** `max-findings-per-file`/`max-*-chars` land as `maxItems`/`maxLength`. **T-1.5** output is byte-deterministic for the same inputs (SRO-18). **T-1.6** declaration order is `files` → `summary` and `findings` → `summary` (SRO-03). **T-1.7** no `$ref`/`$defs`/`oneOf`/`anyOf`/`allOf` anywhere in the output (SRO-02). **T-1.8** `max-schema-bytes` overflow is refused, not emitted.

**Part 2 — claim path.** **T-2.1** v2 Review ⇒ both new payload fields `null` and the claim response is byte-identical to pre-branch. **T-2.2** v3 + backend `OFF` ⇒ both `null`. **T-2.3** each of the three non-`OFF` modes produces the documented wire shape, and never both fields. **T-2.4** an unknown/garbage `backends.structured_output_mode` degrades to `OFF` + WARN, never an exception on the claim path (`Backend.promptMessageFormat` precedent). **T-2.5** the schema's key set equals `review_chunks.file_paths` for the *claimed chunk only*, never a sibling chunk's files. **T-2.6** `JobPayload`/`ClaimedJob` `toString()` never renders schema content (CSR-14/PMR-25 regression).

**Part 3 — Worker.** **T-3.1** the constraint text is attached **verbatim**, byte-for-byte, to the outgoing chat-completions body (assert against a `mockwebserver` recorded request). **T-3.2** both fields non-null ⇒ `AbandonJobException(CONSTRAINT_INVALID)` **before** any llama call, and a best-effort `POST /jobs/{id}/fail` is sent. **T-3.3** oversized constraint (`max-constraint-bytes`) ⇒ same. **T-3.4** non-JSON / non-object constraint ⇒ same. **T-3.5** old-Gateway payload (fields absent) ⇒ request body byte-identical to pre-branch (`@JsonIgnoreProperties` forward-compat). **T-3.6** no log line ever contains schema, diff, prompt, response content, or the bearer token (WSR-10 regression). **T-3.7** `finish_reason` is propagated end-to-end into `review_results.finish_reason`; a llama response omitting it yields `null`, never an exception.

**Part 4 — parsing and retry.** **T-4.1** a conforming response ⇒ one `ParsedComment` per finding, correct `filePath`/`line`/`severity`; empty-`findings` files produce none. **T-4.2** each of `NOT_JSON`/`SCHEMA_MISMATCH`/`COVERAGE_SHORTFALL`/`TRUNCATED` ⇒ **job requeued** (not `FAILED`), a `RETRY` event whose `details` starts with the Gateway-constant `structured-output:` prefix, `last_error` set and sanitized, `not_before` honored. **T-4.3** the same failure at `attempts == max` ⇒ `FAILED` + the existing sibling-cancellation cascade, unchanged. **T-4.4** a response conforming to the *schema* but naming a file from a **different** chunk ⇒ `COVERAGE_SHORTFALL`, and no comment is ever attributed to a file outside this chunk. **T-4.5** *(GitLab behavior probe, informs SRO-56)* post a note containing `/close` inside a fenced code block against the target GitLab and record whether the quick action fires. **T-4.6** `on-invalid-response=RETRY_THEN_FALLBACK` ⇒ after the budget is exhausted, legacy parsing produces a comment, a WARN is logged, and the fallback counter increments. **T-4.7** `gateway.structured.enabled=false` ⇒ a v3 Review behaves exactly like v2 end-to-end. **T-4.8** **lock-ordering regression (SRO-36):** a validation failure must not deadlock or lock-timeout — assert `RetryManager` is invoked only after phase 1 has committed, and that a concurrent stale-heartbeat sweep on the same job produces exactly one `RETRY`. **T-4.9** raw response is persisted **before** validation, and only the first attempt's is kept (SRO-37).

**Part 5 — rendering and sanitization.** **T-5.1** the rendered body has the fixed template shape for every finding. **T-5.2** a suggestion containing ``` cannot break out of its fence; a suggestion containing `//`, `/* */`, `@Override`, `"`, `<script>` survives **intact** inside the fence (the F02-04/F02-08-adjacent regression this feature must not reintroduce). **T-5.3** the fence language is never `suggestion` (SRO-52). **T-5.4** diff-context extraction finds the right hunk for a line, and omits the block cleanly for `line: 0`, an unlocatable line, or a missing file section. **T-5.5** `max-comment-length` overflow drops whole blocks in the SRO-53 order and never leaves an unterminated fence. **T-5.6** the prose part still gets today's full pipeline (quick-action strip, mention neutralization, HTML escape) — SR-08/SR-09 regression.

**Part 6 — chunking and edge validation.** **T-6.1** with `max-files-per-chunk` unset (v1/v2), `DiffChunker` output is byte-identical to pre-branch for a corpus of real diffs (the §8 backward-compat guarantee). **T-6.2** with it set, no chunk exceeds it, including via the single-chunk shortcut and via `splitOversizedSection`. **T-6.3** overflow past `max-chunks` still raises `DIFF_TOO_LARGE` (no new error path). **T-6.4** a non-git-style diff + `promptVersion: v3` ⇒ `422 STRUCTURED_OUTPUT_UNSUPPORTED`; the same diff + `v2` ⇒ accepted, unchanged. **T-6.5** a path that sanitizes to `null` + `v3` ⇒ same 422 (SRO-17).

**Part 7 — metrics/migration.** **T-7.1** `legacyParseFallback` increments on a v2 non-JSON response (the baseline instrument must actually work before v3 exists). **T-7.2** each validation-failure kind increments its own counter and `GET /metrics` exposes them. **T-7.3** V5 applies cleanly on a V4 database, and an older JAR still boots against a V5 database (rollback tolerance).

---

## 13. Hand-off to `appsec-engineer`

Target: `docs/structured-review-output-threat-model.md` (this feature is substantial enough for its own file, like `docs/prompt-manager-threat-model.md`). Focus areas:

1. **MR-author-controlled file paths become grammar literals.** The chunk's paths flow `diff` → `DiffChunker` → `sanitizePath` → `review_chunks.file_paths` → JSON Schema → llama-server's GBNF converter → sampler. Assess: injection into the schema (JSON escaping), injection into the *generated grammar* (a path that produces a pathological or malformed GBNF rule — note the fail-open bug means a malformed grammar yields an *unconstrained* generation with HTTP 200, i.e. a silent security-control bypass reachable by naming a file), and grammar-size amplification (bounded by SRO-14/SRO-15 — please confirm the bound is tight enough).
2. **The constraint is a security control that can silently fail open** (§3.1). Confirm that SRO-04's "validation is unconditional and never trusts the constraint" is actually load-bearing everywhere it needs to be, and that no code path shortcuts validation because "the grammar guaranteed it".
3. **New Gateway→Worker data flow.** The Worker now accepts a Gateway-supplied blob that it injects into an outbound request. Assess the defensive bounds (SRO-13, `max-constraint-bytes`), whether "must be a JSON object" is a sufficient shape check, and whether a compromised/buggy Gateway could use these fields to influence anything besides output shape.
4. **Code content published to GitLab without HTML escaping** (SRO-55/SRO-57). This is a deliberate departure from `CommentParser.sanitize`'s pipeline and needs an explicit sign-off: fence integrity (backtick-run collapsing + 4-backtick fences) is the primary control; mention neutralization is deliberately *not* applied inside code. Please confirm or constrain. Related: SRO-56's fail-closed quick-action rule pending T-4.5.
5. **Diff content republished into MR comments** (SRO-51). Confirm the "same Review's own chunk only" invariant is structurally enforced and cannot be crossed by a `chunkIndex`/`reviewId` mix-up, and that the diff-context block cannot leak content from a file outside the finding's own file section.
6. **Model-controlled JSON keys.** `files`' keys come from the model. Even though they are validated against a known set, note the parsing order: rejection happens *after* `readTree` has already materialized attacker-shaped keys. Assess JSON-bomb/deep-nesting exposure (bounded by `max-raw-response-length` = 200000 and by Jackson's defaults) and whether an explicit `StreamReadConstraints` is warranted.
7. **Failure-path amplification.** Validation failures now consume retry attempts and therefore backend time. Assess whether a prompt-injected diff could deliberately drive `COVERAGE_SHORTFALL` on every attempt to burn a fleet's capacity (mitigated by `max-attempts` = 3 and `not_before`, but worth stating).
8. **`last_error`/`review_events.details` now carry model-influenced file paths** (SRO-41). Confirm `TextSanitizer.sanitizeSingleLine` + the 512 cap + the Gateway-constant prefix ordering (WOT-12) still make the origin discriminator unforgeable when the injected text is a *file path* rather than a Worker `detail`.
9. **`RETRY_THEN_FALLBACK`** (SRO-38): it reintroduces today's raw-transcript-as-comment path on a code path an attacker can force. Confirm the existing SR-08/SR-09 sanitation is sufficient there and that the default (`RETRY_THEN_FAIL`) is the right one.
10. **Migration safety** (§7): additive nullable/CHECK-constrained columns, rollback tolerance, and whether the `backends` CHECK plus the parse-don't-`valueOf` rule (SRO-05) is the right belt-and-braces split.

---

## 14. Documentation to update during implementation

- `README.md` — new `promptVersion` `v3` and what it guarantees; the two new `JobPayload` fields in §6.4/§9; `finishReason` on `POST /jobs/{id}/result`; the new `gateway.structured.*` config block; the new `422 STRUCTURED_OUTPUT_UNSUPPORTED` error code; the new `GET /metrics` fields; an explicit statement that the *shape* of a v3 comment body is Gateway-owned and fixed.
- `DEPLOYMENT.md` — V5 migration note and rollback tolerance; the `backends.structured_output_mode` `UPDATE` recipe; **the llama-server capability-verification `curl` recipe and the "no version is pinned; structured output is a per-backend capability" statement (SRO-08)**; the `gateway.structured.answer-reserve` ↔ v3 `maxTokens` cross-module coupling; the staged rollout ladder from §11.
- `worker/README.md` — §2 flow diagram (the constraint field on the llama call), §5.2 `worker.limits.max-constraint-bytes`, §8.2 the new `CONSTRAINT_INVALID` abandonment reason, and **§10 step 4**, which currently instructs template authors that the `user` text must make the model emit the right shape *because the Worker forwards output verbatim* — that guidance now has a second, stronger mechanism and must say so.
- `CLAUDE.md` — the Data-model bullet list (`backends.structured_output_mode`, `review_results.finish_reason`) and a one-line note that v3 Reviews are decoder-constrained and strictly parsed.

---

Next: appsec threat-model round.

**Sources for the llama.cpp behavior cited in §3.1:**
- [llama.cpp server README (`tools/server/README.md`)](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [Issue #19051 — llama-server fails open when JSON schema grammar parsing fails](https://github.com/ggml-org/llama.cpp/issues/19051)
- [Issue #11847 — `response_format` on the OpenAI-compatible `/v1/chat/completions`](https://github.com/ggml-org/llama.cpp/issues/11847)
- [Issue #11988 — `json_schema` under `response_format` not working on `/v1/chat/completions`](https://github.com/ggml-org/llama.cpp/issues/11988)
- [`common/json-schema-to-grammar.cpp` — supported JSON Schema keywords and required-property ordering](https://github.com/ggml-org/llama.cpp/blob/master/common/json-schema-to-grammar.cpp)
