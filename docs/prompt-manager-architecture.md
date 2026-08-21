# Architecture: PromptManager

Status: **APPROVED**, architecture + appsec threat model both complete. See `docs/prompt-manager-threat-model.md` (PMT-01..25 / PMR-01..30) for the full security requirement set — this document has already been corrected to match it (see §0). Implementation follows this document + the PMR checklist.

## 0. Corrections applied after the appsec threat-model round

The appsec threat model (`docs/prompt-manager-threat-model.md`) found one **blocking architectural flaw** in the original design and required several other MUST changes before implementation starts. Applied here:

1. **Project-section ref = project's default branch, not the MR's target branch.** The original design read `.ai-review/architecture.md` / `.ai-review/code-rules.md` from `GET /merge_requests/{iid}.target_branch` as an anti-self-reference control. That control does not hold: `target_branch` is chosen by the MR author, so `feature → my-own-unprotected-base` lets an attacker fully control the rulebook their own code is reviewed under. **Fixed**: resolve `GET /projects/{id}` → `default_branch` instead (protected in any sane setup). If per-release-branch rules are ever needed, only use the target branch when `GET /projects/{id}/protected_branches/{name}` returns 200; 404 falls back to the default branch with a `PROMPT_REF_FALLBACK` event. `resolveCommitSha` still runs first on the chosen ref — that part of the design was correct.
2. **Two GitLab credentials, not one.** New `gitLabPromptRestClient` bean, read-only token (`GITLAB_PROMPT_TOKEN`, `read_api`/`read_repository`). The existing `gitLabRestClient`/`GITLAB_TOKEN` keeps write-only discussion-posting. See PMR-15.
3. **Sources are project references (numeric id or `group/project` path) on the existing `gateway.gitlab.base-url` host — never a free-form URL field.** This closes off the SSRF shape the original v1 requirements doc's `corporate_repo.url` would have opened. See PMR-14.
4. **Constant preamble/trailer + non-forgeable delimiter around `PROJECT_*` sections.** Not in the original draft; added because raw untrusted section text landing undelimited in the system role is a direct prompt-injection path (PMT-01). See §4 below and PMR-01/02.
5. **Fail-closed at claim time.** Review persists a `prompt_bundle_mode` (`NONE`/`REPO`) at create time; `REPO` + zero corporate sections at claim ⇒ the job fails loudly, never runs with a degraded/empty system prompt. See PMR-09.
6. **`max-file-bytes` is a streaming bound, and prompt resolution runs under its own bounded concurrency permit + wall-clock deadline**, so a slow/unavailable GitLab cannot exhaust the Gateway's Tomcat pool and take down `/jobs/claim`/heartbeat (which would requeue tens of minutes of in-flight LLM work). See PMR-17/19.
7. **MULTI (per-section `ChatMessage`) is the default message format, not a coin flip with SINGLE** — MULTI keeps section boundaries structural; SINGLE's entire boundary guarantee rests on the delimiter. See PMR-22.

Everything else in this document (component list, resolution sequence, DB schema, config shape, Worker changes, budget-math changes, error taxonomy) reflects the architecture as originally designed and reviewed; only the seven points above are corrections layered on top.

## 1. Summary

Gateway synchronously assembles a system prompt for each Review from: 2 mandatory sections in one org-wide corporate GitLab project, and up to 2 optional sections in the reviewed project (or an operator-configured override project). Result is persisted immutably in PostgreSQL before the Review is created, then rendered into `JobPayload.systemMessages` at `/jobs/claim`. Worker wraps the given text verbatim into `ChatMessage(role=system)` entries — it stays a fully stateless HTTP client, no GitLab/DB access added.

## 2. Components (all inside the existing Gateway deployment unit — no new service)

| Component | Responsibility |
|---|---|
| `PromptManager` / `PromptManagerImpl` | Orchestrates one Review's resolution: sources → fetch → assemble → cap. Not `@Transactional`, no DB access itself. |
| `PromptSourceResolver` | Pure function `projectId → List<PromptSourceSpec>` (applies `overrides`, defaults, ref strategy from §0.1). No network I/O. |
| `GitLabPromptClient` (extends `GitLabClient`) | 3 new read-only GitLab calls, via the dedicated `gitLabPromptRestClient` bean. |
| `PromptAssembler` | Ordering, preamble/trailer injection, delimiter wrapping, token estimation, capping. |
| `TextSanitizer` (new, shared) | Cc/Cf/Zl/Zp + delimiter-char-class stripping, NUL rejection. Used by both this feature and `ChunkContextRenderer` (generalized, not duplicated). |
| `PromptMessageFormatter` | Claim-time: DB rows → `List<String>` in MULTI or SINGLE, per `backends.prompt_message_format`. |
| `ReviewPromptSection` entity + repository | Immutable per-section persistence + provenance. |
| `PromptSourceUnavailableException`, `PromptSourceMissingException`, `PromptSourceInvalidException`, `PromptTooLargeException`, `PromptSectionsMissingException` | Classified for `GlobalExceptionHandler`. |

**Explicitly not built**: separate microservice, `@Scheduled` sync/activation job, local git checkout, extra cache layer beyond the optional content-addressed one (§6), prompt history/dataset table.

No new dependencies in `pom.xml` (no JGit, no Resilience4j, no Caffeine — `java.util.concurrent.Semaphore` + a bounded `ConcurrentHashMap` cover the concurrency permit and optional cache).

## 3. Resolution sequence (synchronous, inside `ReviewService.createReview`, after dedup + cheap diff guard, before `persistNewReview`)

```
1. sources = PromptSourceResolver.resolve(projectId)
     corporate: {project, ref=configured branch, [base-prompt-path, review-rules-path]}   (mandatory)
     project:   override.get(projectId) OR {project=projectId, defaultBranch, default paths}

2. corpSha = gitLabPromptClient.resolveCommitSha(corporate.project, corporate.ref)
     -> any error (incl. 404) => PromptSourceUnavailableException (always fails; corporate is mandatory)

3. corpBase  = fetchRawFile(corporate.project, base-prompt-path,  corpSha, maxFileBytes)
   corpRules = fetchRawFile(corporate.project, review-rules-path, corpSha, maxFileBytes)
     -> 404 => PromptSourceMissingException (mandatory section absent = config error, not "no customization")

4. if project.enabled:
   4a. projectRef:
         - override present            => override.ref
         - default (no override)       => GET /projects/{id} -> default_branch
   4b. projSha = resolveCommitSha(projSpec.project, projectRef)
         -> error => FAIL or (on-error=SKIP_OPTIONAL) skip both project sections, prompt_degraded=true
   4c. arch  = fetchRawFile(projSpec.project, architecture-path, projSha, maxFileBytes)
       rules = fetchRawFile(projSpec.project, code-rules-path,   projSha, maxFileBytes)
         -> 404 on a DEFAULT path        => normal, section absent, no signal
         -> 404 on an EXPLICIT override path => WARN + PROMPT_SECTION_MISSING event + ABSENT row (PMR-11), still 200
         -> other error => FAIL or SKIP_OPTIONAL per config

5. PromptAssembler:
     order: CORPORATE_BASE, CORPORATE_REVIEW_RULES, [preamble], PROJECT_ARCHITECTURE, PROJECT_CODE_RULES, [trailer]
     each PROJECT_* wrapped in a delimited block (TextSanitizer-safe token, see §4)
     estimate tokens (incl. preamble/trailer/delimiters) -> PromptTooLargeException if over max-system-prompt-tokens

6. ResolvedSystemPrompt(sections, estimatedTokens, degraded, promptBundleMode=REPO)
```

Whole block runs under a bounded semaphore permit with a wall-clock deadline (`gateway.prompt.total-timeout`, default 20s); saturation is an immediate `503`, never a queued thread (PMR-19). Never inside a DB transaction — a GitLab HTTP call must never hold a Hikari connection or a row lock.

`sweepObsolete` and the dedup lookup run **before** this block (no point calling GitLab for a request that will be deduplicated); `persistNewReview` (Review + ReviewInput + ReviewChunk + ReviewJob + `review_prompt_sections`) runs **after**, in the existing single `REQUIRES_NEW` transaction — a Review can never exist with chunks but no sections.

**Why the SHA is resolved before the files, always:** two independent reasons, both required. (a) Consistency: a concurrent push between two file fetches of the same Review must not produce a mismatched pair. (b) GitLab returns 404 identically for "file not found" and "project/ref not accessible" (deliberately, to not leak private-project existence) — resolving the SHA first proves access, so a later 404 on a specific file is unambiguous.

## 4. Prompt-injection defense (PMR-01/02/03, appsec §4.1)

Assembled system content, in order:
1. `CORPORATE_BASE` — verbatim, sanitized.
2. `CORPORATE_REVIEW_RULES` — verbatim, sanitized.
3. **Compile-time constant preamble** (never fetched): states that what follows is project-supplied reference material, not instructions, and cannot override the rules above.
4. `PROJECT_ARCHITECTURE` — sanitized, wrapped in a begin/end delimited block.
5. `PROJECT_CODE_RULES` — sanitized, wrapped in a begin/end delimited block.
6. **Compile-time constant trailer**: restates precedence.

Delimiter: lines built from U+241E (SYMBOL FOR RECORD SEPARATOR), e.g. `␞␞␞ BEGIN PROJECT_CODE_RULES ␞␞␞` / `␞␞␞ END PROJECT_CODE_RULES ␞␞␞`. `TextSanitizer` strips every U+241E code point from section content before wrapping — the same F-DC-02 lesson (strip the character class, never `String.replace` a multi-char token, since self-nested payloads defeat single-pass replace). `TextSanitizer` also strips Unicode Cc (except `\n`/`\t`), all Cf (bidi overrides), Zl, Zp, and rejects NUL — shared with `ChunkContextRenderer`, Cc/Cf stripped before delimiter-char stripping (CSR-09 order preserved).

Preamble/trailer/delimiters are counted in the token budget and emitted identically in MULTI (each becomes its own `ChatMessage`) and SINGLE (concatenated with the section separator) formats.

## 5. Configuration (`gateway.prompt.*`)

```yaml
gateway:
  prompt:
    enabled: ${PROMPT_MANAGER_ENABLED:true}       # kill-switch; false = today's Worker-JAR-only behavior, zero GitLab calls

    corporate:
      project: ${PROMPT_CORPORATE_PROJECT:}        # numeric id or "group/project" path -- NEVER a URL
      ref: main
      base-prompt-path: prompts/base-system-prompt.md
      review-rules-path: prompts/review-rules.md

    project:
      enabled: true
      # ref is ALWAYS the project's own default branch unless an override sets one explicitly (see §0.1)
      architecture-path: .ai-review/architecture.md
      code-rules-path: .ai-review/code-rules.md
      overrides:
        "1042":
          project: org/team-a/ai-review-prompts
          ref: main
          architecture-path: architecture.md
          code-rules-path: code-rules.md

    error-handling:
      on-error: FAIL            # FAIL | SKIP_OPTIONAL -- corporate sections are ALWAYS FAIL, not configurable

    message-format: MULTI       # MULTI | SINGLE -- default MULTI (PMR-22); backends.prompt_message_format overrides per-backend
    section-separator: "\n\n---\n\n"   # SINGLE mode only

    limits:
      max-file-bytes: 262144
      max-system-prompt-tokens: 6000
      min-diff-budget-tokens: 1000
      max-sections: 4
      max-concurrent-resolutions: 4          # PMR-19 semaphore permits

    connect-timeout: 3s
    read-timeout: 8s
    total-timeout: 20s
```

Startup validation (`GatewayProperties.validateOnStartup()`, same `@PostConstruct` pattern as SR-15): `corporate.project`/`ref`/both paths non-blank; `project` matches `^[0-9]+$|^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+){1,10}$` (no scheme/`@`/`:`/`//`); `ref` matches `^[A-Za-z0-9._/-]{1,255}$`, no `..`; each path matches `^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$`, ≤200 chars, no leading `/`, no `..`; `overrides` size-capped (≤500); budget consistency check (`contextWindow - promptReserve - answerReserve - maxSystemPromptTokens >= minDiffBudgetTokens`); `totalTimeout >= readTimeout * 2`.

Two new required secrets (env-only, ≥32-char-equivalent GitLab-token check like the existing `gateway.gitlab.token`): `GITLAB_PROMPT_TOKEN` alongside the existing `GITLAB_TOKEN`.

## 6. Data model (Flyway `V3__prompt_manager.sql`)

```sql
CREATE TABLE review_prompt_sections (
    id                BIGINT       GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    review_id         BIGINT       NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    ordinal           INTEGER      NOT NULL,
    kind              VARCHAR(32)  NOT NULL
        CONSTRAINT ck_prompt_section_kind CHECK (kind IN
            ('CORPORATE_BASE','CORPORATE_REVIEW_RULES','PROJECT_ARCHITECTURE','PROJECT_CODE_RULES')),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PRESENT'
        CONSTRAINT ck_prompt_section_status CHECK (status IN ('PRESENT','ABSENT')),  -- PMR-11
    content           TEXT         NOT NULL DEFAULT '',   -- empty for ABSENT rows
    source_project    VARCHAR(256) NOT NULL,
    source_path       VARCHAR(512) NOT NULL,
    source_ref        VARCHAR(256) NOT NULL,
    source_commit     VARCHAR(64)  NOT NULL,
    content_sha256    VARCHAR(64)  NOT NULL,
    estimated_tokens  INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_prompt_sections_review_ordinal UNIQUE (review_id, ordinal),
    CONSTRAINT uq_prompt_sections_review_kind    UNIQUE (review_id, kind)
);

ALTER TABLE review_inputs
    ADD COLUMN system_prompt_tokens INTEGER,
    ADD COLUMN prompt_degraded      BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE reviews
    ADD COLUMN prompt_bundle_mode VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CONSTRAINT ck_reviews_prompt_bundle_mode CHECK (prompt_bundle_mode IN ('NONE','REPO'));  -- PMR-09

ALTER TABLE backends
    ADD COLUMN prompt_message_format VARCHAR(16)
        CONSTRAINT ck_backends_prompt_message_format
        CHECK (prompt_message_format IS NULL OR prompt_message_format IN ('MULTI','SINGLE'));
```

New table, not a `system_prompt TEXT` column on `review_inputs`: MULTI format needs sections as separate entities (not split-by-delimiter of a blob — that's the exact bug class F-DC-02 already found once in this codebase), and provenance (`source_commit`) is per-source, and there are two distinct sources with two distinct SHAs.

App DB user gets `INSERT`/`SELECT` only on `review_prompt_sections` (PMR-07, extends SR-19) — no `UPDATE`/`DELETE` grant, matching the append-only contract already used for `review_inputs`.

## 7. `GitLabClient` extension

```java
public interface GitLabClient {
    String postDiscussion(Long projectId, Long mergeRequestId, String body);          // existing, unchanged

    String resolveCommitSha(String projectRef, String ref);                          // GET repository/commits/{ref}
    Optional<String> fetchRawFile(String projectRef, String filePath,                // GET repository/files/{path}/raw?ref=sha
                                   String commitSha, int maxBytes);
    String resolveDefaultBranch(Long projectId);                                     // GET /projects/{id} -> default_branch
}
```

Implementation follows `GitLabClientImpl.postDiscussion`'s existing pattern exactly: templated URI path segments only (`UriUtils.encodePathSegment` for the file path so `/` → `%2F`), never string concatenation (SR-10). New calls go through a **new** `gitLabPromptRestClient` bean (read-only `GITLAB_PROMPT_TOKEN`, `followRedirects(NEVER)` set explicitly, 3s/8s connect/read timeouts) — never the existing write-scoped `gitLabRestClient`. `resolveCommitSha`/`resolveDefaultBranch` failures (any non-2xx, including 404) always throw `PromptSourceUnavailableException`; `fetchRawFile` returns `Optional.empty()` only on a bare 404 with the project/ref already proven reachable. `fetchRawFile` streams the body bounded at `maxBytes + 1`, aborting early — never buffers first and checks after (that was F-DC-01's mistake, one feature later).

## 8. Worker-side changes

**Changed:**
- `dto.JobPayload` (both Gateway and Worker) gains `List<String> systemMessages` — masked in `toString()` as `<masked, N msg, M chars>` (extends the F-DC-07 pattern already applied to `diff`/`chunkContext`).
- `service.dto.ClaimedJob` (Gateway) gains the same field, same masking.
- `QueueManager.claimJobRow`: after loading the `ReviewChunk`, one indexed `SELECT` on `review_prompt_sections` by `review_id`, then `PromptMessageFormatter.render(sections, backend.promptMessageFormat ?? global default)`.
- Worker `PromptTemplateService.resolve(promptVersion, diff, chunkContext, systemMessages)`: when `systemMessages != null`, each element becomes `ChatMessage("system", text)` **verbatim, without calling `substitute()`**, in list order, and `template.system()` is ignored entirely (not duplicated). `systemMessages == null` ⇒ exactly today's behavior (template's own `system:` block) — this is the explicit legacy/compat branch, not a fallback-on-error. Old 2/3-arg overloads keep delegating with `systemMessages = null`.
- Worker `JobPayload`/`ClaimResponse` gain `@JsonIgnoreProperties(ignoreUnknown = true)` (the llama DTOs already have this; these two currently don't — forward/backward compatibility across independent Gateway/Worker deploys must be a stated contract, not an accident of Jackson defaults).
- New Worker-side limits (WSR-03 sibling, independent of Gateway's own enforcement): `worker.limits.max-system-messages` (default 8), combined with `max-diff-bytes` for the total size check.

**Not touched:** `promptVersion` allowlist regex (WSR-01), `ClassPathResource`-only template loading (WSR-07), the single-pass `{{DIFF}}`/`{{CHUNK_CONTEXT}}` substitution and its stripping of literal `{{`/`}}` from `chunkContext` (WSR-02/CSR-08) — that logic continues to apply **only** to the `user` template, never to `systemMessages`. `WorkerLoop`, `HeartbeatScheduler`, `LlamaClient` unchanged.

## 9. Token budget (`DiffSizeValidator`)

`gateway.diff.prompt-reserve` changes meaning: previously "the whole system prompt" (static), now only the `user`-template's fixed wrapper text — recommend lowering the default (verify against the actual rendered template, roughly 2000 → 800). The system-prompt's real size becomes a second, dynamic term:

```java
public int budgetTokens(int systemPromptTokens) {
    int derived = contextWindow - promptReserve - systemPromptTokens - answerReserve;
    return Math.min(maxDiffTokens, Math.max(0, derived));
}
public void assertPromptFits(int systemPromptTokens) {
    if (budgetTokens(systemPromptTokens) < properties.getPrompt().getLimits().getMinDiffBudgetTokens()) {
        throw new PromptTooLargeException(...);   // 422 PROMPT_TOO_LARGE, distinct from DIFF_TOO_LARGE
    }
}
```

`DiffChunker.split(diff)` → `split(diff, systemPromptTokens)`. `rejectIfAbsurdlyLarge`/`absurdlyLargeCeilingTokens` stay at `systemPromptTokens = 0` (cheap, IO-free, deliberately permissive pre-filter — the point of running it before any network call). `max-system-prompt-tokens` (6000) is enforced as one aggregate cap over all sections + preamble/trailer/delimiters, not per-section — this is what `PromptTooLargeException`/422 signals distinctly from an oversized diff, so an operator sees the real cause. `gateway.diff.max-request-body-bytes` is unaffected (its formula only depends on diff-side parameters, and `budgetTokens` only shrinks).

## 10. Error taxonomy (`GlobalExceptionHandler`, all above the generic `Exception` handler)

| Exception | HTTP | code | Trigger |
|---|---|---|---|
| `PromptSourceUnavailableException` | 502 | `PROMPT_RESOLUTION_FAILED` | network/timeout/5xx/401/403 on any prompt-resolution call, or the total-timeout deadline; **coarse and undifferentiated on purpose** (PMR-26 — distinct causes must not form an oracle for project/MR existence across the org) |
| `PromptSourceMissingException` | 422 | `PROMPT_SOURCE_MISSING` | 404 on a **mandatory** (corporate) file with project+ref already proven reachable |
| `PromptSourceInvalidException` | 422 | `PROMPT_SOURCE_INVALID` | file exceeds `max-file-bytes`, invalid UTF-8, contains NUL, empty |
| `PromptTooLargeException` | 422 | `PROMPT_TOO_LARGE` | assembled system prompt exceeds `max-system-prompt-tokens`, or remaining diff budget falls below `min-diff-budget-tokens` |
| `PromptSectionsMissingException` | — (job-level, not HTTP) | `PROMPT_SECTIONS_MISSING` | claim-time: `prompt_bundle_mode = REPO` but zero `CORPORATE_*` rows found — job fails, never runs degraded |

Response bodies carry only operator-configured values (project/path/ref as configured, never token/host/stack/file-content). Corporate-section failures (`PromptSourceUnavailableException`, `PromptSourceMissingException`) always fail `POST /reviews` — not configurable. Project-section failures follow `gateway.prompt.error-handling.on-error`.

## 11. Points of scaling

| Point | Threshold | Action |
|---|---|---|
| GitLab calls per resolve (≤6) | > ~500 Reviews/day or GitLab starts rate-limiting | Enable the optional content-addressed cache `(project, path, commitSha) → content` — immutable by construction (SHA-keyed), no staleness possible, bounded LRU size |
| `POST /reviews` p95 latency | > 2-3s | Same cache (corporate sections are identical across all Reviews) |
| `review_prompt_sections` growth | ~4 rows × 30 Reviews/day ≈ 44k rows/yr | Nothing needed for years; PMR-29 retention job (purge `content`, keep provenance) folds into the existing SR-22 policy |
| Heterogeneous backend fleet | a non-OpenAI-compatible backend appears | `backends.prompt_message_format` column already supports per-backend override |

## 12. Risks carried forward (not solved here, tracked in the threat model)

- Untrusted `PROJECT_*` content reaching the LLM's system role is mitigated (delimitation, sanitization, precedence framing, default-branch-only sourcing) but not eliminated — prompt injection against an LLM judge is a known-hard problem; PMR-01/02/03 raise the bar, they do not close it completely.
- The shared CI token (pre-existing residual, T-21/SR-16) becomes load-bearing for cross-project reads now that `projectId` selects a GitLab read target (PMT-08) — per-project CI tokens are the real fix, tracked as PMR-27 (SHOULD), not blocking this feature.
- Corporate-repo compromise is a supply-chain risk on the review policy itself (PMT-15) — mitigated by scoping the corporate token read-only + project-scoped, but the corporate repo's own branch-protection/approval policy is an operational control outside this codebase (PMR-28).
