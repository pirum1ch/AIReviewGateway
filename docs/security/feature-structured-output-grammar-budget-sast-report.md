# SAST report — Structured Output Grammar Budget (`fix/structured-output-grammar-budget`)

Round: **step 5 of CLAUDE.md's feature workflow** — independent SAST/verification pass over the finished
branch, after `architect` → `appsec` (pre-implementation threat model) → `backend-developer` →
`qa-engineer` → `backend-developer` (QA fix round).

Finding prefix: **`F-SOGB-`** (reserved for this round by
`docs/structured-output-grammar-budget-architecture.md:13`; confirmed unused in every other report under
`docs/security/`).

Scope: `af620fd..HEAD` (5 commits), plus the **final state** of every file that branch touched, plus the
non-regression set named in `docs/structured-output-grammar-budget-threat-model.md` §5.

Specs verified against:

- `docs/structured-output-grammar-budget-architecture.md` (`SGB-01..SGB-07`)
- `docs/structured-output-grammar-budget-threat-model.md` (`SOGT-01..10`, `SOGB-01..12`, §5 release gate)
- `docs/structured-review-output-architecture.md` / `-threat-model.md` (`SRO-nn`, `SOR-nn`, `SOT-nn`)

**Test suites executed as part of this round** (not just read):

| Module | Command | Result |
|---|---|---|
| Gateway (`.`) | `JAVA_HOME=/c/Users/dmitr/.jdks/corretto-21.0.8 mvn -o test` | **844 run, 0 failures, 0 errors — BUILD SUCCESS** |
| Worker (`worker/`) | same | **163 run, 0 failures, 0 errors — BUILD SUCCESS** |

One temporary probe test was written, run, and deleted (it produced the empirical evidence cited in
`F-SOGB-02` and `F-SOGB-03`); no production code was modified by this round.

---

## 0. Verdict

**No CRITICAL and no BLOCKING findings. The branch's production code is sound.** Every `SOGB-nn`
release-gate item that gates the SAST round (`SOGB-03..SOGB-08`) is **met in the final code**, including
the two the threat model singled out as the highest risk (`SOGB-06`'s time bound on the new
backend-controlled error-body read, and `SOGB-01`'s truncation marker).

**One finding must be fixed before final verification:** `F-SOGB-01` — the `SOR-11` architecture test was
"tightened" from a deny-list to an allow-list, and the allow-list is **strictly weaker** than the
deny-list it replaced on the single most important type (`StructuredOutputMode`). This is a test-only
defect — no production behaviour is wrong today — but it removes a control the threat model designates
**CRITICAL** (`SOGB-04`) and it does so silently. Root cause is a drafting error in `SOGB-04` itself,
which the developer implemented faithfully; I have corrected that row inline in the threat model (see §5).

Everything else is Low/Info and can ship or be deferred at the maintainer's discretion.

**Recommendation: one more `backend-developer` fix round for `F-SOGB-01` (and, cheaply, `F-SOGB-03` +
`F-SOGB-04`), then final verification.** `F-SOGB-02` is pre-existing, not a regression of this branch, and
is a legitimate "fix now or track" call.

`SOGB-01`/`SOGB-02` (the two **BLOCKING** gate items) are unaffected by all of the above:
`SOGB-01` (truncation visible to the reviewer) is **met**; `SOGB-02` (the P-0..P-4 empirical probes
against `192.168.1.82`/`.83`) is **out of scope for this round by design** — per the threat model §5 it
gates *backend re-enablement*, not the merge, and I performed no llama.cpp probes and flipped no
`backends.structured_output_mode` value.

---

## 1. Findings

| # | Severity (CVSS-ish) | CWE / OWASP | Where | Description | Fix |
|---|---|---|---|---|---|
| **F-SOGB-01** | **Medium** (control durability; CVSS n/a — test-only) | CWE-1288, CWE-636 / A04 | `src/test/java/com/review/gateway/service/StructuredResponseParserTest.java:560-586` | The `SOR-11`/`SOGB-04` allow-list re-permits `StructuredOutputMode` by package, and scans fields only | Enumerate specific types; extend to parameter/return types |
| **F-SOGB-02** | **Medium** (pre-existing, not a branch regression) | CWE-176, CWE-116 / A04 | `src/main/java/com/review/gateway/service/ResultProcessor.java:335` | A third bare `substring(0, n)` cut on model-derived publish text — can emit a lone UTF-16 surrogate with no downstream safe re-cut | Route through `TextSanitizer.truncateSafely` |
| **F-SOGB-03** | **Low** | CWE-451 / LLM05 | `src/main/java/com/review/gateway/service/CommentRenderer.java:468-469` | `assembleWithTruncatedProse` **slices** `TRUNCATED_COMMENT_MARKER` instead of dropping it; the javadoc claims the opposite. Degrades `SOGB-01`'s prose marker to an unrecognizable fragment | One-word change: cap `base`, not `prose` |
| **F-SOGB-04** | **Low** | CWE-1059 / A05 | `application.yml:112-113`; `GatewayProperties.java:1359,1361`; `TextSanitizer.java:124-126`; `DEPLOYMENT.md` maxTokens row | `SOGB-12` only partially met — four surfaces still describe the pre-fix reality | Text edits |
| **F-SOGB-05** | **Info** (TRACKED, `SOGB-10` unmet) | CWE-1059 / — | `ResultProcessor.java:403`; `StructuredResponseParser.java:296-301` | The `summary`-is-unbounded-because-discarded trapdoor note was never written | Two one-line comments |
| **F-SOGB-06** | **Info** | — | `ReviewSchemaBuilder.java:103-144` | `SchemaOptions.maxCommentChars`/`maxSuggestionChars` are now threaded through the builder but never read by it | Optional one-line comment |
| **F-SOGB-07** | **Low** (process) | CWE-1059 / — | `docs/structured-output-grammar-budget-architecture.md`, `docs/structured-output-grammar-budget-threat-model.md` | Both of this branch's own SDLC artifacts are **untracked working-tree files** — never committed | `git add` them (and this report) before merge |

---

### `F-SOGB-01` — **Medium** — the `SOR-11` allow-list is weaker than the deny-list it replaced

**CWE-1288** (improper validation of source of a security check), **CWE-636** (not failing securely) /
OWASP **A04:2021 Insecure Design**. Non-regression item `SOR-11`; release-gate item `SOGB-04`
(**CRITICAL**).

**Where:** `src/test/java/com/review/gateway/service/StructuredResponseParserTest.java:560-586`.
The test it replaced — `classHasNoReferenceToBackendOrStructuredOutputMode`, at
`af620fd:src/test/java/…/StructuredResponseParserTest.java:348-355` — was **deleted**, not kept alongside.

```java
List<String> allowedTypePrefixes = List.of(
        ...
        "com.review.gateway.model.enums.",   // <-- StructuredOutputMode lives here
        ...
```

**Two independent defects:**

1. **The package prefix `com.review.gateway.model.enums.` re-permits the forbidden type.**
   `src/main/java/com/review/gateway/model/enums/StructuredOutputMode.java` is inside it. The removed
   deny-list asserted `typeName` **doesNotContain** `"StructuredOutputMode"`; the new allow-list
   **explicitly allows** it. On the one dimension `SOR-11` exists to protect, the "tightening" is a
   loosening. Note the prefix is not even needed today: `StructuredResponseParser` declares **no** field
   of any `model.enums` type (`Severity`/`FinishReason` appear only as local/inline references), so the
   entry can simply be deleted.

2. **The scan covers `getDeclaredFields()` only.** The most natural shape of the `SOGT-08` shortcut is
   not a field — `validate(...)` already takes seven parameters, so
   `validate(…, StructuredOutputMode mode)` + `if (mode != OFF) { /* the grammar guaranteed it */ }` is a
   two-line change that leaves **both** the old deny-list and the new allow-list green.

**Concrete failure scenario:** a future round adds a "skip the length/coverage re-check when the backend
was constrained" fast path — the exact fail-open `SOR-11`/`SOGB-04` were written to make structurally
impossible — and CI stays green. The property is protected by an absence, and the test that was supposed
to guard the absence now has a hole big enough for the named threat to walk through.

**Root cause is upstream of the developer.** `SOGB-04`'s own text said *"…and its own DTO/**enum**
set"*, which is what was implemented. **I have corrected that row inline in
`docs/structured-output-grammar-budget-threat-model.md` §3 (marked `[appsec SAST-round correction,
F-SOGB-01]`)** so the next round does not re-derive the same allow-list.

**Fix (fix-ready):**

- Delete the `"com.review.gateway.model.enums."` entry (and `"com.review.gateway.service.dto."`,
  `"java.util.Set"`, `"java.lang.String"` are likewise unreferenced-by-field — keep only what a declared
  field actually needs, so the list is falsifiable).
- Extend the scan to `getDeclaredConstructors()` parameter types, `getDeclaredMethods()` parameter types,
  and method return types. **`src/test/java/com/review/gateway/service/RetryManagerNoJobFailureReasonDependencyTest.java:21-45`
  already does exactly this** and is the shape to copy — it is a strictly better architecture test than
  the one it sits next to.
- Optionally re-add the deleted deny-list assertion as an additive second assert; belt-and-braces is
  cheap here and it is the assertion that names the threat in words a future reader will recognize.

---

### `F-SOGB-02` — **Medium** — a third bare `substring` cut on model-derived publish text

**CWE-176** (improper handling of Unicode), **CWE-116** (improper encoding/escaping of output) / OWASP
**A04**. Threat: `SOGT-02` (**CRITICAL**); requirements `SOGB-03` / `SOGB-10`.

**Where:** `src/main/java/com/review/gateway/service/ResultProcessor.java:335`

```java
String combined = UNVALIDATED_FALLBACK_PREFIX + comment.text();
String capped = combined.length() > maxLength ? combined.substring(0, maxLength) : combined;
```

**Empirically confirmed** during this round (temporary probe, since deleted): with the default
`gateway.publish.max-comment-length = 4000`, a fallback comment whose text carries a non-BMP code point
straddling index 4000 yields a `String` containing an **unpaired high surrogate**.

**Why this one matters more than the other surviving `substring` sites:** it is the only one with **no
downstream safe re-cut**. It runs in `withUnvalidatedPrefix`, *after* `CommentRenderer` has already
returned, and its output goes straight into `ParsedComment.text` → `review_comments.text` (pgjdbc) and
the GitLab note body (Jackson). Jackson's `UTF8JsonGenerator` refuses an unpaired surrogate, so
publication throws — and it throws again on **every** publish-retry, producing exactly `SOGT-02`'s
described end state: a `COMPLETED` Review that can never reach `PUBLISHED`, with no diagnostic anywhere
pointing at truncation.

**Reachability:** requires `gateway.structured.on-invalid-response = RETRY_THEN_FALLBACK` (non-default;
documented as an emergency escape hatch, `SRO-38/68`). That is what keeps this Medium rather than High.

**Not a regression of this branch** — the site predates it. It is reported here because `SOGB-03`'s
requirement is *"One helper, used by the new cut site (and reused, not re-implemented…)"* and `SOGB-10`'s
is *"The single truncation helper is the **only** place a structured string field is length-bounded"*.
The QA fix round swept two of the three cut sites on the structured publish path (`CommentRenderer.capLength`
and the parser's new one); this one was missed. `SOGT-02` itself only enumerated
`CommentRenderer.capLength` and `TextSanitizer.capLength`, so nobody was looking here.

**Fix (fix-ready):** inject `TextSanitizer` into `ResultProcessor` (it is not currently a dependency —
`ResultProcessor.java:64-76`) and replace line 335 with:

```java
String capped = textSanitizer.truncateSafely(combined, maxLength).text();
```

`truncateSafely` returns the input unchanged when it already fits, so this is behaviour-preserving except
for the surrogate back-off.

**Related sites, ranked by reachability — recorded, not filed as separate findings** (all pre-existing,
all outside this branch's stated scope per `TextSanitizer.java:124-126`):

| Site | Cut applied to | Downstream safe re-cut? | Note |
|---|---|---|---|
| `ResultProcessor.java:393` (`capRawResponseIfNeeded`) | raw model response, at `max-raw-response-length` (200000) | no | always-on path, but the sink is a `TEXT` column whose driver may substitute rather than throw; fires only above 200 KB |
| `CommentParser.java:341` (`capLength`) | HTML-escaped model prose, at `max-comment-length` | **yes on the v3 path only** — `CommentRenderer`'s own cap uses the *same* property minus the header, so it always re-cuts strictly tighter. **No** on the v1/v2 path | verified empirically: the v3 path could not be made to leak a lone surrogate through this site |
| `TextSanitizer.java:111` / `ChunkContextRenderer.java:214` | paths / `detail` strings, small caps | n/a | low-value targets; a path/`detail` rarely carries non-BMP text |

If a follow-up wants to close the class rather than the instance: **do not** blindly delegate
`CommentParser.capLength`/`TextSanitizer.capLength` to `truncateSafely` — they append a `"..."` suffix
that `truncateSafely` does not, and changing v1/v2 output byte-for-byte would regress `SRO-54`/`SR-09`.
The correct minimal change there is to add the same one-line high-surrogate back-off to their existing
`cut` computation, keeping the suffix.

---

### `F-SOGB-03` — **Low** — `assembleWithTruncatedProse` slices the truncation marker instead of dropping it

**CWE-451** (user-interface misrepresentation) / **LLM05**. Requirement `SOGB-01` (**BLOCKING**), the
prose half.

**Where:** `src/main/java/com/review/gateway/service/CommentRenderer.java:456-474`, specifically:

```java
if (marker.isEmpty() || marker.length() >= proseBudget) {
    cappedProse = capLength(prose, proseBudget);      // <-- `prose`, which still CONTAINS the marker
}
```

The javadoc immediately above (added in the QA fix commit `2ae6d61`) states: *"only if the budget is too
small even for the marker alone does it get **dropped** and the raw prose safely cut instead."* The code
does not drop it — it caps the marker-bearing string, so the cut lands **inside** the marker.

**Empirically confirmed** during this round. At `gateway.publish.max-comment-length = 36` and `37`, a
truncated prose comment renders with the body ending in `… hello world _` and `… hello world _(`
respectively. `proseBudget = 37 − header(21) − 2 = 14`, `marker.length() == 14`, so the
`marker.length() >= proseBudget` branch fires and `capLength("hello world _(truncated)_", 14)` yields
`"hello world _("`.

**Impact:** `SOGB-01` requires that a truncated comment *"carries a Gateway-constant ellipsis/marker"* so
a mid-sentence cut does not read as complete. In this branch the marker degrades to an unrecognizable
fragment, i.e. the control silently produces nothing a reviewer would read as "truncated". No crash, no
encoding risk (`capLength` is surrogate-safe post-fix), no injection — hence Low.

**Reachability:** only when `header.length() + 2 + 2 × 14 > max-comment-length`, i.e. roughly
`gateway.publish.max-comment-length < 50`. The default is `4000`, and there is no startup assertion
placing a floor under this property (unlike `max-path-chars`, `max-findings-per-file`, etc. in
`GatewayProperties.validateStructuredOnStartup`). A misconfigured deployment is the only route.

**Fix (fix-ready):** in that branch cap `base`, not `prose` —

```java
cappedProse = capLength(marker.isEmpty() ? prose : base, proseBudget);
```

which genuinely drops the marker and matches the javadoc. Optionally add the corresponding assertion to
`CommentRendererTest.doubleTruncationNeverProducesAnUnpairedSurrogateOrUnbalancedFenceAcrossManyLengthBudgets`
(which already sweeps `maxLength` 5..140 and would have caught this had it asserted on the marker):
*"for every budget, the rendered body either contains the whole `TRUNCATED_COMMENT_MARKER` or none of
its characters."*

---

### `F-SOGB-04` — **Low** — `SOGB-12` is only partially met; four surfaces still describe the pre-fix reality

**CWE-1059** (insufficient/incorrect documentation) / OWASP **A05**. Requirement `SOGB-12` (TRACKED):
*"the shipped defaults table currently tells an operator that `max-comment-chars`/`max-suggestion-chars`
are decoder-enforced, and an operator acting on that after this branch merges is acting on a lie about a
security control's strength."*

`README.md:267` and `DEPLOYMENT.md:960-961` **were** corrected (verified). Four surfaces were not:

1. **`src/main/resources/application.yml:112-113`** — still reads
   `max-comment-chars: 1200  # SRO-27 -> maxLength поля comment одного finding` and the same for
   `max-suggestion-chars`. This is the file an operator actually edits, so it is the most load-bearing of
   the four.
2. **`src/main/java/com/review/gateway/config/GatewayProperties.java:1359` and `:1361`** —
   `/** SRO-27 -> finding {@code comment} {@code maxLength}. */` (and `suggestion`). Same claim, in the
   binding class.
3. **`src/main/java/com/review/gateway/service/TextSanitizer.java:124-126`** — `truncateSafely`'s javadoc
   still says it is *"Deliberately a distinct helper from `capLength`/`CommentRenderer.capLength` (both
   pre-existing, both plain `substring` — `SOGT-02` flagged them as a general observation, out of scope
   for this fix)"*. Commit `2ae6d61` changed `CommentRenderer.capLength` to delegate **to this very
   method**, so the sentence is now false about half its subject and actively misleads a reader auditing
   the cut sites (it is what makes `F-SOGB-02`'s site look like it was consciously scoped out when it was
   simply never enumerated).
4. **`DEPLOYMENT.md`, budget summary table, row `v3.yml → maxTokens`** — still `8192`. The actual value
   is `12000` (`worker/src/main/resources/prompts/v3.yml:14`). The threat model corrected this figure
   explicitly (`[appsec correction]`, §4 / §4.3), and the branch applied the correction to
   `docs/structured-review-output-architecture.md` §10 but not to the operator-facing table — which is
   the "единая сводная таблица" an operator uses to reconcile `answer-reserve` against `maxTokens`.

**Fix:** text edits only. (2) and (3) are in production source and are therefore left to the
`backend-developer` fix round per this round's scope rules; (1) and (4) likewise.

---

### `F-SOGB-05` — **Info** — `SOGB-10` (the `summary` trapdoor note) was never written

Requirement `SOGB-10` (SHOULD, TRACKED, threat `SOGT-10`): *"`validateFileEntryAndCollect`'s per-file
`summary` branch plus `Success.summary` carry a one-line comment recording that both are currently
unbounded **because they are discarded** (`ResultProcessor:403` stores `summary = null`), and that wiring
`SRO-26` means routing the chunk summary through the same helper first. Cheapest durable form: put the
note where the value is dropped, not in a doc."*

`grep -rn "SOGB-10" src/` returns nothing. Verified: `ResultProcessor.java:403` still constructs
`new ReviewResult(reviewId, chunkIndex, jobId, command.rawResponse(), null, …)` with no comment, and
`StructuredResponseParser.validateFileEntryAndCollect:296-301` existence-checks the per-file `summary`
without reading or bounding it, likewise uncommented.

This is the one item the threat model asked for *specifically* because the guard that used to bound these
two strings (`maxLength`) was deleted three branches earlier by `SGB-01`. Non-gating; two comments.

---

### `F-SOGB-06` — **Info** — dead options in the schema builder

`ReviewSchemaBuilder.SchemaOptions.maxCommentChars`/`maxSuggestionChars` are still threaded through
`build` → `buildFilesNode` → `buildFileEntryNode` → `buildFindingItemNode`, but after `SGB-01` **no method
in that class reads either one**. The record is legitimately shared with `StructuredResponseParser`
(which does read them, as the receipt-side caps), so removing them is wrong — but a reader of
`ReviewSchemaBuilder` alone now sees two length knobs in scope at every emission site, which is precisely
the invitation `SGB-01` exists to remove. A one-line comment on the record (*"the two char caps are
receipt-side only — `StructuredResponseParser`'s, never this class's; see `stringSchema`'s javadoc"*)
costs nothing. No action required.

---

### `F-SOGB-07` — **Low (process)** — the branch's own SDLC artifacts were never committed

`git status --short` at the end of this round:

```
?? docs/structured-output-grammar-budget-architecture.md
?? docs/structured-output-grammar-budget-threat-model.md
?? docs/security/feature-structured-output-grammar-budget-sast-report.md
```

`git log --oneline af620fd..HEAD` confirms it: the docs commit `2c4f78f` touched only `README.md`,
`DEPLOYMENT.md` and `docs/structured-review-output-architecture.md`. **The architecture document and the
pre-implementation threat model that this entire branch is gated on exist only in the working tree.**

Merging `fix/structured-output-grammar-budget` as it stands would land the code without the two documents
that (a) CLAUDE.md's workflow requires per feature, (b) `README.md:267` / `DEPLOYMENT.md:210+` /
`ReviewSchemaBuilder.java:173-181` / `GatewayProperties.java:215-221` / both `JobFailureReason` enums all
**cite by path**, and (c) carry `SOGB-02`'s empirical release gate — the checklist that must be satisfied
before any backend leaves `structured_output_mode = 'OFF'`. Every one of those citations would be a dead
link on `master`, including the one inside the enum constant that tells a future reader why
`CONSTRAINT_REJECTED` must stay inert.

**Fix:** `git add` all three docs (this report included) in the fix-round commit. Note that
`docs/structural-exhaustiveness-gate-*.md` are also untracked, but those belong to the unrelated lineage
flagged in the architecture doc's §9 and are out of scope here.

---

## 2. Release-gate verification — `SOGB-01..SOGB-12`

| Id | Gate | Verdict | Evidence |
|---|---|:---:|---|
| **SOGB-01** | Truncation is visible to the reviewer | **PASS** | `CommentRenderer.java:127-129` (8-arg `renderIndexed` carrying `commentTruncatedUpstream`/`suggestionTruncatedUpstream`), `:226` (`sanitized.altered() \|\| truncatedUpstream`), `:141-145` (`TRUNCATED_COMMENT_MARKER`, a `private static final` Gateway constant). Threaded from `StructuredResponseParser.java:259-261`. Tests: `overLengthSuggestionIsTruncatedAndStillCarriesTheAlteredCodeMarker`, `overLengthCommentIsTruncatedAndEndsInTheConstantTruncatedMarker`. Marker degradation at absurd budgets → `F-SOGB-03` (Low) |
| **SOGB-02** | P-0..P-4 before any backend leaves `OFF` | **N/A this round** | Ops-gated, explicitly out of scope; no probe run, no `structured_output_mode` value touched. `DEPLOYMENT.md` has no `## Grammar probe results` section yet — correct, that lands with the probes |
| **SOGB-03** | Every cut on a code-point boundary | **PASS** for the new cut site | `TextSanitizer.truncateSafely:132-145` — backs the cut off by one `char` when `charAt(cut-1)` is a high surrogate; `null`-in/`null`-out; `max` clamped ≥ 0. Sole cut site in `StructuredResponseParser.java:353-354`. `CommentRenderer.capLength:501-503` now delegates to it (commit `2ae6d61`). Tests: `TextSanitizerTest`, `truncationAtASurrogatePairBoundaryNeverProducesALoneSurrogate`, `CommentRendererTest`'s 5..140 budget sweep. **Not** met globally → `F-SOGB-02` |
| **SOGB-04** | Truncation unconditional; `SOR-11` deny-list → allow-list | **PARTIAL** | Unconditional: **PASS** — `validateFindingAndCollect:353-354` runs on every finding, no branch on `Backend`/`StructuredOutputMode`/`structuredConstraintSent`/`finish_reason`; the parser has no `Backend`/mode parameter at all. Allow-list: **FAIL** → `F-SOGB-01` |
| **SOGB-05** | Error-body read bounded in bytes | **PASS** | `LlamaClient.java:69` `MAX_ERROR_BODY_BYTES = 8192L`, a constant not a property, as required. `readErrorBodyBounded:266-276` uses **the existing** `BoundedInputStream` (`worker/…/llama/BoundedInputStream.java`, `FilterInputStream`, throws mid-stream) — no reimplementation. `readAllBytes()` routes through the overridden `read(byte[],int,int)`, so the bound is genuinely enforced (peak ≈ 16 KB, one buffer past the limit). Oversize → `""` → `LLM_ERROR`. Test: `oversizedErrorBodyIsBoundedAndNeverReadsPastTheLimitToFindAMatch` (20 000-byte prefix hiding the marker → not detected) |
| **SOGB-06** | Error-body read bounded in **time**, independent of `request-timeout-sec` | **PASS** | `LlamaClient.classifyNon2xx:238-263` — the read runs on a short-lived **daemon** thread and is joined via `readCompletion.get(2s)`; `ERROR_BODY_READ_TIMEOUT` is a `LlamaClient` constant, not derived from `worker.network.request-timeout-sec`. On timeout: `closeQuietly(body)` unblocks the reader's `read()`, and classification degrades to `LLM_ERROR`. Every other outcome (`InterruptedException`, `ExecutionException`, `IOException`, empty, oversize, non-UTF-8) also degrades to `LLM_ERROR`; the read never changes *whether* the job is abandoned. **This is the async `parseResponse` path `WorkerLoop` actually uses** — confirmed at `WorkerLoop.java:352`; the synchronous `chatCompletion`/`RestClient` path (`LlamaClient.java:119-121`) still throws before touching the body and has no caller in `WorkerLoop`. Tests: `LlamaClientNon2xxIndefiniteStallTest` — a **raw `ServerSocket`** that sends `500` headers + `Content-Length: 1000000` and then writes nothing and never closes; asserts <4 s and that no `llama-error-body-read` thread survives. That is a genuinely good test and it settles the one thing a source reading could not (that closing the JDK `HttpResponse` body stream from another thread really does unblock a blocked `read()`) |
| **SOGB-07** | No backend byte survives; plain `contains`, no regex; audit-only | **PASS** | `CONSTRAINT_REJECTION_MARKERS` (`LlamaClient.java:85-86`) is a `static final List<String>`, both entries already lowercase, matched via `lower.contains(marker)` after `toLowerCase(Locale.ROOT)` on a **bounded** (≤8 KB) UTF-8-decoded prefix — **no `Pattern`, no regex, no JSON parse of the error body**, so no ReDoS surface and no unbounded decode. The body string never leaves `classify`: it is not returned, not logged (the only log is a `debug` naming the constant `Duration`), not put in the `LlamaException` message (`"llama-server returned status " + status`, an `int`), and not sent as `detail` — `WorkerLoop.DETAIL_BY_REASON:73-74` holds a fixed Worker-side sentence. Gateway side: `QueueManager.reportFailure:613-621` whitelist-parses and `sanitizeSingleLine(detail, 200)`s regardless. **Inertness independently re-confirmed structurally, not by reading the existing test:** `grep -rn "JobFailureReason" src/main/java/` returns *zero* hits in `RetryManager.java` and `JobStateMachine.java`; the only service-layer reference is `QueueManager`'s audit-string composition, which hands `RetryManager.requeueOrFail` a plain `String`. Tests: `matchedErrorBodyTextNeverLeaksIntoTheExceptionMessage`, `RetryManagerNoJobFailureReasonDependencyTest` |
| **SOGB-08** | Both enums gain the constant; `fromWireValue` only | **PASS** | `worker/…/error/JobFailureReason.java:33` and `gateway/model/enums/JobFailureReason.java:33`, two independent types. `fromWireValue:41-51` iterates `values()` excluding `UNKNOWN`, never `Enum.valueOf`, never throws. `QueueManager:614-618` WARNs on `UNKNOWN` logging **only the length** of the raw value, and still returns `200` |
| **SOGB-09** | Startup assertion states what it did and did not verify | **PASS** | `GatewayProperties.java:215-230`. Message names the property, the value, `MAX_REPETITION_THRESHOLD = 2000`, and says in as many words that it bounds *"only the per-file, per-site cost, not `gateway.structured.max-files-per-chunk × max-findings-per-file`"* — exactly `SOGB-09`'s two-factor caveat. Tests: `ApplicationYamlBootTest` — 201 refuses to start the **real** application context, 200 boots cleanly |
| **SOGB-10** | `summary` trapdoor note | **FAIL** (non-gating) | → `F-SOGB-05` |
| **SOGB-11** | Counter on a closed vocabulary; DEBUG logs lengths only; exposed on `/metrics` | **PASS** | `MetricsCounters.java:37,80-82,110-112` — `ConcurrentHashMap<String, AtomicLong>` keyed only on the two Gateway string literals `"comment"`/`"suggestion"` passed at `StructuredResponseParser.java:356,361`. No file path, project id, backend URL, mode, or model-supplied text can reach the key, so no cardinality explosion (`SOR-21`/`WOR-17` discipline, identical to `structuredValidationFailures{kind}`). The two DEBUG lines (`:357-358`, `:362-363`) log `originalLength`/`cappedLength` **only** — never content, never a prefix. Exposed end to end: `MetricsCounters.structuredFieldTruncatedSnapshot` → `StatisticsService:105` → `MetricsSnapshot` → `AdminController:47` → `MetricsResponse` → `GET /metrics`. That last hop is `SOGB-11`'s stated condition of acceptance (§4.2's "one genuine tension") and it is met. Test: `incrementsTheStructuredFieldTruncatedCounter` |
| **SOGB-12** | Doc corrections land in the same commit | **PARTIAL** | `README.md:267`, `DEPLOYMENT.md:960-961`, and the `SRO-02`/`SRO-27`/`SRO-32`/`SRO-33`/§10/§13.1 amendments in `docs/structured-review-output-architecture.md` are all correctly applied (each verified against the diff, including the §13.1 `REFUTED BY PRODUCTION` marker and the `maxTokens: 12000` correction). Four surfaces missed → `F-SOGB-04` |

---

## 3. `SGB-01..SGB-07` verification (architecture requirements)

- **`SGB-01` — `maxLength` genuinely gone, and nothing re-added under another name. VERIFIED.**
  `ReviewSchemaBuilder.stringSchema:183-187` emits `{"type":"string"}` and nothing else; it lost its
  parameter as specified. Walked **every** emission site: chunk `summary` (`:92`), per-file `summary`
  (`:130`), finding `comment` (`:167`), finding `suggestion` (`:168`) — all four route to that one
  method. `line` is a bare `{"type":"integer"}` (`:155-157`), `severity` a bare `type`+`enum`
  (`:159-165`). `grep -rn "maxLength|minLength|\"pattern\"|maxItems|minItems"` over `src/` and `worker/`
  returns **no** `maxLength`/`minLength`/`pattern`/`minItems` in any schema-construction path — the only
  surviving hits are Java-local variable names, javadoc, and the single intended `maxItems`
  (`:138`). No `format`, no `contentEncoding`, no other keyword that could reintroduce a bounded
  repetition. `SchemaGrammarBudgetRoundTripTest.noNodeAnywhereInAMultiFileSchemaCarriesMaxLengthAndMaxItemsIsExactlyConfigured`
  walks the built tree recursively (not a string `contains`) and asserts zero `maxLength` nodes at any
  depth plus exactly one `maxItems` per file — a correctly-shaped inversion of `T-1.4`.
- **`SGB-02` — VERIFIED.** `maxItems` retained; startup assertion present and well-worded (see
  `SOGB-09`).
- **`SGB-03` — VERIFIED.** Reject → truncate at `StructuredResponseParser:342-367`; the former
  `SCHEMA_MISMATCH` return is gone. `maxItems` overflow remains `SCHEMA_MISMATCH` (`:302-305`),
  unchanged, as `SRO-32`'s amendment requires.
- **`SGB-04` — VERIFIED for ordering.** Truncation happens in the parser, strictly upstream of
  `CommentRenderer`; nothing is fence-wrapped at that point, so `SGB-04`'s third clause ("never applied
  to already-fence-wrapped text") is satisfied by construction. `collapseBacktickRuns` still runs
  *after* the truncation (`CommentRenderer:140`, `:410`) and before any cap, preserving §4.4's structural
  argument that a truncation cannot manufacture a fence. `hasBalancedFences` still runs last, on the
  assembled body, with the `F-SRO-07` fallback re-verification intact (`:167-181`). Two edge cases are
  covered by tests that assert the right thing:
  `truncationLandingInsideABacktickRunStillProducesAFenceBalancedBody` and the 5..140 budget sweep in
  `CommentRendererTest`.
- **`SGB-05` — VERIFIED.** `max-files-per-chunk` untouched at `40`, as specified pending P-3.
- **`SGB-06` — VERIFIED.** See `SOGB-05`/`SOGB-06`/`SOGB-07` above.
- **`SGB-07` — VERIFIED.** `DEPLOYMENT.md`'s recipe now posts the real 1-file and 40-file schemas, keeps
  the negative control, and extends the log check to `Failed to initialize samplers`. The two fixtures
  exist at `src/test/resources/fixtures/structured-output-grammar-budget/schema-1-file.json` and
  `schema-40-files.json` and are pinned against the live builder by
  `ReviewSchemaBuilderTest:165-201`, so the recipe cannot drift.

---

## 4. Non-regression set (threat model §5) — re-verified

| Item | Verdict | Note |
|---|:---:|---|
| `SOR-09` (fence integrity against a truncated prefix) | **PASS** | Collapse-before-cap ordering preserved; assembled-body check runs last; `F-SRO-07` fallback re-verification intact |
| `SOR-11` (un-shortcuttable validation) | **PASS in production code**, **FAIL in the test that guards it** | → `F-SOGB-01` |
| `SOR-21` (closed counter vocabulary) | **PASS** | → `SOGB-11` |
| `SOR-22` / `F-SRO-06` (`ALTERED_CODE_MARKER` on any altered block) | **PASS** | The `SOGT-01` regression is genuinely closed — the upstream flag is OR-ed into the suggestion block's own `altered` decision rather than re-derived |
| `F-SRO-07` (backtick-run collapse in prose, after truncation) | **PASS** | `CommentRenderer:140` |
| `WOR-04` (positional audit discriminator) | **PASS** | `QueueManager:620` `"worker-reported: reason=…; detail=…"` unchanged |
| `WOR-05` (constant `detail`, never an exception message) | **PASS** | `DETAIL_BY_REASON` gains one constant sentence; `reportFailureBestEffort:320` sends `DETAIL_BY_REASON.get(reason)` |
| `WOC-23` (`fromWireValue`, never `Enum.valueOf`) | **PASS** | |
| `WOC-24` (audit-only reason) | **PASS** | Structurally re-confirmed by grep, not just by the existing test |
| `WSR-04` (bounded response read, now on two paths) | **PASS** | Same `BoundedInputStream`, two different bounds, both enforced |
| `SR-21` (`capRawResponseIfNeeded` before parsing) | **PASS** | Untouched |
| `SRO-18` / `SOR-05a` (Jackson tree only) | **PASS** | The diff only **deletes** a `put`; no construction path added. No `StringBuilder`/`String.format`/`+` anywhere in `ReviewSchemaBuilder`'s schema construction — `T-1.2`'s grep property still holds verbatim |
| `F-SRO-03` (`coverageReserveTokens` formula) | **PASS** | Untouched; startup budget check still logs cleanly (`coverageReserveTokens=2670`, observed in the test run) |

---

## 5. House-checklist sweep (log injection, resource leaks, concurrency)

- **Log injection of backend-controlled text (`WSR-10`/`SR-14`/`F02-03` class) — clean.**
  No new `getMessage()` call anywhere in the branch. `grep -rn "getMessage()"` over
  `worker/…/llama/` and the two touched Gateway services returns only the three pre-existing
  *comments* explaining why it is never called. The new WARN/DEBUG lines log an enum name, an exception
  **class** name, a `Duration` constant, and integer lengths — nothing derived from a response body.
- **Resource leaks on the new timeout path — clean.** `readErrorBodyBounded` closes via
  try-with-resources on a `FilterInputStream`, which closes the delegate; the timeout path additionally
  `closeQuietly(body)`s from the calling thread. `LlamaClientNon2xxIndefiniteStallTest` empirically
  asserts no `llama-error-body-read` thread survives the timeout, which is the socket-leak question
  answered by measurement rather than by reading the JDK. One accepted residual: if a future JDK changed
  `HttpResponseInputStream.close()` so it no longer wakes a blocked reader, the leak would be **one
  daemon thread per non-2xx attempt** (bounded by `max-attempts`, one job at a time) — noisy, not fatal,
  and the test would catch the change.
- **Thread safety — clean.** `MetricsCounters` is `ConcurrentHashMap` + `AtomicLong` throughout
  (`computeIfAbsent`/`incrementAndGet`); the new counter follows the same shape. `TextSanitizer`,
  `CommentRenderer`, `ReviewSchemaBuilder` and `StructuredResponseParser` remain stateless singletons
  (`ReviewSchemaBuilder`'s and the parser's `ObjectMapper` fields are used read-only, which is
  thread-safe). `classifyNon2xx` spawns one short-lived thread per failed attempt and shares no mutable
  state with it beyond a `CompletableFuture` — the `ponytail:` comment at `LlamaClient.java:234-236`
  correctly names the "no pooled executor" simplification and its ceiling.
- **Input validation at the trust boundaries — unchanged and intact.** The shape, coverage, key-set,
  severity-enum and `maxItems` checks all remain hard rejections; only the length dimension moved to
  truncate, which is the presentation bound `SGB-03`/§4.2 argued for.
- **Idempotency — intact.** Truncation is a pure deterministic function of `(text, cap)`; re-processing
  the same raw response yields byte-identical output, so a redelivered result cannot publish two
  different bodies.

---

## 6. Test-quality assessment (not just "they are green")

Broadly good — better than a typical fix round. Specifics worth recording:

**Strong:**

- `LlamaClientNon2xxIndefiniteStallTest` — a real raw-socket indefinite stall, asserting both the
  deadline *and* the absence of a lingering reader thread. It correctly identifies (in its own javadoc)
  that the developer's `setBodyDelay(3s)` MockWebServer test proves a weaker property, and it tests the
  stronger one. This is the single most valuable test in the branch.
- `SchemaGrammarBudgetRoundTripTest` — recursive tree walk for `maxLength` at any depth, plus a
  build-a-schema → build-a-conforming-response → run-the-real-parser round trip. Not a string
  `contains` check.
- `ApplicationYamlBootTest` — boots the **real** application context at 200 and 201 rather than calling
  the validator directly.
- `RetryManagerNoJobFailureReasonDependencyTest` — covers fields, constructor params, method params and
  return types. This is the correct shape for an architecture test in this repo.
- `CommentRendererTest`'s 5..140 budget sweep — brute-forced rather than one hand-picked offset, with an
  explicit note about why. It is what makes `F-SOGB-03` a *documented* gap rather than a hidden one:
  the sweep exists and simply does not assert on the marker.

**Weaker:**

- `StructuredResponseParserTest.classOnlyReferencesItsApprovedCollaboratorSet` — see `F-SOGB-01`. Its
  name overstates what it verifies ("references" vs. "declared fields").
- `commentExceedingTheConfiguredCapIsTruncatedAndPublishedNeverSchemaMismatch` asserts
  `doesNotContain("x".repeat(11))` at cap 10 — a negative assertion that also passes if the comment were
  dropped entirely. A positive assertion on the published prefix would pin the behaviour harder.
- `CommentRendererTest`'s sweep asserts encodability and fence parity but never asserts
  `rendered.length() <= maxLength`. (It would legitimately fail at small budgets, because the header is
  never truncated — pre-existing, and worth an explicit `assumeThat`/comment rather than silence.)

---

## 7. Status

- **Closed / verified this round:** `SGB-01`, `SGB-02`, `SGB-03`, `SGB-05`, `SGB-06`, `SGB-07`;
  `SOGB-01`, `SOGB-03`, `SOGB-05`, `SOGB-06`, `SOGB-07`, `SOGB-08`, `SOGB-09`, `SOGB-11`; the
  unconditional half of `SOGB-04`; the entire §5 non-regression set except `SOR-11`'s guard test.
- **Open — fix round:** `F-SOGB-01` (Medium, **must fix**), `F-SOGB-03` (Low), `F-SOGB-04` (Low),
  `F-SOGB-07` (Low, **must fix before merge** — it is a one-command fix), `F-SOGB-05` (Info, `SOGB-10`).
- **Open — maintainer's call:** `F-SOGB-02` (Medium, pre-existing, non-default config), `F-SOGB-06`
  (Info).
- **Deferred by design:** `SOGB-02` (P-0..P-4 empirical probes) — gates backend re-enablement, not the
  merge.

**Counts: Critical 0 | High 0 | Medium 2 | Low 3 | Info 2.**

**Next step per CLAUDE.md: step 6 — `backend-developer` fix round** for `F-SOGB-01` at minimum
(`F-SOGB-03`/`F-SOGB-04`/`F-SOGB-05` are a few lines each and are cheapest to fold into the same
commit), then step 7 final verification. Merging to `master` with every backend still `OFF` remains
correct and unblocked on the production-code side; `SOGB-02`'s probes gate the subsequent
`structured_output_mode` flip, not this merge.

**Doc edit made by this round:** `docs/structured-output-grammar-budget-threat-model.md` §3, requirement
`SOGB-04` — corrected the "…and its own DTO/**enum** set" wording that produced `F-SOGB-01`, marked
`[appsec SAST-round correction, F-SOGB-01]`, per the same inline-correction convention the
pre-implementation round used on the architecture doc. No production code was modified by this round.

---

## 8. Final verification (step 7 — merge gate)

Round: **step 7 of CLAUDE.md's feature workflow**, following a `backend-developer` fix round (`63806d0`,
`f5b4edc`) that closed every finding in §1 above. Scope: `chore/config-consolidation..HEAD` (7 commits,
`af620fd..f5b4edc`), independently re-derived against shipped code — no finding from this section was
accepted on the strength of a prior round's own report.

### Verdict: **PASS.** 0 Critical, 0 High, 0 Medium. Branch is ready to merge into `chore/config-consolidation`.

Four Info-level carry-forwards remain (§8.4), none merge-blocking.

### 8.1 Test suites — independently reproduced

| Module | Command | Result |
|---|---|---|
| Gateway (`.`) | `JAVA_HOME=/c/Users/dmitr/.jdks/corretto-21.0.8 mvn -o test` | **853 run, 0 failures, 0 errors — BUILD SUCCESS** |
| Worker (`worker/`) | same | **163 run, 0 failures, 0 errors — BUILD SUCCESS** |

Matches the fix round's own reported counts exactly. Two temporary probe tests
(`src/test/java/com/review/gateway/service/ZzAppsecProbeTest.java` and one ad-hoc reflection probe) were
written, run, and deleted; `git status` confirmed identical before and after. No production code was
modified by this round.

### 8.2 §1 findings — re-verified closed against current code, not against commit messages

- **`F-SOGB-01` — CLOSED, and stronger than specified.** `StructuredResponseParserTest.java:563-630`: the
  package-prefix leak and the fields-only scan are both gone — the replacement is an exact-`Class<?>`
  allow-list covering fields, constructor parameters, method parameters, and return types, plus explicit
  named `isNotEqualTo(StructuredOutputMode.class)`/`isNotEqualTo(Backend.class)` deny assertions. A
  reflection probe diffing the allow-list against every type actually declared on the class today found
  **zero dead entries** — every permission is load-bearing, which is a stronger property than the SAST
  round asked for (a list that can quietly accumulate unused permissions was part of the original defect's
  shape).

  **New finding V-01 (Info) — the mechanism's ceiling, demonstrated, not hypothesised.** A reflection-based
  allow-list cannot see everything. Five ways to add a `StructuredOutputMode` dependency that this (or any)
  reflection scan would miss were constructed and run against the test's own `assertAllowed` logic — all
  five went undetected: generic erasure (`Set<StructuredOutputMode>` → `Set.class`), a `String`-typed wire
  value, a `boolean` shortcut flag (no signature footprint at all — the shape §4.2's `SOGB-04` names
  explicitly, `structuredConstraintSent`), a branch added to an existing DTO field (`SchemaOptions`) rather
  than the parser's own signature, and a fully-qualified **static** reference with no signature footprint
  at all — which is not theoretical: `StructuredResponseParser.java:184-185` already does exactly this
  shape today, with the sibling enum `FinishReason` (`FinishReason.fromWireValue(...) == FinishReason.LENGTH`).
  That specific use is safe — it fails *closed* to `TRUNCATED`, the opposite of a shortcut — cited only as
  live proof that the blind spot is real, not as a second finding. Not a blocker: no production behaviour
  is wrong, the new test is strictly better than both the deny-list it replaced and the allow-list SAST
  rejected, and the ceiling is inherent to reflection-based testing, not a drafting error. Durable upgrade,
  for a future round: a plain source-text scan of `StructuredResponseParser.java` asserting the absence of
  the literal tokens `StructuredOutputMode`, `structuredConstraintSent`, `Backend` outside the javadoc
  block — the same structural grep this round performed by hand — would catch all five shapes at once.

- **`F-SOGB-02` — CLOSED, at four sites, verified by trace, not by name.** Full-tree grep for
  `.substring(0,` across `src/main/java` and `worker/src/main/java` returns zero remaining bare
  truncations of model- or backend-derived text. `ResultProcessor.java:340`
  (`withUnvalidatedPrefix`, the cited site) and `:398-400` (`capRawResponseIfNeeded`, an extra site the
  developer found on their own) now delegate to `TextSanitizer.truncateSafely`; `CommentParser.java:340-348`
  (`capLength`) got an inline high-surrogate back-off instead, deliberately not delegated — per this
  round's own §1 guidance not to touch that call site's `"..."`-suffix output shape (would regress
  `SRO-54`/`SR-09`). Both back-off implementations were traced character-by-character, not assumed
  equivalent: `TextSanitizer.truncateSafely:142-146` and `CommentParser.capLength:345-347` apply the
  identical `if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))) cut--;` guard to differently-
  derived `cut` values. **Duplication approved** — a shared helper would need to conditionally append a
  suffix depending on caller, which is more surface than the three-token guard it replaces. An end-to-end
  probe (60 non-BMP code points through the full `renderIndexed` path at every `maxCommentLength` from 5 to
  140) produced no lone surrogate at any budget.

- **`F-SOGB-03` — CLOSED for the Gateway-appended marker; re-derived by hand and by test.**
  `CommentRenderer.assembleWithTruncatedProse:456-478` now caps `base` (marker-free) once the budget is
  too small even for the marker, so the marker is genuinely all-or-nothing — hand-derived at this round's
  own repro values (`maxLength` 37 → marker dropped whole; `maxLength` 38 → marker intact whole). The new
  `truncatedProseMarkerIsNeverPartiallySliced` test (parameterized 32..40) is a real trap, not decorative:
  its detector starts at a one-character marker prefix, which the pre-fix output would have tripped.

  **New finding V-02 (Info) — one residual the fix does not cover.** If the *model's own* prose already
  ends with the literal marker text, `prose.endsWith(marker)` peels one copy and the base string still
  carries a second copy, which can then be sliced by the same downstream cap. Confirmed reachable at
  `maxCommentLength` 26..37 with a constructed input. Strictly narrower than the original `F-SOGB-03`
  (needs a small comment-length budget **and** the model echoing a Gateway constant verbatim) and
  non-exploitable — worst case is a cosmetically mangled note, no encoding risk, no injection. The
  single change that would close V-02, the original `F-SOGB-03` class, and a related pre-existing
  over-length-body case (the assembled body can exceed `max-comment-length` at very small budgets because
  the header itself is never truncated) is one startup floor under `gateway.publish.max-comment-length` in
  `GatewayProperties.validateStructuredOnStartup`, alongside the floors already present for
  `max-path-chars`/`max-findings-per-file`. Not present yet; default is `4000`, so nothing is reachable in
  any sane deployment. Non-gating.

- **`F-SOGB-04`/`F-SOGB-07` — CLOSED, values spot-checked against source of truth, not against the diff.**
  All four stale surfaces corrected: `application.yml:112-113` and `GatewayProperties.java:1359,1361` no
  longer claim `-> maxLength`; `TextSanitizer.java:124-128`'s javadoc now correctly scopes itself to this
  class's own `capLength` and records that `CommentRenderer.capLength` delegates here as of `2ae6d61`
  (re-checked: `TextSanitizer.capLength:105-111` genuinely is still a bare substring, so the residual
  claim it makes is accurate — see `V-03` below); `DEPLOYMENT.md:963` now reads `12000`, cross-checked
  directly against `worker/src/main/resources/prompts/v3.yml:14` (`maxTokens: 12000`) rather than trusted.
  `git ls-files` confirms `docs/structured-output-grammar-budget-architecture.md`,
  `docs/structured-output-grammar-budget-threat-model.md`, and this SAST report are now all tracked —
  every citation-by-path from `README`/`DEPLOYMENT`/`ReviewSchemaBuilder`/both `JobFailureReason` enums
  will resolve post-merge.

- **`F-SOGB-05`/`F-SOGB-06`** — `F-SOGB-05`: both trapdoor comments present
  (`ResultProcessor.java:411-413`, `StructuredResponseParser.java:297-299`), naming `truncateSafely` as the
  routing point if `SRO-26` is ever wired. `F-SOGB-06`: no action taken, correct per this report's own
  original "No action required" verdict.

### 8.3 Threat-model §5 release gate — satisfied end to end (not just this round's own findings)

| Gate | Verdict |
|---|---|
| `SOGB-01` (truncation visible) | **MET.** Flag threaded through the 8-arg `renderIndexed` into the suggestion block's `altered` decision and the prose marker. `V-02` above is a cosmetic residual at misconfigured budgets only. |
| `SOGB-02` (P-0..P-4 probes) | **Correctly deferred** — gates backend re-enablement, not this merge. No probe run, no `structured_output_mode` value touched, no `## Grammar probe results` section added to `DEPLOYMENT.md` — the right state to be in. |
| `SOGB-03` (code-point boundaries) | **MET globally now**, not just at the one site this report originally checked — see §8.2. |
| `SOGB-04` (unconditional; `SOR-11` guard) | **MET.** Unconditional half re-confirmed by tracing `validateFindingAndCollect`; guard-test half now closed, ceiling recorded as `V-01`. |
| `SOGB-05`/`06`/`07`/`08` (Worker error-body bounds, inertness, enum) | **MET, unchanged** — neither fix-round commit touches `worker/` (confirmed via `git show --stat`); independently re-read the byte bound (existing `BoundedInputStream`, 8 KB constant), the time bound (daemon thread + 2 s join, independent of `request-timeout-sec`, on the async path `WorkerLoop.java:352` actually calls), and confirmed every failure mode (timeout, IOException, RuntimeException, oversize, non-UTF-8, empty, and even an `Error` on the reader thread) degrades to `LLM_ERROR` rather than hanging or misclassifying. |
| `SOGB-09`/`11` (startup wording; closed counter vocabulary) | **MET, unchanged.** |
| `SOGB-10`/`12` (trapdoor note; doc corrections) | **NOW MET** — were this report's own `FAIL`/`PARTIAL`. |
| §5 non-regression set (`SOR-09/11/21/22`, `F-SRO-03/06/07`, `WOR-04/05`, `WOC-23/24`, `WSR-04`, `SR-21`, `SRO-18`/`SOR-05a`) | **All PASS.** `SOR-11` was the sole `FAIL` in §4 above and is the one item this fix round changed in that set. |
| Accepted residuals `SOGB-INH-1/2/3`, `SOR-INH-2` | Unchanged; conditions of acceptance intact. |

Advisory, non-gating, not done: the two Semgrep rules §5 suggests adding "while this is in flight" (flag
`substring(0,` on model-derived text outside the approved helper; flag response-body-derived arguments in
`worker/.../llama`) were not added to CI. Worth doing as the natural companion to `V-01`'s suggested
source-scan test, but the release gate itself does not require them.

### 8.4 Carry-forward register — all Info, none gating

| # | Where | Why it is not a blocker | Cheapest durable fix |
|---|---|---|---|
| **V-01** | `StructuredResponseParserTest.java:563-630` | Test-only ceiling inherent to reflection-based scanning; strictly stronger than what it replaced; no wrong production behaviour today | 5-line source-text scan of `StructuredResponseParser.java` asserting absence of `StructuredOutputMode`/`structuredConstraintSent`/`Backend` outside the javadoc |
| **V-02** | `CommentRenderer.java:470-473` | Needs `max-comment-length < ~50` **and** the model echoing a Gateway constant verbatim; cosmetic only, no encoding/injection risk | Startup floor on `gateway.publish.max-comment-length` — also closes a related pre-existing case where the assembled body can exceed the configured length at very small budgets because the header is never truncated |
| **V-03** | `TextSanitizer.java:105-111` (`capLength`) | Last remaining non-surrogate-safe cut in the Gateway; reached only via `sanitizeSingleLine(detail, 200)` on Worker-supplied `detail` text on its way to `review_jobs.last_error`; pre-existing, explicitly scoped out by `SOGT-02`; requires an authenticated Worker sending deliberately malformed UTF-16 | The same three-token high-surrogate back-off `CommentParser.capLength` just received |
| **V-04** | `application.yml:118`, on the **merge target** `chore/config-consolidation`, not this branch | `gateway.structured.answer-reserve: 8000` is a dead key — `chore/config-consolidation`'s own commit `23ca526` deleted the `Structured.answerReserve` field it used to bind to (`GatewayProperties.java:1371-1376`), and Spring's default `ignoreUnknownFields=true` swallows it silently. An operator tuning v3's answer budget by editing this line would observe zero effect, while its own comment claims it overrides `gateway.diff.answer-reserve`. This is the exact operator-facing-lie class `SOGB-12`/`F-SOGB-04` exist to prevent, introduced by the target branch, one line below the two lines this branch's own fix round corrected. Does not block this merge; `chore/config-consolidation` should not reach `master` carrying it | Delete the dead key, or restore the field it was meant to bind to |

### 8.5 Working-tree hygiene — clean

Unrelated pre-existing WIP is still uncommitted and untouched by all 7 branch commits: `docker-compose.yml`
(`LLAMA_MAX_TOKENS` on both workers), `worker/Dockerfile` (`LLAMA_MAX_TOKENS` `4096`→`12000`), and
`application.yml:190` (`allowed-prompt-versions` `v1,v2,v3`→`v1,v2`). `f5b4edc` did touch `application.yml`,
but a line-level diff confirms it touched only lines 112-113 (the `F-SOGB-04` doc fix) — the unrelated
`:190` hunk was not swept in.

### 8.6 Merge target — confirmed current

`chore/config-consolidation` (`e01715e`) is a direct ancestor of `HEAD`; `HEAD` is exactly these 7 commits
ahead of it; `chore/config-consolidation` has not itself merged into `master` (still ahead of it) — the
target is current, not stale, and this is a fast-forward.

**Merge target confirmed: `fix/structured-output-grammar-budget` → `chore/config-consolidation`.**

### 8.7 Status

**Counts this round: Critical 0 | High 0 | Medium 0 | Low 0 | Info 4 (`V-01..V-04`, none gating).**

No blocker. **No further `backend-developer` round is required for this branch.** `V-01`/`V-02`/`V-03` are
optional hardening a maintainer can schedule at will; `V-04` belongs to `chore/config-consolidation`'s own
path to `master`, not to this merge. `fix/structured-output-grammar-budget` is **ready to merge**.
