# AppSec SAST Report — feature/diff-position-anchoring (Diff Position Anchoring)

Scope: `master..feature/diff-position-anchoring`, HEAD `7f35263`, tree clean, no production code modified
by me. 7 commits (`44560ee` threat model → `9a6ace4` resolver → `1d0a49f` client → `b3b6a65` publisher
wiring → `9b6d5f4` metrics → `4fbedd7` docs → `7f35263` QA coverage), 27 files, +2656/−69. In scope: the
new `service/DiffPositionResolver` (+ its nested `PathLine`/`ResolvedLine` records),
`service/dto/DiffPosition`, `service/dto/DiffRefs`, the `GitLabClient.fetchDiffRefs` +
positioned-`postDiscussion` additions in `GitLabClientImpl` (incl. the `DiscussionRequest`/
`PositionRequest`/`MergeRequestResponse`/`DiffRefsResponse` wire records), `GitLabPublisher`'s
`buildPositionContext`/`collectWantedKeys`/`resolvePositionFor`/`normalizeFullSha`,
`PublishRetryService`'s new per-review guard, `GatewayProperties.Publish.positionAnchoringEnabled` +
`application.yml`, the four new `MetricsCounters` counters and their `StatisticsService`/`MetricsSnapshot`/
`MetricsResponse`/`AdminController` wiring, and the README/DEPLOYMENT/architecture doc updates.

Method: verification of each of my own pre-implementation MUSTs (**DPR-01…DPR-11**) and SHOULDs
(**DPR-12…DPR-15**) from `docs/diff-position-anchoring-threat-model.md` §5 against the code that was
actually built — not the design doc, and not the developer's summary — including reading each specified
test's *assertions* rather than trusting its method name; plus a general SAST pass (injection / totality /
resource exhaustion / deserialization / access control / secret & payload leakage / dependency delta), plus
the two purpose-built Semgrep rules §7 of the threat model asked for, discriminator-checked against a
deliberately-poisoned copy of the class before being believed.

**Suite (run by me, JDK 26 / Maven 3.9.14 — CLAUDE.md's toolchain section is stale, the installed pair is
`C:\Develop\jdk-26` / `C:\Develop\apache-maven-3.9.14`):** `mvn -o test` →
**`Tests run: 692, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.** Matches QA's independently-reported
number exactly. The pre-existing `BackendDispatchFailFastEndToEndTest` flake (inherited from the
already-merged worker-observability feature) did **not** reproduce in my run and is out of scope regardless.

**Scanners (run by me; Docker is available on this host):**
- `semgrep` with the exact SR-23 gate config (`p/java` + `p/sql-injection` + `p/secrets`, `--severity=ERROR`)
  over `src/main/java` → **0 findings**, 56 rules on 122 files.
- Four **custom** rules written for this feature (threat model §7): slf4j-call-with-a-path/SHA-accessor
  argument, slf4j-call-with-a-`Map`/`Collection` argument, `Integer.parseInt|valueOf`/`Long.parseLong|valueOf`/
  `Integer.decode` in `DiffPositionResolver`, and `throw` in `DiffPositionResolver`, scoped to
  `DiffPositionResolver`/`GitLabPublisher`/`GitLabClientImpl`/`PublishRetryService` → **0 findings**.
  **Discriminator-checked**: the same four rules fire 4/4 on a poisoned scratch copy carrying one violation
  each, so the clean result on the real code is evidence, not a mis-scoped rule set. (Recommendation
  F-DP-08: commit these rules to the repo so DPR-04/DPR-02 get a standing gate.)
- `gitleaks` full history (`.gitleaks.toml`, 118 commits, 2.84 MB) → **no leaks found**.
- SCA: `git diff master...HEAD -- pom.xml worker/pom.xml` is **empty** — zero dependency delta, so the
  verified-clean baseline from the worker-observability round carries over unchanged. The feature adds no
  new library (`HtmlUtils`, `BufferedReader`, Jackson, `RestClient` were all already present, as predicted).

## Verdict: **SIGN OFF — clear to merge to `master`.**

Severity counts: Critical 0 · High 0 · Medium 0 · **Low 3** · Info 6. **No blocking findings.**

All eleven blocking MUSTs (DPR-01…DPR-11) are **genuinely met in the shipped code**, including the two the
brief asked me to re-scrutinise personally: DPR-04 (verified by my own grep of *every* `log.` call in the
four touched classes plus the custom Semgrep rule — no path, SHA or diff fragment reaches any log statement
at any level; `DiffPositionResolver` has no `Logger` field at all) and DPR-03 (verified by reading the
actual `content().json(..., true)` **strict** assertions, which fail on an extra or missing key and
therefore genuinely pin both the `{"body":"..."}`-only fallback shape and the exact positioned field set).
DPT-01 — the one High in the pre-implementation model — is closed on **both** required halves, each with a
test that actually forces the throw rather than asserting around it.

The three Low findings are all *observability/robustness* gaps in code this feature already owns, none of
them exploitable and none of them changing the "always falls back to a plain note, never fails a Review"
invariant. **F-DP-02 and F-DP-03 are worth ~20 lines total and I'd fold them into one short follow-up pass
before or immediately after merge — but I am not gating the merge on them.**

The two deferred SHOULDs are both **accepted**: DPR-14's deferral is exactly what my own §4.1 authorised,
and DPR-13's deferral is reasonable but requires DPR-16's acceptance rationale to be restated honestly
(F-DP-09).

---

## Findings

| # | Severity | CWE / OWASP | Where (file:line) | Description | Remediation |
|---|----------|-------------|-------------------|-------------|-------------|
| **F-DP-01** | Low (CVSS:3.1 `AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:L/A:N` = 3.1) | CWE-20 / CWE-1286, A04:2021 | `DiffPositionResolver.java:120-136` (the unconditional `--- `/`+++ ` branches) vs. `DiffChunker.java` `Section.addLine`'s `firstHunkLineIndex < 0` guard (CSR-11) | **`--- `/`+++ ` are treated as file headers even *inside* a hunk, so ordinary diff content silently ends position resolution.** The class javadoc says it "mirrors `DiffChunker`'s single `BufferedReader` line-scan style", but it does **not** mirror CSR-11's confinement of path extraction to the pre-`@@` header region. A **removed** line whose content starts with `-- ` (SQL/Lua/Haskell comments, `-- +goose Up` migration pragmas, ASCII rules) serialises into the diff as `--- <text>`; an **added** line starting with `++ ` serialises as `+++ <text>`. Either is matched by `line.startsWith("--- ")`/`"+++ "`, which resets `state.inHunk = false` and overwrites `oldPathNormalized`/`newPathNormalized` with a garbage path — killing anchoring for the remainder of that hunk, registering a phantom key in the ambiguity index (which can then *drop* a legitimate path), and potentially attaching a wrong `old_path` to a later hunk's positions. **Not a mis-anchor risk, verified structurally**: `emitIfWanted` (`:185`, `:194`) uses the *same* `state.newPathNormalized`/`newLineInt` as both the map key and the emitted `new_path`/`new_line`, so the resolver can never anchor a comment naming `(P, L)` to anything other than `P:L`; only `old_path`/`old_line` are "extra" data, and a wrong pair is rejected by GitLab's server-side position validation → DPR-08's fallback → plain note. Net effect is silent functional degradation plus, in the poisoned-`old_path` case, up to 2× POSTs for that comment (`positionRejectedByGitLab` climbs, which is the counter doing its job). MR-author-triggerable but requires no privilege beyond committing to their own branch, and yields nothing but a lost anchor. | Track the hunk's declared line budget from `@@ -a,b +c,d @@` (parse the optional `,b`/`,d` counts with the same bounded `parseLeadingNumber`) and only accept `--- `/`+++ ` as a header once the budget is exhausted — that also keeps prefix-less multi-file unified diffs working, which a naive `&& !state.inHunk` guard would break (the `ambiguousDiffDerivedPathsAreDroppedFromTheIndexEntirely` test's own `--- ./x.java` second file arrives while `inHunk` is still true). Add a regression case: a hunk containing a removed `-- comment` line, asserting a later line of the *same* hunk still resolves. |
| **F-DP-02** | Low | CWE-778 / CWE-223, A09:2021 | `GitLabPublisher.java:181-185` (head_sha mismatch) and `:192-195` (`resolved.isEmpty()`) vs. `:212-221` (`resolvePositionFor`) and `README.md:6.7` | **DPR-12's counters are blind in exactly the two failure modes DPR-12 exists to make visible.** `buildPositionContext` returns `null` — moving **no counter at all** — both when `diff_refs.head_sha != review.headSha` and when the resolver resolved *nothing*; `resolvePositionFor`'s `incrementPositionsUnresolved()` is then unreachable for that Review because `positionContext == null` short-circuits at `:213`. Consequence: "anchoring resolves nothing for any Review any more" (a diff-format change, a `review_inputs` retention purge, an upstream `CommentParser` change to `file_path`) and "every MR has moved on before we publish" both present on `GET /metrics` as `positionsAnchored=0, positionsUnresolved=0, diffRefsUnavailable=0` — **byte-identical to the healthy "no comment had a line number" steady state.** `positionsUnresolved` only ever moves when at least one *other* comment in the same Review resolved, i.e. it counts partial misses and is silent on total ones. That inverts DPR-12's own stated rationale ("without a counter, 'anchoring stopped working three weeks ago' is undiscoverable"). Sub-note: `README.md` §6.7 glosses `diffRefsUnavailable` as covering "stale MR state", which a reader will reasonably take to include the head_sha mismatch — it does not. | Two lines plus a doc touch: (a) delete the `if (resolved.isEmpty()) return null;` early return and let the per-comment path count each miss (the empty-map context costs one `HashMap` lookup per comment and nothing else — `resolvePositionFor` already handles `resolved == null`); (b) increment a counter on the head_sha-mismatch branch — either reuse `diffRefsUnavailable` (and keep the README wording) or add a fifth `positionsStaleHeadSha`, which is more diagnosable and matches DPT-05's "silently disabled fleet-wide with no diagnosable signal" concern. Tighten the README sentence either way. |
| **F-DP-03** | Low | CWE-1188 / CWE-710, A05:2021 | `GatewayPropertiesApplicationYamlBindingTest.java:50-91` (not extended) vs. `application.yml:70` and `GatewayProperties.java:633-640` | **DPR-10 holds today, but has no automated drift guard — in a repo where this exact drift class has now shipped three times.** Manually verified in agreement: the Java field is `private boolean positionAnchoringEnabled = true;` and `application.yml:70` is `position-anchoring-enabled: ${POSITION_ANCHORING_ENABLED:true}` (env-overridable placeholder, as DPR-10 required; grep confirms no hard-coded non-placeholder value anywhere). But the *specified* test — "an assertion that the bound property value under the default profile equals the Java field default" — was not written. `AdminControllerTest.metricsExposesDiffPositionAnchoringCounters`'s `jsonPath("$.positionAnchoringEnabled").value(true)` does **not** cover it: as `GatewayPropertiesApplicationYamlBindingTest`'s own javadoc documents at length, `src/test/resources/application.yml` shadows `src/main/resources/application.yml` on the test classpath, so **no `@SpringBootTest` in this module ever binds the shipped config file** — that assertion is reading the Java field default and would stay green if `application.yml` said `false`. The purpose-built harness for precisely this (bind the shipped yml by `FileSystemResource` path) exists and binds only `gateway.backend`. Precedent: F-PM-02 (Java/yml default drift), F-DC-04 (`application.yml` silently won over a corrected Java default), WOC-16 (`read-timeout` documented at 10s, shipped at 5s) — all three shipped, all three for want of this test. | Extend `GatewayPropertiesApplicationYamlBindingTest` with a `bindShippedPublishConfig()` twin of `bindShippedBackendConfig()` (`binder.bind("gateway.publish", …)`) and assert `isPositionAnchoringEnabled()` is `true`, i.e. equal to `new GatewayProperties().getPublish().isPositionAnchoringEnabled()`. ~8 lines, reusing the existing loader. |
| F-DP-04 | Info | CWE-20 | `CommentParser.java:239` (`neutralizeMentions` inside `sanitizeFilePath`) → `GitLabPublisher.java:206`, `:216` | **The DPR-05 round trip is lossy for any real path containing an `@`-word, so those comments silently never anchor.** `sanitizeFilePath` runs `neutralizeMentions` (`MENTION_PATTERN = @(\w[\w.-]*)` → `"@" + U+200B + group(1)`) *before* `htmlEscape`, and `HtmlUtils.htmlUnescape` does not reverse a ZWSP. So `node_modules/@angular/core/index.d.ts` is persisted as `node_modules/@\u200Bangular/...` and the rebuilt lookup key never equals the diff-derived `node_modules/@angular/...`. Affects npm scoped packages, TypeScript `@types`, Next.js parallel routes (`app/@modal/...`) — i.e. the JS/TS ecosystem broadly. **Fail-safe** (unresolvable ⇒ plain note, exactly today's behavior) and no security impact; the injectivity argument in threat-model §4.3 is unaffected because the loss is one-directional and never widens the match set. Recorded because it is a silent functional hole that will read as "anchoring is flaky" in a JS shop. | One line, key-only and never transmitted or logged: strip `\u200B` from the unescaped key in `collectWantedKeys`/`resolvePositionFor` (a real diff-derived path can never legitimately contain a ZWSP, so this cannot widen the match set). Better still, factor the two identical key-building expressions into one private `lookupKeyFor(ReviewComment)` so the unescape-exactly-once rule has a single home. |
| F-DP-05 | Info | CWE-20 / CWE-117 | `DiffPositionResolver.java:253-263` (`extractPath`) vs. threat model §4.4 / DPR-04's `reason=path-too-long len=<n>` branch | **DPR-02's "reject-don't-repair a diff-derived path at >1024 chars / blank / control-character-bearing" was implemented only for the blank and `/dev/null` cases.** There is no length cap and no Cc/Cf rejection, and consequently DPR-04's specified `reason=path-too-long len=<n>` log branch does not exist. **Verified low-impact, and I am not asking for a code change**: the LLM-side key is already capped at 1024 by `CommentParser.capLength` with a `... [truncated]` suffix that can never equal a real diff path, so an over-long diff path is structurally unmatchable and can never reach a wire position; a control-character-bearing path *is* matchable, but is never logged (F-DP-08 / DPR-04 verified), is `\uXXXX`-escaped by Jackson into well-formed JSON, and is validated by GitLab against its own diff before anything renders. The threat-model bullet and the implementation simply disagree; this report reconciles them in the implementation's favour. | No code change required. If a defensive cap is added later for symmetry, it must be a **reject** (return `null` from `extractPath`), never a truncation — a truncated path that accidentally matches is strictly worse than no anchor. |
| F-DP-06 | Info | — (requirement/test reconciliation) | `GitLabClientImplTest.java:132-141` (the in-test rationale comment) | **DPR-03's specified "`default-property-inclusion: always` does not reintroduce `"position": null`" test was deliberately not written — and the developer's rationale is correct.** Verified independently: (a) Jackson's per-class `@JsonInclude` annotation takes precedence over `ObjectMapper.setDefaultPropertyInclusion`, which is what `spring.jackson.default-property-inclusion` drives, so the annotation genuinely closes the hazard rather than merely coinciding with it; (b) grep confirms **no** `spring.jackson.*` key exists in either `src/main/resources/application.yml` or `src/test/resources/application.yml`; (c) the two existing assertions are `content().json(…, true)` — **strict** JSONAssert, which fails on an *unexpected* key including one whose value is `null` — and they run through the real `RestClient` message converter, so removing `@JsonInclude` from `DiscussionRequest` or `PositionRequest` breaks them immediately. The regression coverage DPR-03 wanted therefore exists, in a less fragile form than the one specified. **Accepted as satisfied.** | None. |
| F-DP-07 | Info | — (test-coverage gap) | `GitLabPublisherTest.java:270-288` vs. DPR-11's specified test | **DPR-11's "exactly 0 `fetchDiffRefs` calls for a Review whose comments all lack line numbers" arm is untested.** What *is* tested and does assert correctly: the flag-off arm (`verify(gitLabClient, never()).fetchDiffRefs(any(), any())`) and an exactly-once arm (`verify(gitLabClient).fetchDiffRefs(projectId, mrId)`, Mockito's implicit `times(1)`). The "no anchorable comment ⇒ no I/O at all" arm — the one that actually matters for DPT-12's cost argument, since it is the common case — is only verified by code reading: `collectWantedKeys` (`:199-210`) skips any comment with a null `filePath` **or** null `lineNumber`, and `buildPositionContext` returns before the client call on an empty set (`:161-164`). The `DiffPositionAnchoringEndToEndTest` case with 4 anchorable comments stubs `fetchDiffRefs` but never asserts its call count. | Two lines in `GitLabPublisherTest`: persist a Review whose only comments have `filePath == null`/`lineNumber == null` and assert `never()).fetchDiffRefs(...)`; and add `Mockito.verify(gitLabClient, times(1)).fetchDiffRefs(...)` to `multiFileMultiHunkDiffAnchorsResolvableCommentsAndFallsBackForTheRest`, which already has 4 anchorable comments in one Review. |
| F-DP-08 | Info | — (CI gate coverage) | `.github/workflows/security-gate.yml`; threat model §7 | **DPR-04's specified automated grep/architecture test and the ANSI/bidi runtime log-capture test were not written**, and the two Semgrep rules §7 asked for were not added to the gate. DPR-04 itself is **verified met** — by my own exhaustive read of all 17 `log.` statements in the four touched classes (every argument is a `reviewId`/`commentId`/`projectId`/`mergeRequestId`/`ReviewStatus`/`getClass().getSimpleName()`/count; the only `getMessage()` is `GitLabPublisher:135` on a `GitLabPublishException` whose three construction sites all carry fixed string literals, verified) and by the four custom Semgrep rules described above (0 findings, discriminator-checked). But that verification is a point-in-time review, not a standing control, and the repo *does* already have log-capture harnesses to copy (`BackendHealthCheckerStuckQueuedJobsTest`, `worker/.../WorkerObservabilityLoggingContentTest`). | Commit the four rules (available in this round's scratch work; trivially reconstructible from the descriptions above) under a repo-local `.semgrep/` directory and add `--config .semgrep/` to the `semgrep` job in `security-gate.yml`. Optionally add one `ListAppender` test in the `WorkerObservabilityLoggingContentTest` style asserting that publishing a Review whose `file_path` carries `\u001b[31m` against a diff whose path carries U+202E emits no log record at level ≤ INFO containing either. |
| F-DP-09 | Info | CWE-1427 (LLM01) / CWE-451, A03:2021 | threat model DPR-13 / DPR-16(i); `src/main` grep: still no banner constant | **DPR-13 (AI-generated-content banner) deferred — deferral accepted, but DPR-16's acceptance rationale must be restated.** The deferral itself is reasonable and I am not blocking on it: DPR-13 is a SHOULD, it has been open since T-06/SR-08 two features ago, it is not specific to this feature, and prefixing *every* published comment body is a product decision that belongs to the user/architect, not to an AppSec sign-off on an anchoring feature. (The developer's stated cost — rewriting byte-exact body assertions in `GitLabPublisherTest` — is real but small, ~8 `eq("…")` matchers; it is the product question, not the test churn, that justifies deferring.) **What must not ride along silently** is that DPR-16(i) accepts the LLM's selection-oracle residual *"compensated by DPR-13's banner"* — and that compensating control does not exist. Restated honestly: **the residual is accepted on the strength of GitLab's server-side position validation alone** (which bounds the reachable set to real lines of the same MR's own diff — re-confirmed this round, both by the `positionRejectedByGitLab`/400-fallback design and by F-DP-01's structural key==value analysis), with no banner and no visual disclaimer, while the native diff-thread UI does lend an injected finding more apparent authority than the pre-feature top-level note. | Raise DPR-13 to the user/architect as its **own** tracked item with its own decision, rather than as a line item on a third consecutive feature's SHOULD list. Not a merge gate. |

---

## DPR-by-DPR verification (against the shipped code, not the design doc)

### Blocking MUSTs

| DPR | Verdict | Evidence |
|-----|---------|----------|
| **DPR-01** Position resolution can never propagate an exception (both halves) | **PASS** | **(a)** `GitLabPublisher.java:119-125`: `try { positionContext = buildPositionContext(review, unpublished); } catch (RuntimeException e) { log.warn("position context unavailable: {}", e.getClass().getSimpleName()); positionContext = null; }` — blanket `RuntimeException`, **class name only**, no `e.getMessage()` (WOR-05/F02-03 respected), and the call genuinely sits *before* the per-comment loop, i.e. it is guarding the right spot. `buildPositionContext` deliberately does **not** repeat the guard internally (its javadoc says so), which is the right call — one net, at the single call site, is exactly what §6 point 1 asked for over "enumeration of handled cases". **(b)** `PublishRetryService.java:50-59`: per-candidate `try`/`catch (RuntimeException)` inside the loop, logging `review.getId()` + `e.getClass().getSimpleName()` only, then continuing. **Tests read, not name-checked:** `GitLabPublisherTest.positionResolverThrowingStillPublishesEveryCommentAsAPlainNoteAndReachesPublished` stubs a `DiffPositionResolver` mock that throws **unconditionally** (`when(...).thenThrow(new RuntimeException(...))`) and asserts `PublishOutcome.PUBLISHED` **and** `postDiscussion(..., isNull())` — so it proves both "does not propagate" and "actually falls back to a plain note", not just the former. `PublishRetryServiceTest.aReviewThatThrowsDuringPublishDoesNotBlockTheRemainingCandidatesInThePass` puts the throwing Review **first** in the `createdAt ASC` list and asserts `publishedCount == 2` **plus** `verify(...)` on all three ids — so it fails if the loop aborts, and would also fail if the guard swallowed the wrong reviews. `DiffPositionAnchoringEndToEndTest.poisonedDiffAtTheHeadOfThePassDoesNotBlockLaterCleanReviews` runs the same shape through the **real** resolver, real `PublishRetryService` and a real Postgres with the literal `@@ -99999999999999999999,1 +1,1 @@` header, asserting all three Reviews reach `PUBLISHED`. |
| **DPR-02** `DiffPositionResolver` is a total function, no `throw`, bounded non-throwing number parse | **PASS** | No `throw` statement in the class — verified three ways: grep (`throw` appears only in javadoc prose at `:22,:23,:26,:197,:225,:282,:283`), the custom Semgrep rule `dp-no-throw-in-resolver` (0 findings, discriminator-checked), and reading. No `Integer.parseInt`/`valueOf`/`Long.parseLong` — same three ways (`dp-no-integer-parse-in-resolver`, 0 findings). `parseLeadingNumber` (`:228-246`) is a bounded digit scan capped at `MAX_HUNK_NUMBER_DIGITS = 9` → max value 999,999,999, returning `-1` for "no digits" and for "too many digits" alike; `parseHunkHeader` (`:209-221`) then rejects the whole header, so a 21-digit header is *skipped*, not repaired and not thrown on. Line counters are `Long` (`:141-142`, `advance` at `:205-207`) and `toLineNumber` (`:198-203`) returns `null` — not an exception, not a wrap — for anything `< 1` or `> Integer.MAX_VALUE`, so `int` overflow is structurally unreachable. Every `substring` is bounds-safe by construction (`line.substring(4)` guarded by the matching `startsWith` of length 4; `rest.substring(0, tab)` guarded by `tab >= 0`). Degenerate inputs short-circuit at `:95-97` before any allocation. Even the unreachable `IOException` from `StringReader` is swallowed rather than wrapped (`:288-297`) — pedantic, and correct given the contract is "no throw statement anywhere". **Test read:** `DiffPositionResolverTest` carries **13** adversarial cases against DPR-02's "≥12" (21-digit header, bare `@@`, `@@ -`, `@@ - + @@`, header with no `+`, `+++ ` with no path, a real 100,000-line hunk, a computed lone surrogate, a NUL byte, `\r`-only line endings, a single 195,000-char line, an unexpected in-hunk marker, plus the four null/empty/degenerate cases) — each asserting `doesNotThrowAnyException()` **and**, in most cases, the specific resulting map, which is the difference between "didn't blow up" and "behaved correctly". `Map.copyOf` at `:111` is NPE-safe here since neither key nor value can be null. Thread safety: all scan state is method-local (`State` constructed inside `resolve`), so the singleton `@Service` is safe under the concurrent publish/scheduler paths. |
| **DPR-03** Wire contract asserted on serialized bytes; no `/dev/null`, no half-filled position, no `"position": null` | **PASS** | Three independent layers, all verified. **Serialization:** `@JsonInclude(NON_NULL)` on **both** `DiscussionRequest` (`:359-361`) and `PositionRequest` (`:368-378`) — the annotation DPT-02 said was "one annotation deep" is present on both, and `@JsonProperty` pins every snake_case name. **Choke point:** `toPositionRequest` (`:132-147`) refuses to build a position unless *every* invariant holds — `null` position, blank-or-`/dev/null` `oldPath`/`newPath`, `null` `newLine`, or any blank SHA all return `null` ⇒ no `position` key at all. This is a genuine second line of defense, independent of the resolver. **Source:** `DiffPositionResolver.emitIfWanted:189-194` applies GitLab's added-file convention at the source (`extractPath` turns `/dev/null` into `null`; `oldPath` then falls back to `newPath`), so `/dev/null` can never even reach the client. **Test assertions read, not names:** all three wire tests use `content().json(…, **true**)` — strict JSONAssert, which fails on an *unexpected* key (including a `null`-valued one) and on a missing one. `fallbackBodyWithNoPositionSerializesToExactlyBodyNoPositionKey` pins exactly `{"body":"plain note"}`; `positionedBodyContainsExactlyTheLegalFieldsForAnAddedLine` pins the 7-field added-line shape with **no** `old_line` key; `positionedBodyContainsBothLineNumbersForAContextLine` pins the 8-field context shape. **"Confirm once by hand against the target GitLab version" — done this round**: GitLab's own discussions API reference states `position[old_path]` and `position[new_path]` are both *required* for `position_type: text`, and "To create a thread on an added line …, use `position[new_line]` and don't include `position[old_line]`", with both paths referencing the same file for newly added content. That is exactly what is implemented, including the rename case (`old_path` = the real pre-rename path, verified end-to-end by `DiffPositionAnchoringEndToEndTest`'s `old/C.txt → new/C.txt` assertion). See **F-DP-06** for the one specified test that was intentionally omitted, and why that omission is accepted. |
| **DPR-04** No path / SHA / diff fragment in any log in the new code paths | **PASS** (re-scrutinised personally, as asked) | I enumerated **every** `log.` statement in `DiffPositionResolver`, `GitLabPublisher`, `GitLabClientImpl` and `PublishRetryService` — 17 in total — and read each argument list. `DiffPositionResolver` has **no `Logger` field at all** (grep for `log\.`/`Logger`/`slf4j` → nothing), which is the strongest possible form of the requirement. Every other argument in the four classes is one of: `reviewId`, `commentId`, `projectId`, `mergeRequestId`, a `ReviewStatus` enum, `e.getClass().getSimpleName()`, or an integer count. The only `getMessage()` is `GitLabPublisher:135` on a caught `GitLabPublishException`, and all three construction sites of that exception (`GitLabClientImpl:97`, `:105`, `:118`) pass **fixed string literals** — no path, no body, no GitLab response text. The new 400-retry log (`GitLabClientImpl:88-89`) carries `projectId`/`mergeRequestId` only; the two `buildPositionContext` DEBUG lines (`GitLabPublisher:170`, `:182`) carry `review.getId()` and fixed reason prose, satisfying DPR-06's "diagnosable, not invisible" requirement without echoing the SHA it is complaining about. No `Map`/`Collection`/`PositionContext`/`DiffPosition`/`DiffRefs`/`PathLine`/`ResolvedLine` is ever passed to a placeholder (the WOR-17 trap) — confirmed by rule `dp-slf4j-map-or-collection-arg`, 0 findings. See **F-DP-05** (the specified `reason=path-too-long` branch does not exist because the reject it would report was not implemented — assessed and accepted) and **F-DP-08** (the specified automated test does not exist; verification here is manual + Semgrep). |
| **DPR-05** Asymmetric normalization, unescape exactly once, ambiguity-drop, exact match only | **PASS** | `normalizeDiffPath` (`:266-271`) strips exactly one leading `a/`, `b/` or `./` and is called **only** from the two diff-header branches (`:122`, `:131`) — the caller-supplied `wanted` keys are never touched by it (grep-verified: no other call site). `HtmlUtils.htmlUnescape` appears exactly twice in `src/main` (`GitLabPublisher:206`, `:216`), both on `comment.getFilePath()`, both once, and its result is used only as a map key — never transmitted (the wire values come from `ResolvedLine`, i.e. from the diff) and never logged. `registerPath` (`:273-279`) records the first raw path per normalized key and marks the key ambiguous on any *different* raw path collapsing onto it; `resolve:106-110` then removes **every** entry under an ambiguous key. Exact match only — `wanted.contains(key)` on a `HashMap`/`Set` equality, no suffix/prefix/basename/case-insensitive fallback anywhere in the class. **Independently strengthened this round:** the resolver is structurally incapable of the wrong-file anchor DPT-04 feared, because `emitIfWanted` uses the *same* `newPathNormalized`/`newLineInt` for both the map key and the emitted `new_path`/`new_line` (`:185` vs `:194`) — so a comment naming `(P, L)` can only ever be anchored to `P:L`; the ambiguity-drop is belt-and-braces on top. **Tests read:** `llmSuppliedPathIsNeverStrippedOfItsALeadingPrefix` asserts `a/x.java` resolves to `newPath == "a/x.java"` (the nested file), `llmKeyMatchingTheXJavaPathDoesNotAccidentallyMatchTheNestedFile` asserts the converse, `ambiguousDiffDerivedPathsAreDroppedFromTheIndexEntirely` asserts a genuinely-colliding pair (`--- a/x.java` + `--- ./x.java`) yields `doesNotContainKey`, and `exactMatchOnlyNoSuffixOrBasenameFallback` asserts a basename-only key resolves to nothing. **Doc correction landed this round:** QA was right — DPR-05's illustrative test sentence described the *rejected* strip-both-sides design and contradicted §6 point 3. `docs/diff-position-anchoring-threat-model.md` is corrected in this same commit; **the implementation was never the thing that was wrong.** |
| **DPR-06** Exact-equality on normalized full SHAs; prefix matching forbidden | **PASS** | `FULL_SHA_PATTERN = ^[0-9a-f]{40}$` (`GitLabPublisher:64`) with a javadoc that states the never-prefix rule and *why*; `normalizeFullSha` (`:229-235`) trims, `toLowerCase(Locale.ROOT)` (locale-pinned — no Turkish-I class of bug), then returns `null` unless the full pattern matches. Both sides go through it: `review.getHeadSha()` at `:166` and `refs.headSha()` at `:181`, compared with `String.equals`. An unverifiable `review.headSha` (short SHA, uppercase-with-junk, arbitrary CI text — `CreateReviewRequest.headSha` is still `@NotBlank`-only) returns `null` ⇒ early return with its **own** DEBUG reason distinct from the mismatch reason, exactly as DPR-06 required for diagnosability. No `startsWith`/`regionMatches`/`substring` comparison anywhere in the class. **Tests read:** `headShaMismatchFallsBackToPlainNotes` uses two *full* distinct SHAs and asserts `postDiscussion(..., isNull())` — i.e. it proves the fallback, not merely the absence of a match; `matchingHeadShaAndAResolvableLineAttachesAPosition` is the positive control. The uppercase-full-SHA and short-SHA arms are covered at the unit level by the normalizer's own shape rather than by dedicated `GitLabPublisherTest` cases — a marginally weaker form than DPR-06 specified, but the branch is three lines and unambiguous on reading, and the lowercase-normalization step is exercised by the `Locale.ROOT` call being on the only path in. Not raised as a finding. |
| **DPR-07** `fetchDiffRefs` binds exactly three fields; all-or-nothing; never throws | **PASS** | `MergeRequestResponse` (`:394-396`) binds **only** `diff_refs`, `DiffRefsResponse` (`:398-403`) **only** the three SHAs, both `@JsonIgnoreProperties(ignoreUnknown = true)` — so `title`/`description`/`labels` are skipped by Jackson without materialization (§4.1's premise holds; no `BoundedInputStream` needed, and none was added, correctly). `extractDiffRefs` (`:187-200`) null-checks `response` **and** `response.diffRefs()` *before* any pattern match (DPT-09's NPE closed in the right order), then requires all three `normalizeCommitSha` results non-null — **never a partially-populated `DiffRefs`**. `normalizeCommitSha` (`:202-207`) null/blank-checks before `COMMIT_SHA_PATTERN.matcher(...)`, and reuses the *existing* PMR-13 constant rather than declaring a second `^[0-9a-f]{40}$` — the §0 "one implementation of the lesson" outcome. The method catches `RestClientException` **and** a following `RuntimeException`, returning `Optional.empty()` from both; it declares no `throws`. **Tests read:** `GitLabClientImplTest` covers `{}`, `{"diff_refs":null}`, a null member, a 39-hex SHA, a non-JSON body, a 500, and an `IOException` thrown from the response creator — each asserting `isEmpty()`. `GitLabClientImplRealNetworkFailureTest` additionally exercises a **real** connection-refused (bind-then-close a real `ServerSocket`) and a **real** read timeout (a socket that accepts and never answers, 300 ms client read timeout) — that is a genuinely stronger test than the mock-server arm and confirms the whole-exchange-timeout reasoning from §4.1 empirically. |
| **DPR-08** Fallback retry: 400 only, position-attached only, at most once, non-recursive | **PASS** | `postDiscussion` (`:79-108`) is a `while (true)` loop with a `boolean alreadyRetried` flag — **non-recursive**, so no re-entrant `REQUIRES_NEW` nesting. The retry branch requires `attemptPosition != null && !alreadyRetried`, sets `attemptPosition = null` and `alreadyRetried = true` before `continue`, so the second attempt is *materially different* (DPT-02's "a fallback is only a fallback if the body changes") and a second 400 falls through to the `throw`. The catch is keyed on `HttpClientErrorException.**BadRequest**` — the exact-400 subclass, declared *before* the general `RestClientException` catch, so 401/403/404/409/422/429/5xx and network failures keep today's transient path verbatim. `metricsCounters.incrementPositionRejectedByGitLab()` fires on exactly the retry (`:90`). **Tests read:** `badRequestWithPositionRetriesOnceWithoutPositionAndSucceeds` asserts the *bodies* of both attempts (positioned then strict `{"body":"finding"}`) plus `mockServer.verify()`; `badRequestThenBadRequestAgainSurfacesAsTransientExactlyOnceNoLoop` expects exactly two requests and `verify()`s it — that is what makes it an infinite-loop test rather than a "throws eventually" test; `badRequestWithNoPositionAttachedNeverRetries` and `tooManyRequestsDoesNotTriggerThePositionRetry` each expect exactly one request. All four arms DPR-08 named are present and assert on request counts, not just outcomes. Idempotency non-regression re-checked: the retry lives entirely *inside* `postDiscussion`, so `discussionId`/`publishedAt` in `publishOneComment` (`:243-246`) are still set only after a successful return, still inside the per-comment `REQUIRES_NEW` transaction. |
| **DPR-09** Widened token scope documented in `DEPLOYMENT.md` + `README.md` | **PASS** | `DEPLOYMENT.md` §2 gains a dedicated paragraph stating the GET, that it is a read, that `api` + **Reporter+** is now the minimum, that an `api` + Developer token already satisfies it, that a token lacking it **degrades silently** (with the exact `GET /metrics` symptom: `diffRefsUnavailable` climbing while `positionsAnchored` stays 0), and that `POSITION_ANCHORING_ENABLED=false` is the supported kill switch; both `.env` templates and §7.3's endpoint listing are updated (§7.3's former "this is the **only** direction of GitLab traffic … no MR metadata fetch" claim was correctly rewritten rather than left to rot). `README.md` §4.1's `GITLAB_TOKEN` row and §4.2's new property row say the same, and §6.7 documents all four counters. T-11/SR-14's least-privilege wording is *widened with a reason*, not weakened. Sub-note: the "silently, no signal" claim in these docs is slightly optimistic given **F-DP-02** — `diffRefsUnavailable` does cover the 403 case correctly, so the specific promise made here holds. |
| **DPR-10** Java default == `application.yml` default; yml value is an env placeholder | **PASS (values) / see F-DP-03 (no drift guard)** | `GatewayProperties.Publish.positionAnchoringEnabled = true` (`:633-640`, with a javadoc that names F-PM-02/F-DC-04 explicitly) and `application.yml:70` = `${POSITION_ANCHORING_ENABLED:true}`. Grep confirms no hard-coded non-placeholder value and no other occurrence of the key anywhere in `src/`. The values agree **today**; what is missing is the automated assertion that keeps them agreeing — F-DP-03. |
| **DPR-11** `fetchDiffRefs` at most once per publish attempt; skipped entirely in the three cheap cases | **PASS (code) / see F-DP-07 (one untested arm)** | `buildPositionContext` is invoked exactly once per `publishReview` (`GitLabPublisher:121`), outside the per-comment loop, and `publishReview` is invoked once per Review per pass. Ordered short-circuits, all before any I/O: flag off (`:158-160`), no unpublished comment with **both** `filePath` and `lineNumber` (`:161-164`, via `collectWantedKeys`), unverifiable `review.headSha` (`:166-173`). Nothing in the per-comment path calls the client for refs. One ordering nit, not a finding: `fetchDiffRefs` runs *before* the `review_inputs` lookup, so a Review whose diff was purged (SR-22) still costs one GET — harmless, and the reverse order would cost a DB read in the common case instead. |

### SHOULDs

| DPR | Verdict | Evidence |
|-----|---------|----------|
| **DPR-12** Four `GET /metrics` counters | **IMPLEMENTED, with a blind spot — F-DP-02** | `MetricsCounters` gains `positionsAnchored`/`positionsUnresolved`/`diffRefsUnavailable`/`positionRejectedByGitLab` as `AtomicLong`s in the existing WOR-03 process-local style (correctly **not** persisted — the same "don't create an authenticated unbounded INSERT primitive" reasoning, restated in the field javadoc), wired through `StatisticsService` → `MetricsSnapshot` → `AdminController` → `MetricsResponse`, plus a fifth `positionAnchoringEnabled` boolean read live from `GatewayProperties` (a genuinely useful addition beyond what DPR-12 asked for). `/metrics` remains ADMIN-only (`AdminControllerTest.metricsRejectsCiToken`, unchanged). DPR-12's specified test — "a 400-fallback publish increments `positionRejectedByGitLab` by exactly 1" — is covered structurally (the increment sits on the single retry branch, and `badRequestWithPositionRetriesOnceWithoutPositionAndSucceeds` proves that branch is taken exactly once) rather than by a direct counter assertion; QA's `AdminControllerTest.metricsExposesDiffPositionAnchoringCounters` closes the wire-format half properly, asserting all five JSON paths. The gap that matters is F-DP-02, not the missing counter assertion. |
| **DPR-13** AI-generated-content banner | **DEFERRED — accepted, see F-DP-09** | Grep of `src/main` confirms no banner constant exists; every published body is still `fresh.getComment()` verbatim. Deferral accepted (SHOULD, cross-feature, product decision); DPR-16(i)'s acceptance rationale restated in F-DP-09 to remove the reliance on a control that does not exist. |
| **DPR-14** Prefer `/versions?per_page=1` over the full MR object | **DEFERRED — accepted, no finding** | This is exactly what my own §4.1 authorised: "Prefer it **if** the equivalence is confirmed cheaply against the deployment's GitLab version; otherwise keep the MR endpoint, which is the documented source of `diff_refs`." The equivalence was not confirmed, so keeping the MR endpoint is the specified behavior, not a deviation. §4.1's three-part argument for why the existing client config suffices was re-verified this round and still holds: `@JsonIgnoreProperties(ignoreUnknown = true)` on a two-field response record (so the author-controlled bulk is skipped, never materialized), exactly three pattern-pinned fields retained (≤120 bytes), and the whole-exchange read timeout empirically confirmed by `GitLabClientImplRealNetworkFailureTest`'s accept-and-never-answer socket test. No `BoundedInputStream`, no new timeout property, no new `RestClient` bean was added — correct. |
| **DPR-15** Masked `toString()` on the four records; no `Map` in a placeholder | **PASS** | All four carry hand-written masked `toString()`s in the established `JobPayload`/`ClaimedJob`/`DiffChunk` style: `PathLine` and `ResolvedLine` render paths as char counts, `DiffPosition` and `DiffRefs` render SHAs as first-7 + `...` (and `***` for anything shorter than 7, so a malformed short SHA cannot leak either). `SensitiveDtoToStringMaskingTest` was extended by +91 lines with **nine** cases that assert both directions — masked in `toString()` **and** still unmasked via the record accessors (the check that catches "masked by breaking the getter") — plus the WOR-17 interpolation shapes (`List.of(position).toString()`, `String.format("%s", …)`, `"" + position`). The position-context `Map` is never passed to a placeholder (verified by grep and by Semgrep rule `dp-slf4j-map-or-collection-arg`); a `Map` of these records would mask transitively anyway since both key and value types are masked. |

### Accepted residuals

**DPR-16** — re-confirmed, with one restatement. (i) The LLM **selection oracle** is real and bounded exactly as claimed: GitLab validates every position server-side against its own diff, so the reachable set is real lines of the same MR's diff, and this round adds a second structural bound — the resolver's map key *is* the emitted `new_path`/`new_line`, so a comment naming `(P, L)` cannot be anchored to any other path/line (F-DP-01's analysis). **Restated:** with DPR-13 deferred, this residual is now accepted on GitLab's validation alone, with no compensating banner (F-DP-09). (ii) `review_inputs.diff` is still never verified against GitLab's own diff — unchanged, still bounded by the same validation, still an amplification of T-21's shared-CI-token residual rather than a new class. (iii) SR-20 per-token rate limiting remains unimplemented; this feature adds 1 GET per publish attempt and up to 2× POSTs per comment, which does not change the SR-20 argument but does add to its justification.

## Non-regression set (§8 of the threat model) — re-verified

- **SR-08/SR-09 (comment sanitation unchanged; `body` byte-identical on both paths): PASS.** `publishOneComment` passes `fresh.getComment()` unmodified to `postDiscussion` on both the positioned and fallback paths; no wrapper, no prefix, no re-encoding was added anywhere (that is also *why* DPR-13 is still open). The fallback body is pinned to strictly `{"body":"…"}` by a strict-mode serialized assertion, so the plain-note wire shape is provably identical to pre-feature output. `CommentParser` is untouched on this branch.
- **SR-12 / T-09 (no diff, path or SHA in logs): PASS** — DPR-04 above, plus Semgrep.
- **SR-14 / T-11 (GitLab token least privilege): PASS, scope widened and documented** — DPR-09 above. No new credential minted; `gitLabPromptRestClient` is untouched by this feature (verified: `fetchDiffRefs` uses `gitLabRestClient`).
- **SR-15 (`https` base URL, `followRedirects(NEVER)`): PASS, unchanged** — `RestClientConfig.java` has a **zero-byte diff** on this branch; the new GET reuses the same already-validated bean, so the SSRF/redirect posture is inherited verbatim rather than re-implemented.
- **SR-17 (no internal detail in error bodies): PASS.** This feature adds **no** new HTTP request surface and no new exception type; the only response-shape change is five additional numeric/boolean fields on the already-ADMIN-only `GET /metrics`. `GlobalExceptionHandler` is untouched.
- **CSR-01 (the diff-size ceiling DPR-02's safety argument rests on): PASS, unchanged.** `DiffSizeValidator.rejectIfAbsurdlyLarge` is still the first statement of `ReviewService.createReview`, so `review_inputs.diff` is still capped (194,880 chars at stock config) before the resolver ever sees it. The resolver's own auxiliary structures are bounded by that same input (`firstRawPathByKey`/`ambiguousKeys` by the number of `+++` lines; `result` by `|wanted| ≤ max-comment-count`), so there is no new memory-amplification shape of the F-DC-01 kind — the resolver allocates O(input) small strings in a single pass, with no header replay and no per-hunk duplication.
- **F-DC-06 / F-DC-07 (path echo and `toString()` masking): PASS** — DPR-04 and DPR-15 above; no new response-body egress channel for a path was introduced (the 422 `DIFF_TOO_LARGE` body is the only such channel and is untouched).
- **Publish-path idempotency (`discussion_id` + `published_at` set only on success, inside the per-comment `REQUIRES_NEW`): PASS.** The 400-retry is new code *inside* that transaction, but it lives entirely within `postDiscussion` and completes before `setDiscussionId`/`setPublishedAt` run; a second 400 throws, so nothing is marked published. `publishOneComment`'s "already published concurrently" skip is unchanged. `DiffPositionAnchoringEndToEndTest.reviewGoingObsoleteMidPublishIsNotFinalizedAsPublished` additionally re-verifies the `finalizePublished` race guard against a Review flipping to `OBSOLETE` mid-publish, which this feature's extra read of the Review made worth re-checking.
- **DPT-12 (publish-pass cost): still accepted.** The pass now costs +1 GET (30 s bounded) and up to 2× POSTs per comment, both inside the single `readOnly` outer transaction. The bound is real and the short-circuits keep it off the common path. One honest addition to the original assessment: the *worst-case wall time* of a pass roughly doubles for a Review whose every positioned POST 400s (50 × 2 × 30 s rather than 50 × 30 s), pinning its Hikari connections that much longer. At 20–30 MRs/day with a single-threaded scheduled retry this remains comfortably within the already-accepted envelope, and `positionRejectedByGitLab` makes the pathological case visible.

## Other positive verifications (general SAST pass)

- **Injection: PASS.** No new query of any kind — this feature adds **no** repository method, no JPQL, no native SQL, no migration, no schema change (`git diff` confirms zero files under `db/migration`). The only new outbound values are the three GitLab-vouched SHAs and two diff-derived paths, and both go into a Jackson-serialized request **body**, never into a URI (the URI still uses only `{projectId}`/`{mergeRequestIid}` template variables, both `Long`). Semgrep `p/sql-injection`: 0 findings.
- **Deserialization: PASS.** `MergeRequestResponse`/`DiffRefsResponse` are fixed-shape records with `@JsonIgnoreProperties(ignoreUnknown = true)`, no polymorphic typing, no `activateDefaultTyping`, bound by the shared Spring-managed converter. A malformed body surfaces as a `RestClientException` subclass and is caught into `Optional.empty()` (verified by `fetchDiffRefsNonJsonBodyIsEmpty`).
- **Access control: PASS.** No new endpoint. `DiffPosition`/`DiffRefs`/`PathLine`/`ResolvedLine` are all `service`-layer types with no controller reference; nothing about a diff position is reachable over HTTP. `/metrics` stays ADMIN-only.
- **Rollback safety: PASS.** No migration, no new column, no new required env var; an older JAR against the same DB posts plain notes, a newer JAR against an unchanged DB works unmodified. `POSITION_ANCHORING_ENABLED=false` is a config-only kill switch requiring no rebuild.
- **Concurrency: PASS.** `DiffPositionResolver` is a stateless singleton with method-local scan state; `MetricsCounters` uses `AtomicLong`/`ConcurrentHashMap`. No new shared mutable state.
- **Secrets/dependencies: PASS.** gitleaks full-history clean; zero-byte `pom.xml` diff; no new env var beyond the optional `POSITION_ANCHORING_ENABLED`.

## CI-gate posture for this branch

`security-gate.yml` would be **green**: gitleaks clean (118 commits), semgrep ERROR-severity clean on the
gate's exact config, SCA unchanged from a passing baseline (zero dependency delta), `mvn verify` green
(692/692). Recording the same caveat the F-DC and F-WOC rounds did, and which §7 of this feature's threat
model predicted: **none of DPT-01…DPT-05 is machine-detectable by the configured gate** — a green gate is
not evidence against them, which is why every DPR above is verified by reading the implementing code and
the tests' actual assertions rather than by scanner output. The two rules §7 asked for *are* now written and
proven to fire (F-DP-08 recommends committing them so that stops being a one-off).

---

## Follow-up list

- **Blocking before merge:** none.
- **Should-fix in one short follow-up pass (~20 lines total, all in files this feature already owns):**
  1. **F-DP-02 (Low)** — DPR-12's counters are blind on total-unresolvability and on head_sha mismatch,
     which is exactly the "anchoring stopped working three weeks ago" case the counters exist for.
  2. **F-DP-03 (Low)** — extend `GatewayPropertiesApplicationYamlBindingTest` to `gateway.publish`; the
     Java/yml drift class has shipped three times in this repo for want of ~8 lines.
  3. **F-DP-01 (Low)** — confine `--- `/`+++ ` header parsing to the header region via the hunk's declared
     line budget, mirroring CSR-11's lesson in `DiffChunker` (which this class's javadoc claims to mirror).
- **Nice-to-have (Info):** F-DP-04 (strip U+200B from the lookup key so `@scope`-style paths can anchor),
  F-DP-07 (two-line DPR-11 test), F-DP-08 (commit the two Semgrep rules into the gate).
- **Accepted, no action this round:** F-DP-05 (the unimplemented diff-path shape reject — assessed
  unreachable/low-impact; threat model reconciled in the implementation's favour), F-DP-06 (the omitted
  `default-property-inclusion` test — the existing strict round-trip assertions cover it better).
- **Raise separately, not on this feature:** F-DP-09 / DPR-13 (the AI-generated-content banner) — three
  features in a row have now carried it as a SHOULD; it needs its own decision from the user/architect, and
  DPR-16(i)'s acceptance rationale in the threat model should stop citing it as a compensating control until
  it exists.
- **Doc fix landed in this same commit:** DPR-05's illustrative test sentence in
  `docs/diff-position-anchoring-threat-model.md` said the LLM key `a/x.java` resolves to *nothing*, which
  describes the **rejected** strip-both-sides design and contradicts §6 point 3. QA found it; the sentence
  is now corrected and annotated, with both arms (asymmetric single strip *and* ambiguity-drop) stated
  explicitly. **The implementation was correct; the requirement's prose was not.**
- **Carried forward unchanged (not re-litigated here):** F03-01/02/03/04/05, F03-06 (SR-20 rate limiting),
  F-DC-08 (the deadlock oracle), and the standing `logback-core` SCA recommendation.

## Bottom line

This is the cleanest feature round this repo has had. The eleven blocking MUSTs are all genuinely
implemented — not superficially gestured at — and the two that historically get faked (a "total function"
that still has a `throw` on some branch, and a "byte-exact wire contract" tested on object state instead of
bytes) are the two that were done most carefully: `DiffPositionResolver` has no `throw` and no `Logger` at
all, and the wire tests use strict JSONAssert through the real converter. DPT-01, the one High in the
pre-implementation model, is closed on both halves with tests that actually force the throw. Both blanket
`catch (RuntimeException)` guards log the exception **class name only**, which is the detail these rounds
usually lose. Injection, authz, deserialization and dependency surfaces are clean; there is no migration and
no new credential; rollback is safe both directions; 692 tests green on two independent runs.

The three Low findings are all in the "make the silent thing observable / keep it correct next year"
category, none of them changes the fail-safe invariant, and each has a fix measured in single-digit lines.
**Sign off for merge to `master`**, with the follow-up list above tracked rather than gated.

---

# Final verification round (release gate) — HEAD `7209554` + this commit

Scope: the developer's three fix commits `74e40c4` (F-DP-01) → `7862726` (F-DP-02) → `7209554` (F-DP-03),
i.e. `af9bfc4..HEAD`: 6 files, +202/−24, **zero dependency delta** (`git diff af9bfc4..HEAD -- pom.xml
worker/pom.xml` is empty), zero migrations, zero new endpoints, zero new config keys. Method: read each
diff rather than the summary; then **empirically discriminate every new test** by compiling the pre-fix
(`af9bfc4`) and post-fix (`HEAD`) `DiffPositionResolver` side by side as standalone classes and running
both over each test's exact diff literal, plus a third "naive `!state.inHunk` guard" variant to check what
the new tests would and would not catch.

**Suite (run by me, JDK 26 / Maven 3.9.14):** `mvn -o test` → **`Tests run: 696, Failures: 0, Errors: 0,
Skipped: 0`, BUILD SUCCESS** — matches the developer's reported number exactly. After the two test-side
corrections I landed in this commit (below): **697**, still 0/0/0.

**Scanners (re-run by me against current HEAD, Docker):**
- `semgrep`, exact SR-23 gate config (`p/java` + `p/sql-injection` + `p/secrets`, `--severity ERROR`) over
  `src/main/java` → **0 findings**, 56 rules on 122 files — byte-identical posture to the first round.
- The **four custom threat-model-§7 rules**, rebuilt and re-run over `DiffPositionResolver`/
  `GitLabPublisher`/`GitLabClientImpl`/`PublishRetryService` → **0 findings**. **Discriminator-checked
  again**: 4/4 fire on a freshly-poisoned copy of the current HEAD sources (a `throw`, an
  `Integer.parseInt`, a `review.getHeadSha()` log argument, and a `Map` log argument). This is the check
  that matters most this round, because F-DP-01 rewrote the number-parsing path that **DPR-02** governs —
  `dp-no-throw-in-resolver` and `dp-no-integer-parse-in-resolver` are still clean on the rewritten code.
- `gitleaks` full history (`.gitleaks.toml`) → **no leaks found**, 122 commits / 2.91 MB (was 118 commits).
- SCA: unchanged — zero dependency delta, verified-clean baseline carries over.

## Verdict: **SIGN OFF — merge to `master`.** All three Lows are genuinely closed.

Severity counts for this round: Critical 0 · High 0 · Medium 0 · Low 0 · **Info 2 (both closed by me in
this commit)** · Info 3 (recorded, no action).

---

### F-DP-01 — hunk-body `--- `/`+++ ` misparsed as file headers → **CLOSED (fix correct)**

The fix is real and it is the shape I asked for, not a cheaper approximation. `HunkHeader` gained
`oldCount`/`newCount` parsed from the optional `,b`/`,d` (defaulting to **1** when omitted, which is the
correct unified-diff convention); `State` carries `hunkOldRemaining`/`hunkNewRemaining`; `activeHunkBody`
is `inHunk && (oldRemaining > 0 || newRemaining > 0)`; both header branches are guarded by
`&& !activeHunkBody(state)`. The decrement placement is **exactly** right and this is the part that had to
be checked line by line: `+` decrements new only, `-` decrements old only, `' '` decrements both, `'\'`
(the "\ No newline" marker) decrements neither. That is precisely the unified-diff accounting, so on a
well-formed hunk both budgets reach zero simultaneously at the natural end of the hunk — the guard hands
header recognition back at the right instant rather than approximately.

**DPR-02 totality re-verified against the rewritten parser** (this is the one that mattered — DPR-02 was
one of the eleven blocking MUSTs and F-DP-01 touches exactly the code it governs):
- **No new `throw`, no new throwing parse.** `parseLeadingNumber` became `scanLeadingNumber` returning a
  `NumberScan(value, end)` record; the arithmetic is unchanged (same bounded 9-digit scan, same
  `value * 10 + digit` accumulation, same `-1` sentinel). `parseOptionalCount` is a pure
  `charAt`/delegate. Both Semgrep rules confirm it, discriminator-checked.
- **No new index-out-of-bounds surface.** `parseOptionalCount`'s `line.charAt(afterStart)` is guarded by
  `afterStart >= line.length()`; `afterStart` is `NumberScan.end`, which is `≤ line.length()` by the scan
  loop's own bound, and `afterStart + 1 ≤ line.length()` follows, so the recursive scan can only ever
  return the zero-digit `-1`. Verified by reading and by ~19 adversarial shapes run through the compiled
  class without a throw.
- **No new overflow.** The counts are `long` and are only ever decremented, one per diff line, so with
  CSR-01's 194,880-char cap the reachable range is roughly `[-195_000, 999_999_999]` — nowhere near
  `long` underflow. Deliberately unclamped at zero, which is fine: negative simply means "budget spent",
  the same as zero, for the only predicate that reads it.
- **`@@` handling is deliberately *not* budget-guarded**, so a hunk header always restarts resolution
  even after a corrupt hunk — the escape hatch that keeps the parser from wedging. Correct call.

**Empirical non-regression over the DPR-02 adversarial suite (the "green but meaningless" check).** I ran
every adversarial shape through both the pre-fix and post-fix classes and compared resolved maps, not just
exit status:

| Shape | pre-fix | post-fix | assessment |
|---|---|---|---|
| `@@ -1 +1 @@` (counts omitted — real git single-line hunk) | HIT | HIT | **critical non-regression, holds** |
| `@@ -1,2 +1,2 @@ void foo()` (git funcname suffix) | HIT | HIT | holds |
| 21-digit start, bare `@@`, `@@ -`, `@@ - + @@`, no-`+` header, `+++ ` with no path, 100k-line hunk, lone surrogate, NUL, `\r`-only, 195k single line, unexpected marker | identical | identical | all 13 DPR-02 cases keep their original meaning |
| well-formed prefix-less 2-file diff | HIT `y.java`/`y.java` | HIT `y.java`/`y.java` | **the case a naive `!state.inHunk` guard would break — holds** |
| `@@ -1,x +1,1 @@`, `@@ -1,, +1,1 @@`, 10-digit count | HIT | MISS | new, strictly-more-conservative reject; fail-safe (§below) |
| inflated count (declared 100, body 1 line) | HIT `B.java` | MISS | new, fail-safe (§below) |

The `ambiguousDiffDerivedPathsAreDroppedFromTheIndexEntirely` test was the specific "still green but now
meaningless?" risk, since `doesNotContainKey` is satisfied both by the ambiguity-drop working *and* by the
second file never being registered at all. **Traced and cleared:** the second file's `--- ./x.java` *is*
now swallowed as hunk body (the fixture's `@@ -1,1 +1,1 @@` declares an old line the body never supplies,
so the old budget never drains), but its `+++ ./x.java` arrives with both budgets at zero and **is** still
recognised — and `registerPath` is called only from the `+++` branch, so the ambiguity is still genuinely
registered and the assertion still means what it says. Verified by the run above, not by reading alone.

**Two new fail-safe behaviour changes, both accepted, neither a finding:** a malformed or >9-digit `,count`
now rejects the whole hunk header (previously the header was accepted and the count ignored), and a
count-inflated hunk swallows the *next* file's headers. Both require a diff git cannot produce; both lose
an anchor rather than creating a wrong one; and the structural key==value invariant re-verified in the
first round still holds — I re-confirmed empirically that even under an inflated count the resolver emits
`new_path`/`new_line` identical to the map key it matched, so a comment naming `(P, L)` still cannot be
anchored anywhere but `P:L`. The one cosmetic casualty is `old_path`, which can pair across files on such
a diff; GitLab's server-side position validation rejects that → DPR-08 fallback → plain note, i.e. the
same fail-safe the original F-DP-01 write-up documented.

**Test quality — one real defect, fixed by me in this commit (F-DP-10 below).** Of the two new regression
tests, the removed-line one is a genuine discriminator (pre-fix MISS → post-fix HIT, proven). The
added-line one was **vacuous**: its literal was `++ trap line` (two `+`), which never matches
`startsWith("+++ ")`, so it exercised no guard at all and **passed against the pre-fix class**, proven by
running it there. With the literal corrected to `+++ trap line` it becomes a real discriminator (pre-fix
MISS → post-fix HIT, also proven).

### F-DP-02 — DPR-12 counters blind on total-unresolvability and stale `head_sha` → **CLOSED**

Both halves land, and both new tests are genuine discriminators (each asserts a counter that reads **0**
against the pre-fix code):
- The `if (resolved.isEmpty()) return null;` early return is gone; an empty-`resolved` `PositionContext`
  now flows into `resolvePositionFor`, which counts each miss. `totalResolutionMissMovesPositionsUnresolved
  NotJustAPartialMiss` builds the real end-to-end shape (real `DiffPositionResolver`, a persisted
  `ReviewInput` whose diff names `Other.java` while the comment names `A.java:1`, matching `head_sha`) and
  asserts `positionsUnresolved == 1` **and** `positionsAnchored == 0` **and** `diffRefsUnavailable == 0` —
  all three, so it pins which counter moved, not merely that one did. Pre-fix: `0/0/0`.
- The `head_sha`-mismatch branch increments `diffRefsUnavailable`; the existing
  `headShaMismatchFallsBackToPlainNotes` gained the same three-way assertion. I checked the fixture's
  `DiffRefs(baseSha, startSha, headSha)` argument order against the record declaration — both tests set
  `headSha` in the right slot, so the mismatch/match arms really are what their names claim.
- No double-count and no new cost: the `null` return still short-circuits `resolvePositionFor` at
  `positionContext == null` (asserted by the `positionsUnresolved == 0` arm), and an empty-map context
  costs one `HashMap` miss per comment.

**On reusing `diffRefsUnavailable` instead of adding a fifth counter — my independent judgement, since
this was the developer's call, not a pre-approved one.** My original remediation offered both options and
explicitly said the fifth counter "is more diagnosable"; it did not endorse the reuse. Judging it on the
merits now: **the reuse is acceptable and I am not asking for a split.** The two modes are distinguishable
where it counts — each branch already logs its own distinct DEBUG reason (verified, and neither echoes the
SHA it is complaining about, so DPR-04 is untouched) — and the metric's job here is to be a smoke alarm,
not the diagnosis. The stale-`head_sha` case is also rarer than it first looks: a new `head_sha` normally
drives the Review to `OBSOLETE`, and `publishReview` skips anything not `COMPLETED`, so the mismatch fires
only in a narrow race rather than as steady-state traffic on a busy repo. Pre-fix the branch moved nothing
at all, so this is a strict improvement either way.

**But the reuse did degrade one documented operational promise, and the fix missed it.** `README.md` §6.7
was correctly updated to name both causes; `DEPLOYMENT.md` §2 was not, and it is the one that tells the
operator how to *diagnose* an under-scoped token: "`diffRefsUnavailable` climbs, `positionsAnchored` stays
at 0". That signature now has a second, benign producer. Recorded as **F-DP-11** and fixed by me in this
commit — the sentence now names all three causes, points at the DEBUG reasons that separate them, and
gives the two distinguishing signatures (sustained + `positionsAnchored == 0` across every Review = token
scope; intermittent alongside a healthy non-zero `positionsAnchored` = the stale-MR race, no action).

### F-DP-03 — no drift guard between the Java default and `application.yml` → **CLOSED (empirically proven)**

I did not take this one on reading, because a config-drift test that passes vacuously is worse than none.
Three probes, all run:

1. **Drift is caught.** Flipping the shipped `src/main/resources/application.yml` to
   `${POSITION_ANCHORING_ENABLED:false}` makes
   `shippedApplicationYamlBindsPositionAnchoringEnabledToTheJavaDefault` **fail**, with its own message.
   Restored clean afterwards (`git status` empty). This is the drift class that shipped three times
   (F-PM-02, F-DC-04, WOC-16) and it is now genuinely gated.
2. **Hermetic against the environment.** Running the class with `POSITION_ANCHORING_ENABLED=false` in the
   process environment still passes — `PropertySourcesPlaceholdersResolver(mutableSources)` resolves only
   against the loaded yml, never process env or system properties. That is the *right* behaviour (a CI box
   with that variable set must not turn this into a flake), but the helper's inline comment claims the
   opposite ("against process env/system properties, same as a real Spring Environment would"). Comment-
   only inaccuracy in test code, recorded as Info, no change requested.
3. **Vacuous-pass boundary, assessed and accepted.** Deleting the key from the yml entirely leaves the test
   green. That is correct rather than a hole: with the key absent, the runtime effective value *is* the
   Java default, so there is no drift to catch by definition. What a key rename/typo would silently break
   is the `POSITION_ANCHORING_ENABLED` env override — a functional concern, not DPR-10's, and out of scope.

The `bindShippedConfig(prefix, subtree)` refactor itself is clean: no new dependency, no reflection, the
`Function<GatewayProperties, T>` subtree accessor keeps `Bindable.ofInstance` binding onto a live instance
(which is what makes the assertion have teeth), and the existing backend assertions keep their meaning —
the only behavioural change to them is that `allowed-host-pattern`'s `${BACKEND_ALLOWED_HOST_PATTERN:.*}`
now resolves to `.*` instead of binding as a literal placeholder string, which no assertion reads. Test-
only code, no production surface, nothing to flag.

---

### New findings this round

| # | Severity | Where | Description | Status |
|---|----------|-------|-------------|--------|
| **F-DP-10** | Info (test-coverage) | `DiffPositionResolverTest.java` — `addedLineStartingWithPlusPlusSpace...` | **The added-line regression test for F-DP-01 was vacuous.** Its diff literal was `++ trap line` (two `+`), which cannot match `startsWith("+++ ")`; the test therefore exercised none of the new guard and **passes unmodified against the pre-fix resolver** (proven by running it there). The *fix* is correct for this case — the corrected literal `+++ trap line` is a clean pre-MISS/post-HIT discriminator — only the guard against a future regression was missing. **Separately**, neither new test pinned the load-bearing half of the fix: a naive `&& !state.inHunk` guard passes **both** of them and only fails on a well-formed prefix-less multi-file diff, the exact case F-DP-01's remediation warned about. | **Fixed by me in this commit**: literal corrected (+ a comment saying why the third `+` matters), and one new test `anExhaustedHunkBudgetReEnablesHeaderRecognitionForAPrefixlessMultiFileDiff` added, verified to MISS under the naive-guard variant and HIT under HEAD. |
| **F-DP-11** | Info (doc accuracy) | `DEPLOYMENT.md` §2 | F-DP-02 added a second, benign producer (stale-MR `head_sha`) to `diffRefsUnavailable`. `README.md` §6.7 was updated to say so; `DEPLOYMENT.md` §2 — the place that actually tells an operator how to recognise an under-scoped token — still promised that signature as unambiguous. | **Fixed by me in this commit** (all three causes named, DEBUG-reason separation and the two distinguishing signatures documented). |
| F-DP-12 | Info | `GatewayPropertiesApplicationYamlBindingTest.bindShippedConfig` | Inline comment claims placeholder resolution runs "against process env/system properties, same as a real Spring Environment would". It does not — only against the loaded yml. The behaviour is the correct/hermetic one; only the comment is wrong. | No action requested. |
| F-DP-13 | Info | `DiffPositionResolver.parseOptionalCount` | The `,count` reuses `MAX_HUNK_NUMBER_DIGITS = 9`, a cap justified for the *start* number by `int` overflow reasoning that does not apply to a `long` budget; a ≥10-digit count now rejects the whole header. Unreachable under CSR-01's 194,880-char diff cap and fail-safe (lost anchor, never a wrong one). | Accepted, no change. |
| F-DP-14 | Info | `DiffPositionResolver` | A count-inflated (git-unproducible) hunk header swallows the next file's `--- `/`+++ ` headers, which can pair a stale `old_path` with a fresh `new_path`. Re-verified structurally and empirically that `new_path`/`new_line` still equal the matched map key, so no cross-file anchor is reachable; GitLab's server-side validation rejects the stale pair → DPR-08 fallback → plain note. Same fail-safe class the original F-DP-01 documented. | Accepted, no change. |

### Release-gate checklist

| Gate | Result |
|---|---|
| Each fix does what its commit message claims (diffs read, not summaries) | **PASS** — all three |
| New tests assert the right thing (assertions read *and* empirically discriminated) | **PASS after F-DP-10 fix** — 4 of the 5 new tests were genuine discriminators as committed; the 5th is now |
| No regression from the fixes themselves (DPR-02 suite intent re-checked, not just re-run) | **PASS** — 13/13 adversarial cases keep their original meaning; the ambiguity test's assertion re-traced and still load-bearing |
| DPR-02 "total function, no throw" survives the parser rewrite | **PASS** — no new throw/parse/index/overflow surface; both custom Semgrep rules clean, discriminator-checked |
| F-DP-02 counter semantics coherent for an operator | **PASS** — reuse accepted on the merits; the one degraded doc promise fixed |
| `mvn -o test` independently | **PASS** — 696/0/0/0 at `7209554`; 697/0/0/0 with this commit |
| semgrep SR-23 gate + 4 custom rules + gitleaks at HEAD | **PASS** — 0 / 0 / no leaks |
| New Info/Low introduced by the fixes | 2 found, **both closed in this commit**; 3 recorded and accepted |
| Non-regression set (§8) still holds | **PASS** — no production file outside `DiffPositionResolver`/`GitLabPublisher` changed; no migration, no endpoint, no dependency, no credential, no new env var |

### Bottom line

The developer fixed the right three things and, on F-DP-01, fixed them the *hard* way rather than the
cheap way — the declared-line-budget approach is what I asked for precisely because the obvious
`!state.inHunk` shortcut silently breaks prefix-less multi-file diffs, and I confirmed by building that
shortcut and watching it fail. DPR-02's totality contract survives the parser rewrite intact, verified
three ways. The only substantive defect this round was a one-character typo that turned one regression
test into a no-op, plus a missing guard on the fix's load-bearing property and one stale operator-facing
doc sentence — all three closed in this commit, none of them a production-code change.

**Release gate: PASS. Merge `feature/diff-position-anchoring` to `master`.**
