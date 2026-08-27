# Structured Output Grammar Budget — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**, **sibling document** (the `docs/prompt-manager-threat-model.md`
precedent), not an amendment section appended to `docs/structured-review-output-threat-model.md` (the
`docs/worker-threat-model.md` precedent). Reason for that choice, since the architecture doc left it
open: the delta is **not** small in kind, only in diff size. It (a) creates a *new text-mutation point*
on the publish path (`SGB-03`/`SGB-04` truncation) that changes a control's outcome from reject to
mutate-and-ship, (b) opens a *new trust boundary* — the Worker ingesting a backend-controlled **error
body**, on a code path that today reads zero bytes of it — and (c) carries its own empirical release
gate (§6/P-3) that must be citable on its own. An amendment section could not carry a release gate of
its own without confusing the SRO gate that already passed.

Prefix: threats **`SOGT-nn`**, requirements **`SOGB-nn`** — chosen to sit alongside the architecture
doc's `SGB-nn` and the SAST round's already-reserved `F-SOGB-`, and to mirror the `SOT-nn`/`SOR-nn`
split of the parent model. `SGB-nn`, `SRO-nn`, `SOR-nn`, `SOT-nn`, `WOR-nn`, `WOC-nn` are referenced,
never renumbered.

It **extends** `docs/structured-review-output-threat-model.md` (`SOT-01..24` / `SOR-01..23`) and
inherits `docs/threat-model.md`, `docs/worker-threat-model.md`,
`docs/worker-observability-and-claim-latency-threat-model.md` (`WOR-01..20`) unchanged. It rewrites
none of them. Methodology and gate vocabulary (BLOCKING / CRITICAL / TRACKED) are the parent model's.

---

## 0. Verdict up front

The direction — **delete `maxLength`, truncate on receipt** — is **endorsed**. It is a deletion, it
removes a deterministic whole-chunk failure that exists in production *today* on the shipped `OFF`
default, and it does not move a single decision out of the Gateway.

Three of the seven requirements are endorsed **only with additions**:

| Architect's row | Ruling |
|---|---|
| SGB-01 (drop `maxLength`) | **Accept.** Gated on P-3 evidence, not on the source reading (SOGT-07). |
| SGB-02 (keep `maxItems`, assert ≤ 200) | **Accept, amend** the assertion's wording (SOGT-09). |
| SGB-03 (reject → truncate) | **Accept, amend** — must carry a truncation flag to the renderer (SOGT-01, BLOCKING) and must be unconditional (SOGT-08). |
| SGB-04 (truncation ordering) | **Accept, amend** — code-point-boundary truncation is not optional (SOGT-02). |
| SGB-05 (`max-files-per-chunk` contingent) | **Accept as written.** |
| SGB-06 (`CONSTRAINT_REJECTED`) | **Accept, amend** — needs a byte bound *and a time bound* (SOGT-03, CRITICAL). |
| SGB-07 (production-shape recipe) | **Accept as written.** Endorsed strongly: it is the control that would have caught this incident pre-canary. |

Rejected-alternative **R-1 ($defs) is upheld** — see §4.1, with one factual correction already applied
to the architecture doc.

---

## 1. Decomposition — what actually changes

| Element | Change | Module |
|---|---|---|
| `ReviewSchemaBuilder.stringSchema` | Loses its `maxLength` emission and its parameter | Gateway |
| `GatewayProperties.validateStructuredOnStartup` | +1 assertion (`max-findings-per-file ≤ 200`) | Gateway |
| `StructuredResponseParser.validateFindingAndCollect:335-340` | reject → **truncate** (new text mutation) | Gateway |
| `StructuredResponseParser` → `CommentRenderer` hand-off | New "this value was truncated" signal (SOGB-02) | Gateway |
| `MetricsCounters` | +1 counter `structuredFieldTruncated` | Gateway |
| `LlamaClient.parseResponse` non-2xx branch | **New read of a backend-controlled error body** | Worker |
| `JobFailureReason` (both copies) | +`CONSTRAINT_REJECTED`; `WorkerLoop.DETAIL_BY_REASON` +1 constant | both |
| `DEPLOYMENT.md` SRO-08 recipe + committed schema fixtures | Doc/test | — |

### Trust-boundary delta on the parent model's §1

| # | Boundary | Trust posture |
|---|---|---|
| **SOGTB-TRUNC** | model-supplied `comment`/`suggestion` → **Gateway truncation** → `sanitizeCodeBlock` → fence assembly → GitLab note | **New.** The parent model's `SOTB-CODEBLOCK` assumed the text reaching `CommentRenderer` was *whatever the model wrote, in full*. It is now a Gateway-mutated prefix of it. Every property `SOR-09` proves about fence integrity must be re-proved against a prefix, and every property `SOR-22` proves about *provenance labelling* must survive the fact that the Gateway, not the sanitizer, did the cutting. |
| **SOGTB-ERRBODY** | llama-server **non-2xx response body** → `LlamaClient` → classification → `POST /jobs/{id}/fail` `reason` | **New.** Today `parseResponse:159-162` throws on a non-2xx *without touching the body*. This is the first time a backend's **error** bytes are read, buffered and scanned by the Worker, and it happens on the single worker-loop thread, **after** `awaitLlamaResponse`'s `requestTimeoutSec` has already been satisfied and after `AbortSignal` has lost its ability to interrupt (the future is already complete; `cancel(true)` is a no-op). |

`SOTB-KEY`, `SOTB-CONSTRAINT`, `SOTB-COVERAGE`, `SOTB-MODE` are unchanged. **`SOTB-KEY` gets
materially smaller**: after SGB-01 the schema hands llama.cpp's grammar compiler four fewer bounded
constructs per file and no numeric literals at all, which is the correct direction for a boundary whose
whole problem is that we cannot read the binary on the other side of it.

---

## 2. STRIDE threats — SOGT-01..SOGT-10

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | Gate |
|----|--------|-------------|-----------|----------|:---:|:---:|
| **SOGT-01** | Tampering (deception) | CWE-451 / LLM05 | `StructuredResponseParser` × `CommentRenderer.sanitizeCodeBlock` | **Truncation silently strips the "this is not a verbatim quotation" label.** `sanitizeCodeBlock` computes `SanitizedCode.altered` as `!capped.equals(raw)` where `raw` is *its own input*. Move the cut upstream into the parser and the renderer's `raw` **is already the truncated string** ⇒ `altered == false` ⇒ no `ALTERED_CODE_MARKER`. A suggestion cut at 2000 chars mid-statement is then published inside a fence with **no indication it is an excerpt** — which is precisely the state `SOR-22`/`SOT-23` exist to prevent, and the reason `SRO-52` forbids the `suggestion` fence language (an altered block must never be one click from a real commit). Worse than the pre-fix behavior in one specific way: today an over-long suggestion fails loudly; after SGB-03 it ships, truncated, looking authoritative. **Silent regression of a shipped, SAST-verified control (F-SRO-06).** | **Medium** | **BLOCKING** (New) |
| **SOGT-02** | Availability / Integrity | CWE-176, CWE-116 / A04 | truncation site (`substring`) | **A truncation can split a UTF-16 surrogate pair and produce a String that is not encodable.** Both existing cut helpers — `CommentRenderer.capLength:432-438` and `TextSanitizer.capLength:105-112` — are plain `substring(0, n)`. Under SGB-03 truncation stops being an exceptional path and becomes the *routine* handling of any long finding, so the boundary is hit constantly and the model emits emoji/CJK freely. An unpaired surrogate then travels into `review_comments.text` and into the GitLab note body; Jackson's UTF-8 generator rejects a split surrogate when serializing the note payload, and the pgjdbc encoder is likewise not obliged to accept it. Outcome: a `COMPLETED` Review whose publication throws on **every** publish-retry attempt — a stuck, self-repeating failure with no bad actor and no diagnostic pointing at truncation. The architect correctly *named* this in SGB-04; it is recorded here as a threat because "MUST NOT split a surrogate pair" needs to be a testable requirement with a named helper, not a sentence in a design doc. | **Medium** | **CRITICAL** (New/Amp) |
| **SOGT-03** | DoS | CWE-400, CWE-770, CWE-1088 / A04 | `LlamaClient.parseResponse` non-2xx branch (SGB-06) | **Reading the error body to classify it hands a backend a Worker-wedging primitive.** Three compounding facts, all verified in code. (a) `parseResponse:159-162` throws on non-2xx **before** touching the body — SGB-06 must therefore *add* a read where there is none. (b) The obvious implementation reuses `readBounded`, whose bound is `worker.limits.max-response-bytes` = **200 000** — a 200 KB buffer allocated to look for a 22-character token. (c) The far bigger problem is **time, not bytes**: this read runs on the worker-loop thread *after* `awaitLlamaResponse` already returned, so `worker.network.request-timeout-sec` no longer applies (the JDK `HttpRequest.timeout` bounds time-to-headers, not body), `AbortSignal.attach`'s `future.cancel(true)` is a no-op on an already-completed future, and `HeartbeatScheduler` is still running. A backend that sends `HTTP 500` headers and then stalls the body stalls that Worker **permanently**: heartbeats keep flowing until the Gateway's `gateway.job.max-duration` (45m) sweep requeues the job, the heartbeat then gets `continue:false`, the abort fires — and the thread is still blocked in `read()`. One backend, one stalled body, one Worker dead until process restart. Reachable by a compromised/buggy backend or a hung reverse proxy in front of it; not reachable by an MR author. | **Medium-High** | **CRITICAL** (New) |
| **SOGT-04** | Repudiation / Log injection | CWE-117, CWE-532 / A09 | `LlamaClient` → `FailRequest.detail` → `review_jobs.last_error` | **Backend-supplied error text must not become audit text.** The question the architect asked. The answer is that the existing design *already* closes it, and the only risk is a developer undoing it: `WorkerLoop.DETAIL_BY_REASON:57-68` (WOR-05) is a **Worker-side constant map**, one fixed sentence per `JobFailureReason`, and `reportFailureBestEffort:313-315` sends `DETAIL_BY_REASON.get(reason)` — never `e.getMessage()`, never a response body. On the Gateway side `QueueManager.reportFailure:613-621` whitelist-parses `reason` via `fromWireValue` and `sanitizeSingleLine(detail, 200)`s the detail regardless. So a compliant SGB-06 adds one enum constant and one constant sentence and **nothing backend-controlled moves**. The threat is the *tempting* implementation — "put the matched line in `detail` so the operator sees the real error" — which would put arbitrary backend bytes into `last_error`, `review_events.details` and a WARN log line in one step, and would defeat `WOR-04`'s positional discriminator by substring. | **Low** (as designed) | **CRITICAL** (New — must not regress) |
| **SOGT-05** | Tampering | CWE-807, CWE-350 / A08 | `CONSTRAINT_REJECTED` classification | **The classification is a backend-controlled assertion about the backend.** A backend can force `CONSTRAINT_REJECTED` by echoing `failed to parse grammar` in any 5xx body, and can suppress it by localizing/rewording its own error (in which case the report degrades to today's `LLM_ERROR` — fail-safe). Verified inert today: `QueueManager.reportFailure` passes only a **composed String** to `RetryManager.requeueOrFail(jobId, reason, workerId)`; `RetryManager` has no `JobFailureReason` parameter, no import, and no branch — the retry-vs-fail decision is `attempts` vs `maxAttempts` and nothing else. So the worst case is a mislabelled audit row. The residual is *future* coupling: the moment anyone writes an alert, a runbook automation, or a `last_error LIKE '%CONSTRAINT_REJECTED%'` demotion job, a backend gains the ability to trigger it. R-7 already rejected exactly this, correctly. | **Low** | **TRACKED** (New) |
| **SOGT-06** | DoS | CWE-770, CWE-405 / A04, LLM01 | decoder ← schema without `maxLength` | **Unbounded per-field generation: real, and not a new class.** With `maxLength` gone, a *working* constraint no longer stops a rambling `comment`/`suggestion`; the model runs to `v3.yml`'s `maxTokens: 12000`, returns `finish_reason: length`, and `StructuredResponseParser:181-187` classifies `TRUNCATED` **before** `readTree` — a retryable validation failure. Three attempts, then `FAILED`, then `ChunkCoordinator` cancels successful siblings. That is `SOR-INH-1` verbatim. Why it is nonetheless **not a regression**: (i) the shipped fleet default is `OFF`, where no constraint has ever bounded anything and this profile is already today's profile; (ii) the constrained profile it is compared against **has never once existed in production** — the grammar was rejected 100% of the time, which is the incident; (iii) the outer bounds are unchanged and all still hold — `maxTokens` 12000 (always sent), `worker.limits.max-response-bytes` 200000, `gateway.publish.max-raw-response-length` 200000, `StreamReadConstraints.maxStringLength`. The honest statement is therefore: **removing `maxLength` gives up a token-burn bound that was theoretical, in exchange for a decoder constraint that compiles at all.** Worth measuring, not worth blocking. | **Medium** | **TRACKED** (New) |
| **SOGT-07** | Tampering (fail-open) | CWE-636, CWE-1288 / A04 | SGB-01's `char*` claim | **The fix's central premise rests on the same kind of evidence that produced the incident.** `SOT-24`/§13.1 concluded "`maxLength` compiles to a compact `{m,n}`, so it does not amplify the grammar" by reading `common/json-schema-to-grammar.cpp` — true of the emitted GBNF text, false of the parsed grammar, and production refuted it. SGB-01's premise ("plain `{"type":"string"}` routes to the shared `string` primitive `"\"" char* "\""`; `*` costs zero repetition budget") is arrived at **the same way, from the same two files, by the same reasoning**. It is very probably right. It is not yet *evidence*, and this document's own §1.1 says so in as many words. Shipping it on the strength of the source reading — or, worse, flipping a backend off `OFF` on it — repeats the exact process failure. | **Medium** | **BLOCKING** (process) (New) |
| **SOGT-08** | Tampering (fail-open) | CWE-636, CWE-807 / A04 | `StructuredResponseParser` × `SOR-11` | **SGB-03's framing invites the one shortcut `SOR-11` forbids.** "Length moves from *enforced twice* to *enforced where it is actually enforceable*" is a sentence a developer can reasonably read as "the receipt-side length handling is a fallback for the unconstrained case" — and the natural next step is `if (backendWasConstrained) skip`, or keying it off `finish_reason`, or off `structuredConstraintSent`. Any of those reintroduces exactly the `SOTB-MODE`-dependent validation that `SOR-11`'s architecture test exists to make structurally impossible. Note also that the parser must acquire a new collaborator for the counter (`MetricsCounters`) — the first change to its dependency set since `SOR-11` was written, so the deny-list test needs to be *tightened to an allow-list* rather than silently widened. | **Medium** | **CRITICAL** (New) |
| **SOGT-09** | Availability | CWE-1284, CWE-682 / A05 | SGB-02's startup assertion | **`max-findings-per-file ≤ 200` is a bound on the wrong quantity if the accounting is cumulative.** Under the per-site reading it is a correct ~10× margin. Under the cumulative reading (`n_prev_rules` accumulating across sites — the reading §1.2 could not exclude and P-1 exists to settle) the binding constraint is `files × findings`, and a perfectly legal `max-findings-per-file: 150` with 20 files exceeds 2000 while passing the assertion cleanly at startup. The failure then reappears where SGB-02 was supposed to remove it: a 250 ms fleet-wide runtime rejection, now with a startup check that *certified* the configuration. A check that says "safe" when it has only verified one of two factors is worse than no check, because it stops the operator looking. | **Low-Medium** | **TRACKED** (New) |
| **SOGT-10** | Integrity | CWE-20, CWE-770 / A04 | chunk / per-file `summary` | **Two of the four fields SGB-01 unbinds have no receipt-side cap at all, and the gap is currently masked by a defect.** SGB-03 specifies truncation for `comment`/`suggestion` only. `ReviewSchemaBuilder` also drops `maxLength` from the per-file `summary` (200) and the chunk `summary` (500). Today that is harmless *by accident*: the per-file summary is only existence-checked (`validateFileEntryAndCollect:287-292`, value never read) and the chunk summary is returned in `Success.summary` and then **discarded** — `ResultProcessor:403` constructs `new ReviewResult(..., command.rawResponse(), null, ...)`, i.e. `SRO-26` ("the chunk summary populates `review_results.summary`") is not actually wired. So there is nothing to bound *today*. The threat is the trapdoor: whoever wires `SRO-26` up later inherits an unbounded, model-controlled string going straight into a `TEXT` column, with the `maxLength` that used to bound it deleted three branches earlier and no comment saying why. | **Low** | **TRACKED** (New/Found) |

**Tally:** Medium-High = 1, Medium = 5, Low-Medium = 1, Low = 3. Total **10**.
**By gate: BLOCKING = 2 (SOGT-01, SOGT-07), CRITICAL = 4 (SOGT-02, SOGT-03, SOGT-04, SOGT-08),
TRACKED = 4.**

---

## 3. Security requirements — SOGB-01..SOGB-12

Testable assertions for `backend-developer` on `fix/structured-output-grammar-budget`; AppSec
re-verifies each in the SAST round (`docs/security/feature-structured-output-grammar-budget-sast-report.md`,
prefix `F-SOGB-`).

### BLOCKING — resolve in the design/before the branch is declared done

- **SOGB-01 (MUST, SOGT-01).** **Truncation is visible to the reviewer.** The parser's truncation
  decision is carried to `CommentRenderer` (an extra boolean/field on the value handed to
  `renderIndexed`, or an `alteredUpstream` flag OR-ed into `SanitizedCode.altered` — the shape is the
  developer's call, the property is not), so that a truncated `suggestion` is published **with**
  `ALTERED_CODE_MARKER` exactly as a sanitizer-altered one is, and a truncated `comment` (prose, which
  has no marker today) carries a Gateway-constant ellipsis/marker. No marker text is ever derived from
  model output (`WOR-04`/`SRO-41` discipline). *Test:* a 2500-char `suggestion` at
  `max-suggestion-chars: 2000` publishes a body containing `ALTERED_CODE_MARKER`; a 1500-char `comment`
  at `max-comment-chars: 1200` publishes a body whose prose ends in the constant marker; neither is a
  `SCHEMA_MISMATCH`. This is the `F-SRO-06` regression test, re-pointed at the new cut site.
- **SOGB-02 (MUST, SOGT-07).** **P-3 is evidence, not paperwork.** No backend leaves
  `structured_output_mode = 'OFF'` on the strength of this document, the architecture document, or any
  reading of llama.cpp source. §6's P-0 through P-4 run against **each** backend individually
  (`192.168.1.82:8000`, `192.168.1.83:8000`), their `GET /props` `build_info` is recorded, and the
  `## Grammar probe results` section lands in `DEPLOYMENT.md` **before** the first `UPDATE`. P-1's
  outcome — not an assumption — sets `gateway.structured.max-files-per-chunk` per SGB-05. If P-0 does
  not reproduce, the branch stops and the diagnosis is redone (the architect already says this; it is
  restated here as a release-gate item so it cannot be waived by whoever is holding the incident).

### CRITICAL — implementation-time, gates the SAST round

- **SOGB-03 (MUST, SOGT-02).** **Every cut happens on a code-point boundary.** Truncation MUST NOT
  leave an unpaired surrogate. One helper, used by the new cut site (and reused, not re-implemented,
  if `CommentRenderer.capLength`/`TextSanitizer.capLength` are ever touched):
  `text.substring(0, Character.isHighSurrogate(text.charAt(cut - 1)) ? cut - 1 : cut)` or
  `text.offsetByCodePoints`. *Test:* a `suggestion` of `cap - 1` ASCII characters followed by a
  non-BMP character (e.g. U+1F600) truncates to a String for which
  `new String(s.getBytes(UTF_8), UTF_8).equals(s)` holds, and which Jackson serializes without
  throwing. Grep assertion: no bare `substring(0, ` on a model-derived string in the new code.
- **SOGB-04 (MUST, SOGT-08).** **Truncation is unconditional.** The receipt-side length handling is
  reached on **every** structured result, with no branch on `Backend`, `StructuredOutputMode`,
  `structuredConstraintSent`, `finish_reason`, or any "the grammar guaranteed it" reasoning.
  `SOR-11`'s architecture test is **tightened from a deny-list to an allow-list**: the only types
  `StructuredResponseParser` may reference are `CommentParser`, `CommentRenderer`, `TextSanitizer`,
  `GatewayProperties`, `MetricsCounters`, Jackson, and its own DTO set — so the next collaborator
  added to it fails the test until someone justifies it. **[appsec SAST-round correction, `F-SOGB-01`]**
  This row originally read "…and its own DTO/**enum** set". That wording is wrong and was faithfully
  implemented as an allow-list entry for the whole `com.review.gateway.model.enums.` **package prefix** —
  which is where `StructuredOutputMode` lives, i.e. the allow-list re-permits by package exactly the type
  the deny-list it replaced forbade by name. The allow-list MUST enumerate the specific enum types the
  parser actually holds as a field (today: **none**), never the package. It MUST also cover
  constructor/method **parameter** and return types, not only declared fields — a `StructuredOutputMode`
  parameter added to `validate(...)` is the most natural shape of the `SOGT-08` shortcut and is invisible
  to a field-only scan. `RetryManagerNoJobFailureReasonDependencyTest` is the correct shape to copy. *Test:* the existing `SOR-11` pair (conforming
  response from a `mode = OFF` backend validated identically to a constrained one) extended with an
  over-length `comment`: truncated identically in both cases.
- **SOGB-05 (MUST, SOGT-03).** **The error-body read is bounded in bytes.** The non-2xx read uses a
  small dedicated bound — **≤ 8192 bytes** (a new `worker.limits.max-error-body-bytes`, or a
  `LlamaClient` constant; a constant is preferred, there is no reason for an operator to tune this) —
  never `worker.limits.max-response-bytes` (200000). The existing package-private `BoundedInputStream`
  is the mechanism; do not add a second one. *Test:* a stub backend returning a 1 MB `500` body causes
  at most ~8 KB to be read, and the job is still abandoned exactly once.
- **SOGB-06 (MUST, SOGT-03).** **The error-body read is bounded in *time*, and can never wedge the
  worker loop.** This is the half that matters. The read MUST have its own short deadline
  (≤ 2 s is ample for an error body; a `worker.network.*` property is acceptable but a constant is
  fine) after which classification silently degrades to `JobFailureReason.LLM_ERROR` — i.e. exactly
  today's behavior. The blocking `read()` MUST NOT be able to hold the worker-loop thread past that
  deadline; the straightforward shape is to perform the bounded read on a separate task and
  `get(deadline)` it, abandoning the stream on timeout. Under **no** outcome — timeout, `IOException`,
  empty body, non-UTF-8 bytes — may the read change whether the job is abandoned or how; it decorates
  a decision that is already made. *Test:* a stub backend that sends `500` headers and then never
  writes a body ⇒ the Worker abandons the job within the deadline, reports `LLM_ERROR`, and returns to
  `claim()`; the loop thread is provably not blocked afterwards.
- **SOGB-07 (MUST, SOGT-04).** **No backend-supplied byte survives the classification.** The matched
  text is used for **one** thing: choosing a constant from a closed enum. It is never returned, never
  stored, never logged at any level (not even truncated, not even at DEBUG), never placed in a
  `LlamaException` message, and never sent as `FailRequest.detail` — `WorkerLoop.DETAIL_BY_REASON`
  gains a **new Worker-side constant sentence** for `CONSTRAINT_REJECTED` (e.g. *"llama-server refused
  the decoder-constraint grammar (compile-time rejection)"*), exactly like the other seven entries.
  Matching is a plain ASCII case-insensitive `contains` over the bounded, UTF-8-decoded prefix against
  a `static final List<String>` — **no regex** (a backend-controlled subject plus a pattern is a
  catastrophic-backtracking invitation for nothing gained), and no JSON parsing of the error body.
  *Test:* a `500` body containing `"error":"...failed to parse grammar...<script>@here\n; worker-reported: heartbeat timeout"`
  produces `reason=CONSTRAINT_REJECTED`, a `detail` byte-identical to the constant, and a
  `review_jobs.last_error` containing none of that text; a grep assertion that the response-body
  variable reaches no logger and no DTO.
- **SOGB-08 (MUST, SOGT-04).** **Both enums gain the constant, and the deploy order is stated.**
  `CONSTRAINT_REJECTED` is added to `com.review.worker.error.JobFailureReason` **and**
  `com.review.gateway.model.enums.JobFailureReason` (the two are intentionally independent types;
  `fromWireValue` is the only parse — never `Enum.valueOf`). A Worker deployed ahead of the Gateway
  reports a reason the Gateway maps to `UNKNOWN` with a WARN and no `400` — that is `WOC-23` working as
  designed, and `DEPLOYMENT.md` says so rather than leaving it to be rediscovered. *Test:*
  `fromWireValue("CONSTRAINT_REJECTED")` on the Gateway enum resolves; an unknown reason still yields
  `UNKNOWN` + `200`.

### TRACKED — recorded, verified in the SAST round, not gating now

- **SOGB-09 (SHOULD, SOGT-09).** SGB-02's startup assertion message states **what it did and did not
  verify**: it bounds the per-site cost only, names `gateway.structured.max-findings-per-file` and
  llama.cpp's `MAX_REPETITION_THRESHOLD = 2000`, and says that the **`files × findings`** product is
  bounded by measurement (`gateway.structured.max-files-per-chunk`, per §6/P-3), not by this check. If
  P-1 returns the cumulative reading, the assertion becomes a two-factor check
  (`max-files-per-chunk × max-findings-per-file`) and the ~200 constant is replaced by the measured
  value.
- **SOGB-10 (SHOULD, SOGT-10).** The single truncation helper is the **only** place a structured
  string field is length-bounded, and `validateFileEntryAndCollect`'s per-file `summary` branch plus
  `Success.summary` carry a one-line comment recording that both are currently unbounded **because
  they are discarded** (`ResultProcessor:403` stores `summary = null`), and that wiring `SRO-26` means
  routing the chunk summary through the same helper first. Cheapest durable form: put the note where
  the value is dropped, not in a doc.
- **SOGB-11 (SHOULD, SOGT-06).** `structuredFieldTruncated` is keyed on a **closed Gateway
  vocabulary** or not keyed at all — `comment`/`suggestion` field names are acceptable
  (Gateway constants); a file path, project id, backend URL, mode, or any model-supplied string is not
  (`SOR-21`, `WOR-17`). The `DEBUG` line logs **lengths only**, never content, never a prefix of it.
  P-3 additionally records observed `completion_tokens` per probe, so the first constrained canary has
  a token-burn baseline to be compared against (`SOGT-06`'s "measure it" half). *Test:* the existing
  `SOR-21` marker-string test extended to the new counter.
- **SOGB-12 (SHOULD, docs).** The three documentation corrections the architecture doc's §8 already
  lists (`README.md:267`, `DEPLOYMENT.md:924-925`, `SRO-27`/§10/§13.1) land **in the same commit** as
  the `ReviewSchemaBuilder` change, not as a follow-up — the shipped defaults table currently tells an
  operator that `max-comment-chars`/`max-suggestion-chars` are decoder-enforced, and an operator acting
  on that after this branch merges is acting on a lie about a security control's strength.

---

## 4. Rulings the architect asked for

### 4.1 R-1 (share the per-file subtree via `$defs`/`$ref`) — **rejection upheld**

The core argument is airtight and does not need the portability discussion at all: the guard is
`n_prev_rules * total_rules >= MAX_REPETITION_THRESHOLD` evaluated **at a repetition site**, and a
`char{0,2000}` site is `1 × 2000 ≥ 2000` **the first time it is encountered**. De-duplication changes
how many times that site appears; it cannot change what happens the first time. So `$defs` cannot fix
the schema as shipped, and R-1's headline — *"rejected on correctness, before portability even comes
up"* — is correct.

Two amendments, one of which I applied to the architecture doc directly:

1. **R-1's parenthetical was wrong and self-contradictory.** It claimed *"SGB-01 makes the per-file
   subtree repetition-free"*. It does not: SGB-02 deliberately keeps `maxItems: 20` on `findings`, once
   per file. Corrected in place. This matters because it is the *only* place `$defs` could ever have
   helped — under the cumulative reading, sharing the per-file rule collapses 40 `maxItems` sites into
   1. So the honest statement is: `$defs` is useless against the bug we actually hit, and *potentially*
   useful against the bug P-1 is testing for. If P-1 returns the cumulative reading, R-1 should be
   revisited on its merits rather than treated as settled — and even then, reason (a), upstream
   **#21228**'s report of `$ref`/`$defs` schemas *silently falling back to unconstrained generation*,
   is decisive on its own: that is `SOR-INH-2`'s fail-open, chosen voluntarily, on a fleet we cannot
   observe. **SGB-05 (cap the file count) is strictly the safer lever than `$defs` for the same
   problem**, because its failure mode is a smaller chunk, not an invisible loss of the constraint.
2. Reason (b) — *"a build that does not resolve local `$ref` renders the constraint **vacuous rather
   than absent***" — is the strongest sentence in the table and is worth promoting into `SRO-02`'s
   rationale in the base doc, since `SRO-02` currently justifies inlining on a portability floor
   ("the server README's caveat") that reads like a style preference. Vacuous-vs-absent is the actual
   reason, and it generalizes: this feature's whole posture is that a control we cannot observe must
   at least fail *loudly*.

P-5 as informational-only, explicitly non-gating, is the right call and I would not change it.

### 4.2 Direction check against the CLAUDE.md non-negotiables (independent second pass)

The architect's §5 table is accurate. Four additions from this side:

- **"Gateway is the sole owner of business logic and state" — strengthened, not weakened.** The most
  important thing about SGB-03 is what it *doesn't* do: R-7 (auto-demote a backend on a grammar
  rejection) was rejected, so the one place this branch touches a backend-influenced signal is
  audit-only and provably inert (`SOGT-05`). Keep it that way; `SOGB-07`/`SOGB-08` are the tests that
  hold it.
- **"Idempotency everywhere a retry can happen" — satisfied, and worth stating why.** Truncation is a
  deterministic pure function of `(text, cap)`: re-processing the same raw response yields the same
  bytes, so a redelivered/retried result cannot produce two different published comments. This is a
  real property of the design, not an accident, because the cut is a prefix and not, say, a
  summarization.
- **"PostgreSQL is the single source of truth" — one nuance the architect did not state and should.**
  Truncation destroys information *on the publish path only*: the full model text survives in
  `review_results.raw_response`, so a truncated comment is always reconstructible. That is the actual
  justification for preferring truncate over reject, and it has exactly two holes, both pre-existing
  and both acceptable: the raw response is itself capped at `max-raw-response-length` (200000), and on
  a **retry** the stored raw belongs to the first attempt (`SOT-19`, accepted). Worth one sentence in
  `README.md` next to the `SOGB-12` correction.
- **"Fail fast at the edge" — SGB-03 is not a violation.** The length cap is a *presentation* bound,
  not a trust-boundary validation: the trust-boundary checks (shape, coverage, severity enum, key set,
  `maxItems`) all remain hard rejections. Rejecting a whole chunk because a comment was 1300 chars was
  never fail-fast, it was fail-*late* and fail-*wide* — the edge had already accepted the Review, the
  generation had already been paid for, and the blast radius was the sibling chunks. SGB-03 makes the
  system strictly less fragile. Endorsed without reservation.
- **One genuine tension the architect under-sold.** SGB-03 removes a *loud* signal. Today an
  over-length field produces a `SCHEMA_MISMATCH` an operator can see in `structuredValidationFailures`;
  tomorrow it is a `structuredFieldTruncated` counter and a `DEBUG` line. That is the right trade, but
  it only stays right if the counter is actually exposed on `GET /metrics` and watched during the §7
  canary — which `SOGB-11` and the architect's own §7 both require. If the counter is dropped as
  "nice-to-have" during implementation, this becomes a silent quality degradation, which is the
  failure mode this entire feature exists to eliminate.

### 4.3 Residual 1 — unbounded string generation at the decoder: **accepted, no new control**

Ruled in `SOGT-06`. Summary of the ruling: the DoS/token-burn profile after SGB-01 is **identical to
the shipped `OFF` default**, which is the only profile that has ever actually run. The bound being
given up (`maxLength` at the decoder) has never once been in force in this deployment — the grammar
was rejected before a token was generated, 100% of the time. Comparing the proposal against a state
that never existed would be the wrong baseline.

The chain that does the bounding is intact and unchanged: `maxTokens: 12000` (always sent, decoder-
level, not schema-level) → `finish_reason: length` → `StructuredResponseParser:181-187` classifies
`TRUNCATED` **before** `readTree` → retry → `FAILED`. Plus `worker.limits.max-response-bytes` (200000)
and `capRawResponseIfNeeded` (200000) on the way. No new control required.

Accepted as an extension of the existing residual rather than a new one — see `SOGB-INH-1` below —
with the single cheap obligation in `SOGB-11`: record `completion_tokens` during P-3 so the canary has
a baseline. Correct the `maxTokens: 8192` figure in §4 (done).

### 4.4 Residual 2 — truncation as a text-mutation point: **accepted, mitigation amended**

Two sub-questions were asked; they have different answers.

**"Could a truncation create an unbalanced fence?"** — **No, and the reason is structural, provided
SGB-04's ordering holds.** Traced end to end:

1. The Gateway's fence marker is `CODE_FENCE` = **four** backticks, a Gateway constant emitted only at
   assembly (`renderSuggestionBlock:188-189`, `renderDiffContextBlock:210-212`).
2. Content can never contain a 3+ backtick run by the time assembly happens: `sanitizeCodeBlock:371`
   collapses `` `{3,} `` → `` `` `` for code, and `renderIndexed:110` does the same for prose
   (`F-SRO-07`).
3. Truncation only **removes trailing characters**. A prefix of a string cannot contain a backtick run
   longer than the original contained, so truncation **cannot manufacture** a fence marker; and because
   truncation happens *upstream* of step 2, any run it shortens (say 5 → 3) is still collapsed
   afterwards.
4. `hasBalancedFences:422-430` therefore still sees only Gateway-emitted markers, in pairs.

The one ordering that **would** be unsafe is the inverse — collapse first, truncate second — because a
cut could then land inside a Gateway-emitted `` ```` `` marker. `CommentRenderer` already avoids that
(its caps drop whole blocks, and `assembleWithTruncatedProse` cuts prose only, in a body that by then
has no code blocks). SGB-04's "truncate at parse, before the renderer" preserves it. **So SGB-04's
ordering requirement is correct as written and is load-bearing — it is not a formality.**

**"…or otherwise produce output SOR-09's check doesn't catch?"** — **Yes: two things, and they are the
findings.** `hasBalancedFences` verifies *fence parity*. It says nothing about (a) whether the block's
content is a faithful excerpt (`SOGT-01` — the missing `ALTERED_CODE_MARKER`, BLOCKING) or (b) whether
the body is *encodable at all* (`SOGT-02` — the split surrogate, which is not a Markdown problem and
which no fence check would ever detect). Both mitigations are mandatory: `SOGB-01` and `SOGB-03`.

`SGB-04`'s third clause — "MUST NOT be applied to already-fence-wrapped text" — is correct and is
automatically satisfied by cutting in the parser, since nothing is fence-wrapped until
`CommentRenderer` assembles. Worth an explicit test anyway (`T-4.11` already covers the backtick-run
and surrogate boundary cases; add the marker assertion from `SOGB-01`).

### 4.5 Residual 3 — `CONSTRAINT_REJECTED` string matching: **accepted, mitigation materially amended**

Both halves of the architect's question are answered **yes** — and both answers are conditional on
requirements that were not in the design doc.

**"Can backend-supplied raw text reach `last_error`/the audit trail un-sanitized via this path?"** —
**No, provided `SOGB-07` is honored.** The existing architecture already forbids it structurally:
`WorkerLoop.DETAIL_BY_REASON` is a Worker-side constant map (`WOR-05`), `reportFailureBestEffort` sends
`DETAIL_BY_REASON.get(reason)`, and even if a Worker did send free text, the Gateway
`sanitizeSingleLine(detail, 200)`s it and prefixes it positionally (`WOR-04`). SGB-06 needs to add
*one enum constant and one constant sentence*. The finding is that the tempting implementation —
surfacing the real llama-server error so the operator can see it — is exactly the regression, and
nothing in the design doc warned against it. `SOGB-07` states it as a MUST with a test.

**"Is the classification genuinely inert w.r.t. the retry decision?"** — **Yes, verified in code, and
not by accident.** `QueueManager.reportFailure:613-625` resolves the enum for the *audit string only*
and calls `retryManager.requeueOrFail(jobId, composedReason, workerId)`; `RetryManager` takes a
`String reason` and has no `JobFailureReason` import, parameter, or branch — the decision is
`attempts >= maxAttempts` and nothing else. A new enum constant cannot reach it. Add the negative
architecture test anyway (`SOGB-07`'s grep half), because the property is currently guaranteed by an
absence, and absences are what regressions are made of.

**What the architect missed, and it is the biggest single finding in this round:** the *ingestion*, not
the matching. `LlamaClient.parseResponse:159-162` throws on a non-2xx **without reading the body**.
SGB-06 must therefore add a read of backend-controlled bytes, on the worker-loop thread, at the one
moment when neither `request-timeout-sec` nor `AbortSignal` can still bound it (`SOGT-03`). Bytes are
the easy half (`SOGB-05`, 8 KiB, reuse `BoundedInputStream`); **time is the half that can wedge a
Worker permanently** (`SOGB-06`). Also: match with `contains`, not a regex, against a
backend-controlled subject.

### 4.6 §4's claim that `SOR-11`/`SRO-04` isolation is untouched: **confirmed, with one correction to
the claim's wording**

**Confirmed on substance.** Nothing in SGB-01..SGB-07 gives `StructuredResponseParser` a reason to know
about `Backend`, `StructuredOutputMode`, or the wire mode. SGB-01 is upstream of it entirely (schema
construction), and SGB-03 changes what the parser *does* with a length overflow, not *whether it
checks*. The check itself moves from `if (tooLong) fail` to `if (tooLong) truncate` — same
unconditional evaluation, different outcome. `SOR-11`'s architecture test passes unchanged.

**Two corrections to how §4 states it.**

1. The row says `SOR-11`'s test asserts "no branch on `structured_output_mode`/`finish_reason`". The
   shipped parser **does** branch on `finish_reason` — `validate:181-187` classifies `TRUNCATED` when
   `FinishReason.fromWireValue(finishReason) == LENGTH`. That is not a violation, because the property
   `SOR-11` actually protects is **directional**: no backend/decoder-derived signal may cause
   validation to be *skipped* or to *pass*. `finish_reason` is only ever allowed to make the outcome
   **stricter** (fail-closed). Stating it as "no branch on `finish_reason`" is both false against the
   code and dangerously imprecise — a future reader who takes it literally either "fixes" a correct
   fail-closed check or concludes the rule is already broken and stops respecting it. `SOGB-04`
   restates the property directionally.
2. The parser gains a new collaborator (`MetricsCounters`, for `structuredFieldTruncated`) — the first
   change to its dependency set since `SOR-11` was written. Benign in itself (`MetricsCounters` holds
   no backend/mode state), but the test must be **tightened from a deny-list to an allow-list** at the
   same commit, or it silently stops being the control it was (`SOGB-04`).

---

## 5. Release gate

**Blocking (before the branch is declared done / before any backend leaves `OFF`):**
SOGB-01, SOGB-02.

**Critical (implementation, gates the SAST round):** SOGB-03, SOGB-04, SOGB-05, SOGB-06, SOGB-07,
SOGB-08.

**Tracked (verified in the SAST round, not gating now):** SOGB-09, SOGB-10, SOGB-11, SOGB-12.

**Accepted residuals:**

- **SOGB-INH-1 (ACCEPTED-RISK, SOGT-06) — extends `SOR-INH-1`.** Removing `maxLength` gives up a
  decoder-level per-field token bound. Accepted because that bound has never been in force in this
  deployment (the grammar was rejected before generation, 100% of attempts) and because the profile it
  reverts to is the shipped `OFF` default. Compensated by `maxTokens: 12000`,
  `worker.limits.max-response-bytes`, `capRawResponseIfNeeded`, the `TRUNCATED` classification,
  `max-attempts`, `not_before` (`WOR-01`), and the `SRO-39` kill switch. Measured via P-3's
  `completion_tokens` record and the §7 canary.
- **SOGB-INH-2 (ACCEPTED-RISK, SOGT-05).** `CONSTRAINT_REJECTED` is a backend-influenced assertion
  about the backend: forgeable by echoing the token, suppressible by rewording the error (degrades to
  `LLM_ERROR` — fail-safe). Accepted because it is audit-only and `RetryManager` structurally cannot
  branch on it (`SOGB-07`). **The condition of acceptance is that it stays decorative**: it must never
  become an input to a demotion job, an alert-driven automation, or a config change (R-7, upheld).
- **SOGB-INH-3 (ACCEPTED-RISK, SOGT-03 residual).** A stalled response **body** on the *existing* 2xx
  path (`readBounded`) has always been able to block the worker-loop thread past every timeout — bytes
  are bounded (`WSR-04`), time is not. Pre-existing, out of scope for this branch, bounded in practice
  by `gateway.job.max-duration` (45m) requeueing the job on the Gateway side. Recorded because
  `SOGB-06` fixes the same class on the new path and a future round should consider whether the 2xx
  path deserves the same deadline.
- **`SOR-INH-2` (inherited, unchanged, and now better evidenced).** The decoder-level half of the
  control runs on an unpinned third-party binary. This incident is `SOR-INH-2` realized in its
  *loud* form (a hard reject, correctly), which is the good outcome — the feared form remains the
  silent fail-open. `SGB-07`'s production-shape recipe plus P-4's negative control are the compensating
  controls, and both are strengthened by this branch.

**Non-regression set to re-verify in the SAST round:** `SOR-09` (fence integrity — now against a
truncated prefix, `SOGT-01`/§4.4), `SOR-11` (un-shortcuttable validation, allow-list form —
`SOGB-04`), `SOR-21` (closed counter vocabulary — `SOGB-11`), `SOR-22`/`F-SRO-06`
(`ALTERED_CODE_MARKER` on any altered block — `SOGB-01`), `F-SRO-07` (backtick-run collapse in prose,
still after truncation), `F-SRO-03` (`coverageReserveTokens` shared formula — untouched, but
`max-files-per-chunk` moves under SGB-05, and the startup budget check reads it), `WOR-04` (positional
audit discriminator), `WOR-05` (constant `detail`, never an exception message — `SOGB-07`), `WOC-23`
(`fromWireValue`, never `Enum.valueOf` — `SOGB-08`), `WOC-24` (audit-only reason — `SOGB-07`),
`WSR-04` (bounded response read, now on two paths — `SOGB-05`), `SR-21` (`capRawResponseIfNeeded`
before parsing), `SRO-18`/`SOR-05a` (Jackson tree only — the diff deletes a `put`, adds no construction
path; the architect is right that this is the fastest clearance in the set).

**CI gate:** the existing `SR-23` gate covers this branch; no new tooling. Two Semgrep rules worth
adding while it is in flight: (a) flag `substring(0,` applied to a model-derived string outside the one
approved truncation helper (`SOGB-03`); (b) flag any slf4j call or DTO assignment in `worker/.../llama`
whose argument derives from an HTTP response body (`SOGB-07`).

---

## 6. Files the developer will actually touch

`src/main/java/com/review/gateway/service/ReviewSchemaBuilder.java:177-182` (`stringSchema` — the
one-line deletion), `:97,:135,:172-173` (call sites losing their argument);
`src/main/java/com/review/gateway/service/StructuredResponseParser.java:333-341` (reject → truncate,
`SOGB-01`/`SOGB-03`/`SOGB-04`), `:394` (`RawFinding` — the natural place for the truncation flag),
`:100-135` (the new `MetricsCounters` collaborator, `SOGB-04`);
`src/main/java/com/review/gateway/service/CommentRenderer.java:356-375` (`SanitizedCode.altered` —
where the upstream flag must be OR-ed in), `:432-438` (`capLength`, the surrogate-unsafe helper),
`:98-148` (`renderIndexed`'s signature if the flag is threaded through);
`src/main/java/com/review/gateway/service/MetricsCounters.java:31-34,:55-68` (+1 counter, `SOGB-11`);
`src/main/java/com/review/gateway/config/GatewayProperties.java:194-222` (`validateStructuredOnStartup`
— SGB-02's assertion, `SOGB-09`);
`worker/src/main/java/com/review/worker/llama/LlamaClient.java:158-172` (`parseResponse`'s non-2xx
branch — `SOGB-05`/`SOGB-06`/`SOGB-07`), `:174-179` (`readBounded` — reuse the mechanism, not the
bound); `worker/src/main/java/com/review/worker/llama/BoundedInputStream.java` (package-private, same
package — no visibility change needed);
`worker/src/main/java/com/review/worker/error/JobFailureReason.java` and
`src/main/java/com/review/gateway/model/enums/JobFailureReason.java` (`SOGB-08`);
`worker/src/main/java/com/review/worker/core/WorkerLoop.java:57-68` (`DETAIL_BY_REASON` — the constant
sentence, `SOGB-07`);
`src/main/java/com/review/gateway/service/ResultProcessor.java:403` (the `summary = null` trapdoor,
`SOGB-10`), `:353-357` (`structuredSchemaOptions` — unchanged, but it is what feeds the caps);
`src/main/java/com/review/gateway/service/QueueManager.java:592-631` and
`src/main/java/com/review/gateway/service/RetryManager.java:93-165` (read-only: the proof that the
reason cannot reach the retry decision, `SOGB-07`).

**Next:** `backend-developer` on `fix/structured-output-grammar-budget`, then `qa-engineer`
(T-1.4 inverted, T-1.10, T-2.9, T-4.11 per the architecture doc's §8, **plus** the `SOGB-01` marker
assertion and the `SOGB-03` surrogate case inside T-4.11, and a `SOGB-06` stalled-error-body test),
then the SAST round (`docs/security/feature-structured-output-grammar-budget-sast-report.md`, prefix
`F-SOGB-`). **P-0..P-4 (`SOGB-02`) gate the backend re-enablement, not the merge** — the branch can
merge with every backend still `OFF`.
