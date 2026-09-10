# AppSec SAST Report — `feature/backend-self-registration` (Backend Self-Registration & Backend Admin API)

Scope: two repositories, one feature.

- **Gateway** — `C:\Develop\AIReviewGateway`, `master..feature/backend-self-registration`, HEAD **`7859eaa`**,
  working tree clean. 4 commits (`4c15d75` threat model → `b3ee0d8` implementation → `5148eed` QA env-var
  fix → `7859eaa` QA lifecycle test), 40 files, **+2753 / −51**.
- **Worker** — `C:\Develop\AIReviewWorker`, `master..feature/backend-self-registration`, HEAD **`c232de8`**,
  working tree clean. 6 commits (`2e02911` `CappedBackoff` extraction → `a17bc9c` `backend.url` +
  BSQ-20 validation → `08cd65f` `GatewayClient.announce` → `a89fcd4` `WorkerRunner` announce step →
  `6f66e83` docs → `c232de8` QA `400`-is-fatal fix), 15 files, **+996 / −7**.

Gating spec: `docs/backend-self-registration-threat-model.md` — §4 requirements `BSQ-01..BSQ-24`, §6
release gate (BSQ-01..BSQ-20 blocking MUSTs), §5 the nine mandatory architecture corrections, §2 the
STRIDE table `BST-01..BST-19`. Design: `docs/backend-self-registration-architecture.md` (`BSR-01..BSR-26`).

Method: adversarial read of the full two-repo diff against every blocking MUST, plus a deliberate hunt
for the classes of defect that a security-*functional* pass does not surface — timing side channels,
error-message leakage under response-time analysis, the combined config/env surface, supply chain, and
the *interaction* between requirements that were authored independently. Prior passes (orchestrator
line-by-line on `BackendRegistryService`/`BackendUrlValidator`; qa-engineer against the running
application) were **re-verified by sampling, not trusted**.

---

## Independent evidence collected in this round (not reported to me)

- **Supply chain — both `pom.xml` files are byte-identical to `master`.** `git diff master...HEAD -- pom.xml`
  is empty in the Gateway repo (also for `worker/pom.xml`) and in the Worker repo. The architecture doc's
  "**No `pom.xml` change in either module**" (§0) holds exactly. No new dependency, no version bump, no
  transitive-tree change → no SCA delta to assess for this branch.
- **`V6` is still free.** `git log master --oneline` tip is `0659108`
  (`docs(F-WRS-07)…`, the worker-repo-split merge line) — `master` carries `V1`–`V5` only; re-checked
  after the threat model was written, since branches move. `V6__backend_self_registration.sql` is the
  sole `V6` reachable from any branch in this checkout.
- **Both suites re-run from scratch by me**, tallied from the raw surefire XML rather than from console
  output: **Gateway `tests=1019 failures=0 errors=0 skipped=0`** (`mvn -q test`, exit 0);
  **Worker `tests=203 failures=0 errors=0 skipped=0`** (exit 0). Confirms the QA round's counts exactly.
- **gitleaks 8.21.2**, run locally with each repo's own `.gitleaks.toml`, mirroring the pinned
  `security-gate.yml` invocation (`gitleaks git --no-banner --redact -c .gitleaks.toml`):
  - **Worker**: full history (9 commits) **clean**; worktree **clean**.
  - **Gateway**: full history (229 commits) → 2 findings, both `generic-api-key`, both dated
    **2026-07-13**, in `docs/security/sr-23-ci-gate.md:50` (commit `e5b5edf3`) and
    `src/test/java/…/EventServiceTest.java:61` (commit `8292a1f3`). Both verified
    `git merge-base --is-ancestor <c> master` → **YES**, and neither is in `git rev-list master..HEAD` —
    i.e. **pre-existing on `master`, not introduced by this branch**. Worktree findings (25) are all in
    untracked/ignored paths (`target/surefire-reports/`, a local `.env`, an unrelated
    `GitLab_script/.venv/`). **No secret-shaped content in any line this branch adds.**
- **Semgrep could not be run locally** (no Linux container available on this machine; the CI job runs in
  the `semgrep/semgrep` image with `p/java` + `p/sql-injection` + `p/secrets`). Compensated by a manual
  read of every added line for that ruleset's classes (string-concatenated SQL, `Runtime.exec`,
  reflection, unvalidated redirect, `Pattern` on tainted input) — **nothing found**; and by the
  BSQ-04/BSQ-06/BSQ-24 checks below, which are the two rules the threat model's §6 CI-gate note wanted
  written. `gh` is not installed on this machine, so CI run status could not be queried.
- **Timing measurement of the `422` collapse** (see F-BSR-06). Compiled a probe into
  `com.review.gateway.service` against `target/classes` and called the real package-private
  `BackendUrlValidator.validate(url, Pattern.compile("^192\\.168\\.1\\.\\d+$"))`, measuring the *first*
  (uncached) evaluation of a freshly randomised hostname each time:

  ```
  allowlist MISS, IP literal (no DNS)        :     0 ms
  allowlist MISS, name that does NOT resolve :    46–57 ms   (fresh random label, uncached)
  allowlist MISS, name that DOES resolve     :     0–54 ms
  ```

  The same probe also surfaced F-BSR-08: an unresolvable host is reported server-side as
  `Backend URL host is in a blocked range (loopback/link-local/metadata)`.

---

## Verdict: **PASS-WITH-FINDINGS → requires a `backend-developer` fix round before merge**

The security core of this feature is **well built**. All nine of §5's mandatory architecture corrections
are genuinely implemented, not merely claimed — including the two that were easiest to get wrong
(BSQ-04 bare-origin normalisation enforced *inside* `BackendUrlValidator` so the probe path re-validates
on every use, and BSQ-08's insert-race retry restructured outside the transaction via a
`REQUIRES_NEW` `TransactionTemplate`). BSQ-01, BSQ-03, BSQ-05, BSQ-06, BSQ-07, BSQ-09, BSQ-10, BSQ-11,
BSQ-12, BSQ-13, BSQ-14, BSQ-15, BSQ-17, BSQ-18, BSQ-19, BSQ-20 all verify against the code.

Five findings block: one that widens the error-handling surface of the **entire application** far beyond
this feature (F-BSR-01), one unexplained and actively harmful `docker-compose.yml` change smuggled into
the implementation commit (F-BSR-02), one blocking MUST that was **not attempted at all** (F-BSR-03 /
BSQ-16 — no documentation was written in the Gateway repo), and two cross-repo status-code defects that
two application-layer passes did not catch because each half looks correct in isolation (F-BSR-04,
F-BSR-05).

None of the five is an SSRF or authorisation defect. The registry write path itself is sound.

| ID | Severity | Blocks merge? |
|---|---|---|
| **F-BSR-01** | **Medium** | **Yes** |
| **F-BSR-02** | **Medium** | **Yes** |
| **F-BSR-03** | **Medium** | **Yes** (BSQ-16 is a blocking MUST, unimplemented) |
| **F-BSR-04** | **Medium** | **Yes** |
| **F-BSR-05** | **Medium** | **Yes** |
| F-BSR-06 | Low–Medium | No — strongly recommended, 2-line fix |
| F-BSR-07 | Low | No |
| F-BSR-08 | Low | No |
| F-BSR-09 | Low | No (docs) |
| F-BSR-10 | Low | No |
| F-BSR-11 … F-BSR-16 | Info | No |

---

## Findings

### F-BSR-01 — **Medium, blocking**. A feature-local convenience handler was installed application-wide

*CWE-209 (information exposure through an error message) / CWE-544 (missing standardized error handling)
/ CWE-778 (insufficient logging) — A09:2021. Relates to **BSQ-13** and to the non-regression item
**SR-17**; **not anticipated by the pre-implementation model**.*

**Where:** `src/main/java/com/review/gateway/controller/GlobalExceptionHandler.java:251-260`.

```java
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage()));
}
```

**What it was for:** exactly two throws, both in `BackendRegistryService.upsertByAdminTx`
(`:255`, `:258`) — `"url is required when registering a new backend"` /
`"model is required when registering a new backend"`. The handler's own javadoc says
*"Message is a fixed, non-reflecting string"*, which is true **of those two throws** and false **of the
handler**.

**What it actually does.** `@ControllerAdvice` handler selection is by exception type, not by which
controller threw — so this now intercepts **every `IllegalArgumentException` from every endpoint in the
application** (`/reviews`, `/jobs/*`, the admin surface, the webhook path). `NumberFormatException`,
`PatternSyntaxException` and `Base64`'s decode failure are all `IllegalArgumentException` subclasses, and
JDK/Spring/Hibernate internals throw IAE with the offending value embedded in the message
(`For input string: "…"`, `No enum constant com.review.gateway.model.enums.…`). Three consequences,
each a regression against behaviour that existed on `master`:

1. **SR-17 is broken for a whole exception class.** The `Exception` backstop at `:287` documents the
   contract — *"the client always gets the same generic body regardless of the underlying cause"*. Any
   IAE now returns `ex.getMessage()` verbatim instead. That is precisely the `getRejectedValue()`-style
   reflection BSQ-13 forbids, arriving by a different route.
2. **The `log.error` disappears.** The backstop logs the exception with its stack trace; this handler
   logs nothing at all. A genuine server-side fault that happens to be an IAE now produces a `400` and
   **zero server-side evidence** — on an ADMIN endpoint whose only audit trail is the log file (BSTB-AUDIT).
3. **`5xx` becomes `4xx`, which this branch made load-bearing.** The Worker's new rule (F-BSR-05,
   `GatewayClient.java:186`) treats `400` as *fatal, never retry, fail startup*. An internal Gateway
   fault reclassified to `400` therefore kills Worker startup permanently instead of being retried.

There is at least one unguarded `Integer.parseInt` on non-constant input in the codebase
(`CommentRenderer.java:333`, on a regex-extracted diff hunk-header line number — `\d+` guarantees digits
but not that they fit in an `int`); `CommentParser.java:222` shows the codebase's own correct pattern for
this (a local `catch (NumberFormatException)`). I am not claiming a specific reachable exploit today —
the finding is that an unbounded-scope handler was added to serve two constant strings, and that its
scope was never assessed.

**Fix (either is fine; the first is the smaller diff):**
- Give the two throws their own exception — e.g. reuse the `BackendUrlRejectedException` shape as a
  `BackendRegistryValidationException extends RuntimeException` — map *that* to `400`, and **delete the
  `IllegalArgumentException` handler entirely**. Or
- keep the handler but emit a **fixed** body (`"Request validation failed"`, never `ex.getMessage()`)
  **and** add the missing `log.warn(…, ex)`.

Add a regression test asserting that an arbitrary IAE raised from a non-`/backends` endpoint still yields
`500 INTERNAL_ERROR` with a generic body.

---

### F-BSR-02 — **Medium, blocking**. Unrelated, unexplained `docker-compose.yml` change that ships the exact DoS condition BSQ-03 exists to prevent

*CWE-1188 (insecure default resource initialisation) / CWE-1104 — A05:2021. Relates to **BST-08 / BSQ-03**;
**not anticipated** (it is scope creep, not a design consequence).*

**Where:** `docker-compose.yml:128-149`, introduced by `b3ee0d8` (the implementation commit) with no
mention in the commit message, no basis in the architecture doc, the threat model, or §10's
implementation checklist.

```yaml
      LLAMA_URL_3: ${LLAMA_URL_3:?set LLAMA_URL_3, e.g. http://192.168.1.103:8000}
      LLAMA_URL_4: ${LLAMA_URL_4:?set LLAMA_URL_4, e.g. http://192.168.1.104:8000}
      LLAMA_URL_5: ${LLAMA_URL_5:?set LLAMA_URL_5, e.g. http://192.168.1.105:8000}
      LLAMA_URL_6: ${LLAMA_URL_6:?set LLAMA_URL_6, e.g. http://192.168.1.106:8000}
…
         ('llama-03', '$$LLAMA_URL_2', '$$LLAMA_MODEL', 1),
         ('llama-04', '$$LLAMA_URL_2', '$$LLAMA_MODEL', 1),
         ('llama-05', '$$LLAMA_URL_2', '$$LLAMA_MODEL', 1),
         ('llama-06', '$$LLAMA_URL_2', '$$LLAMA_MODEL', 1)
```

Four separate defects in nine lines:

1. **Four new *mandatory* variables.** `:?` is compose's fail-if-unset form, so this **breaks
   `docker compose up` for every existing deployment** that supplies only `LLAMA_URL_1`/`_2`. That is an
   availability regression on the reference deployment, introduced by a feature whose entire design
   premise (BSR-11, BSQ-05, the `@ConditionalOnProperty`) is *"a Gateway upgrade must never change
   behaviour for a deployment that does not opt in"*.
2. **The four new variables are then discarded.** `llama-03`…`llama-06` are all seeded with
   `$$LLAMA_URL_2`. The operator is forced to supply four addresses that are never used, and gets four
   registry rows pointing at one host.
3. **It ships BST-08's threshold as the default.** The threat model's own arithmetic:
   `BackendHealthChecker.runPass` is serial over `ACTIVE`+`SUSPECT` rows with
   `gateway.backend.read-timeout: 10s` and `backend-health-interval: 60s`, so *"~6 unreachable rows
   exceed one 60 s pass"* and the WOC-17 re-entrancy guard degrades into a skip-storm that also stops
   `checkQueueStalled`/`checkStuckQueuedJobs`. The reference compose file now seeds **exactly six** rows,
   four of them redundant. BSQ-03 was written to stop an *attacker* creating this state; this creates it
   by default, with no attacker.
4. **The service comment is now false in a way this branch is responsible for.** `docker-compose.yml:117-118`
   still reads *"registers **both** backend rows (DEPLOYMENT.md §5 — there is no REST endpoint for this,
   only a direct SQL insert)"*. Architecture §9 flagged this exact line for rewrite.

**Fix:** revert `docker-compose.yml` to its `master` state on this branch. If more than two seed
backends are genuinely wanted, that is a separate change with its own justification — and it must not
default to six, must not make the extra URLs mandatory, and must not point them all at one host.

---

### F-BSR-03 — **Medium, blocking**. BSQ-16 was not implemented; the Gateway's operator documentation still tells operators this feature does not exist

*CWE-1059 (insufficient documentation of a security-relevant control) / CWE-778 — A09:2021.
**BSQ-16 is on the §6 blocking-MUST list.** Also drops the documentation halves of BSQ-05, BSQ-22,
BSQ-INH-4 and BSQ-INH-5.*

**Where:** `git diff --stat master...HEAD -- README.md DEPLOYMENT.md CLAUDE.md` is **empty**. The only
`docs/` change on the branch is the threat model itself (+252).

BSQ-16 (blocking MUST) reads: *"`DEPLOYMENT.md` states explicitly that backend-registry mutations are
audited **in log files only** (with the reason: `review_events.review_id` is a `NOT NULL` FK), that
`backends.updated_at` is overwritten by the next health probe and is not an audit trail, and therefore
that Gateway log retention is the audit retention for this feature."* Verified absent:
`grep -c 'announce\|announced_by\|self-registration\|BACKEND_SELF_REGISTRATION' README.md DEPLOYMENT.md`
→ **0 and 0**. This is not a wording quibble — BST-12's whole point is that a hostile repoint of a
production backend leaves *no durable trace in PostgreSQL* and is erased from `backends.updated_at`
within ≤60 s by the next successful probe. If nobody tells the operator that, log retention gets set to
whatever the default is and the feature's only forensic record is silently discarded.

Simultaneously, the shipped operator docs now actively mislead:

| File:line | Says | Reality after this branch |
|---|---|---|
| `README.md:258` | "**Backend (llama-server) registration has no REST endpoint.** … there is no `POST /backends` or admin UI — register a backend with a direct SQL statement" | `POST /backends`, `DELETE /backends/{name}`, `POST /backends/announce` all exist |
| `DEPLOYMENT.md:491` | "**STUB — not implemented: no admin API for backend registration.**" | Implemented |
| `DEPLOYMENT.md:1500` | "`# 3. Register a backend (§5 -- no REST endpoint, direct SQL only)`" | False |

Also missing and individually required:

- **BSQ-05, second half** — the startup failure message correctly states that passing the sentinel check
  proves only non-universality (verified at `GatewayProperties.java:344-350`), but *"and the
  `DEPLOYMENT.md` text must both state plainly…"* is not done.
- **BSQ-INH-4** — the knowing deviation from `SR-15` (`http://` backend URLs accepted) *"must be recorded
  as an explicit deviation from a MUST in `DEPLOYMENT.md` and `README.md` §4.3 — not as an oversight"*.
  Not recorded anywhere. As it stands it **is** an oversight in the record.
- **BSQ-INH-5 / BST-15** — "parking a backend at `MAINTENANCE`/`OFFLINE` does not protect its `url` from
  announce" is documented in `BackendRegistryService`'s code (`:224-229`, correctly) but nowhere an
  operator will read it.
- **BSQ-22, `DEPLOYMENT.md` half** — the migration comment **is** present and accurate (see the positive
  verification below); the matching note on `DEPLOYMENT.md`'s `GRANT` block is not.
- New config surface undocumented: `BACKEND_SELF_REGISTRATION_ENABLED`, `BACKEND_MAX_BACKENDS`, the
  changed status of `BACKEND_ALLOWED_HOST_PATTERN` (now a **hard startup requirement** under the flag),
  Worker `BACKEND_URL` ⟷ Gateway `allowed-host-pattern` as a new coupled cross-repo pair, the new error
  codes `BACKEND_NAME_TAKEN` / `BACKEND_URL_REJECTED` / `BACKEND_REGISTRY_FULL`, the new `announcedBy`
  field in `GET /backends`, and the two new `GET /metrics` counters.

The Worker repo did do its half (`README.md` +37, `.env.example` +9, `docker-compose.yml` +5 — all
verified accurate, including the `llama.url` vs `backend.url` distinction). The Gateway repo did none.

**Fix:** write architecture §9's documentation table. BSQ-16's three sentences are the only genuinely
blocking part; the rest is required for the feature to be operable at all.

---

### F-BSR-04 — **Medium, blocking**. `BACKEND_REGISTRY_FULL` shares `422` with `BACKEND_URL_REJECTED`, so BSQ-03's DoS *mitigation* becomes a cheaper, durable DoS on Worker startup

*CWE-703 (improper check of exceptional condition) / CWE-770 — A04:2021. **New: an unmodelled
interaction between BSQ-03 and BSQ-18**, which were authored independently.*

**Where:** `GlobalExceptionHandler.java:85-90` (`BackendRegistryFullException` → `422`) ×
`GatewayClient.java:186-189` (Worker: `422` ⇒ `REJECTED_FATAL`) ×
`WorkerRunner.java:151-154` (the `default ->` arm of the fatal-cause `switch`).

BSQ-18 makes `422` terminal on the sound reasoning that it is *"this Worker's own misconfiguration and
never self-heals"*. That reasoning is true of `BACKEND_URL_REJECTED`. It is **false** of
`BACKEND_REGISTRY_FULL`, which is a Gateway-side capacity condition the Worker has no influence over.
Because BSR-19 deliberately forbids the Worker from reading the response body (*"never the response
body, which is Gateway-controlled text"*), the Worker cannot tell the two apart — so it takes the
`default ->` arm and dies at startup with:

> `Backend self-registration failed (422): backend.url was rejected by the Gateway's host allowlist, or was not a bare origin`

Two consequences:

1. **Wrong diagnosis, guaranteed.** An operator who legitimately reaches `max-backends` (default 16)
   spends their time debugging `BACKEND_URL`/`allowed-host-pattern`, which are both correct.
2. **It hands a fleet-token holder a better attack than the one BSQ-03 closed.** BSQ-03 bounds the
   probe-loop DoS at 16 rows. But an attacker who *fills* the registry to the cap (16 announces of
   distinct names, authenticated, unrate-limited — `SR-20` is still an open `SHOULD`, restated in
   BSQ-INH-2) now causes **every Worker whose row does not already exist to fail startup permanently**.
   Existing Workers keep working (the update branch is exempt from the cap — verified at
   `BackendRegistryService.java:151-158`, and `registryFullDoesNotBlockAnUpdateOfAnExistingOwnedRow`
   pins it), so the blast radius is "no new or replaced host can ever come online", with a misleading
   error and no self-healing. Note also that the cap can never be relieved by the product's own tooling:
   `DELETE /backends/{name}` is a soft decommission and `backendRepository.count()` still counts the
   `OFFLINE` row (F-BSR-13).

I am recording an explicit disagreement with the prior review pass here: the wire contract *was*
cross-checked and both `422` codes *were* found to bucket into `REJECTED_FATAL`. That check confirmed
the mapping is internally consistent; it did not ask whether "fatal" is the semantically right bucket
for a capacity condition. It is not.

**Fix (smallest correct change):** map `BackendRegistryFullException` to a status the Worker already
treats as retryable-with-backoff — **`503 Service Unavailable`** is the honest code (it *is* a temporary
server-side capacity condition) and needs **no Worker change at all**: `503` falls through
`GatewayClient.announce`'s `mapServerError` → `GatewayUnavailableException` →
`WorkerRunner.announceWithRetry`'s capped-backoff loop, which is exactly the desired behaviour (the
Worker keeps retrying and comes up by itself the moment the operator raises the cap, while the Gateway's
`REGISTRY_FULL` counter and cap WARN — both already implemented — carry the signal). If a `4xx` is
preferred, use BSQ-03's other stated option (`409`) *and* split `WorkerRunner`'s `switch` so `409` no
longer implies "copy-pasted `BACKEND_ID`". Do not leave both causes on `422`.

---

### F-BSR-05 — **Medium, blocking**. A `401` from `POST /backends/announce` is retried forever; the fatal/non-fatal split is a closed allowlist with a *transient* default

*CWE-754 (improper check for unusual conditions) — A04:2021. Relates to **BSQ-18 / BST-14a**;
**new**, and directly in the blast radius of the QA round's own `400` fix.*

**Where:** `src/main/java/com/review/worker/gateway/GatewayClient.java:172-193`.

The classification is:

```java
if (statusCode == 403 || statusCode == 404)               -> REJECTED_NONFATAL   // WARN, legacy mode
if (statusCode == 400 || statusCode == 409 || statusCode == 422) -> REJECTED_FATAL  // fail startup
throw mapServerError("announce", e);                      // -> GatewayUnavailableException
```

`mapServerError` yields `GatewayUnavailableException`, which `WorkerRunner.announceWithRetry`
(`:104-110`) retries **indefinitely** with capped backoff while the worker-loop never starts. So **every
4xx not in the two explicit lists defaults to "transient, retry forever"** — including **`401`**.

`401` is what `SecurityConfig`'s `authenticationEntryPoint` returns for a missing or wrong bearer token
(confirmed by `SecurityMatrixSelfRegistrationEnabledTest:87`, which asserts `401` for the no-token cell).
A wrong `GATEWAY_API_KEY` is the single most common Worker misconfiguration there is, and it is
categorically non-self-healing — the same category as `409`/`422`. Today it produces a Worker that never
starts, never exits, and emits `Gateway unavailable while announcing this backend; retrying in N ms` —
i.e. exactly the *"hanging Worker startup indefinitely behind a misleading 'Gateway unavailable' WARN"*
failure the QA round's `c232de8` commit message describes fixing for `400`. The fix was applied to the
symptom (`400`) rather than to the rule that produced it.

This is worse than pre-branch behaviour: before this feature a bad token merely made `/jobs/claim` fail
in a running Worker; now it wedges startup before the loop exists.

**Fix:** invert the default. `403`/`404` are the *only* codes that mean "this Gateway does not do
self-registration" (BSQ-18's whole rationale) and `429` is the only genuinely transient `4xx`; every
other `4xx` is by definition a client-side condition that will not self-heal. Make it:

```java
if (statusCode == 403 || statusCode == 404) -> REJECTED_NONFATAL
if (statusCode == 429)                      -> mapServerError(...)   // transient, retry
if (statusCode >= 400 && statusCode < 500)  -> REJECTED_FATAL
// 5xx / ResourceAccessException unchanged
```

with `401` added to `WorkerRunner`'s cause `switch` (*"GATEWAY_API_KEY is missing or is not the Gateway's
WORKER token"*). This subsumes the `c232de8` `400` fix rather than sitting beside it, and it composes
with F-BSR-04's `503` recommendation (a `5xx` stays transient). Add a `401` case to
`GatewayClientTest`/`WorkerRunnerTest`.

---

### F-BSR-06 — **Low–Medium**. DNS resolution runs *before* the allowlist regex, so the allowlist provides zero protection against the DNS primitive, and the `422` collapse is undone by wall-clock

*CWE-208 (observable timing discrepancy) / CWE-918 / CWE-203 — A10:2021. Relates to **BST-05 /
BSQ-INH-2**, whose "not closable" framing this finding narrows.*

**Where:** `src/main/java/com/review/gateway/service/BackendUrlValidator.java:100-107` — `isBlockedHost(host)`
(which calls `InetAddress.getAllByName`) at `:100`, the allowlist `Pattern` match at `:105`.

BSQ-13's message collapse is correctly implemented (verified: `BackendRegistryService.java:104-111`
throws the single fixed `"Backend URL was rejected"` for every cause, and
`announceCollapsesEveryUrlRejectionCauseToTheSameFixedMessage` pins it). But because the DNS lookup runs
*first*, the collapsed body is undone by response time. Measured on the real validator (see evidence
section): a `422` for an allowlist-missing **IP literal** returns in **0 ms**; a `422` for an
allowlist-missing **hostname** costs **46–57 ms** of resolver round-trip on a machine with a fast local
resolver. `getAllByName` has **no timeout**, so against a slow or attacker-controlled authoritative
nameserver that figure is unbounded — BST-05's *"a hostile authoritative nameserver holds a Tomcat
request thread open"* is live, and reachable for **any** hostname, not only allowlisted ones.

BSQ-INH-2 accepts the DNS channel on the grounds that *"the DNS lookup happens before any response is
chosen"*. That is true of the response, but not of the ordering: the allowlist match is a pure in-memory
regex against an already-length-capped host. Running it **first** means a fleet-token holder can only
provoke a resolver query for hosts the operator's own pattern already admits. For the
`DEPLOYMENT.md`-shaped pattern `^192\.168\.1\.\d+$` that is **zero DNS queries ever** (IP literals do not
reach the resolver), which closes the out-of-band QNAME-exfiltration channel and the no-timeout
thread-pin almost entirely, and flattens the timing oracle to 0 ms for every rejection.

Reordering is **outcome-neutral** — both predicates must pass for a URL to be accepted, so swapping them
changes no accept/reject decision, only which constant message is produced for a URL that fails both
(and the WORKER path collapses those anyway).

**Fix:** move the `pattern.matcher(...).matches()` block (`:104-107`) above the `isBlockedHost` block
(`:100-102`). Add a comment binding the ordering to this finding so nobody "tidies" it back, and a test
asserting that a non-allowlisted hostname is rejected without any name resolution (e.g. via a
`InetAddress`-free assertion on a host guaranteed to be slow, or simply a wall-clock bound). BSQ-INH-2's
text should then be amended to say the DNS channel is bounded by the allowlist rather than unbounded.

---

### F-BSR-07 — **Low**. The BSQ-02 repoint WARN logs a DB-sourced legacy URL verbatim, including any embedded credentials

*CWE-532 (insertion of sensitive information into a log file) — A09:2021. Relates to **BSQ-14 / SR-12 /
T-09**; **new**.*

**Where:** `src/main/java/com/review/gateway/service/BackendRegistryService.java:185, 214-216`.

```java
String previousUrl = backend.getUrl();
…
log.warn("Backend {} via self-announce (name={}, workerId={}, oldOrigin={}, newOrigin={})",
        …, safe(previousUrl), safe(normalizedUrl));
```

`newOrigin` is the BSQ-04-normalised bare origin — correct. `oldOrigin` is read straight from the
`backends` row and passed only through `safe()` → `TextSanitizer.sanitizeSingleLine`, which (verified at
`TextSanitizer.java:56-72`) strips `Cc`/`Cf`/`Zl`/`Zp` and `<`/`>` and caps length — it does **not**
strip userinfo, path or query. A pre-`V6` row inserted by raw SQL (the documented registration path until
this branch) is not bound by BSQ-04, so a row of the form
`http://svc:S3cr3t@192.168.1.50:8080/probe` is entirely possible, and the credential lands in the log
file verbatim on the announce that replaces it.

BSQ-14 anticipated the shape of this ("every value read from a DB row rather than from a just-validated
DTO passes through `TextSanitizer`") and the developer implemented that instruction faithfully — the gap
is that `sanitizeSingleLine` is a *control-character* sanitiser, not a *URL* sanitiser, and BSQ-14's
`scheme://host[:port]` rule was only enforced for the freshly-validated value.

`BackendRegistryServiceTest:475`
(`aLegacyRowsControlCharactersInAnnouncedByProduceASingleSanitizedLogLine`) covers the control-character
half only.

**Fix:** normalise `previousUrl` to `scheme://host[:port]` before logging — a small
`bestEffortOrigin(String)` helper that parses with `URI` and falls back to a fixed
`"<unparseable>"` string on failure (never the raw value). Note this is *stricter* than BST-11's
forensics concern asks for; if the path/query of a legacy row is wanted for evidence, log it as a
separate `legacyPathPresent=true` boolean rather than the substring. Add a test seeding a row whose
`url` carries userinfo and asserting the secret never appears in the captured log output.

---

### F-BSR-08 — **Low**. An unresolvable host is reported as "in a blocked range (loopback/link-local/metadata)"

*CWE-1295 (debug messages revealing unnecessary/incorrect information) — A09:2021. Relates to
**BSR-04 / BST-11 / BSQ-14**; mechanism pre-exists on `master`, **user-visible impact is new**.*

**Where:** `src/main/java/com/review/gateway/service/BackendUrlValidator.java:100-102, 129-132`.

`isBlockedHost` returns `true` both for a genuinely blocked address *and* for
`UnknownHostException` — fail-closed, which is correct behaviour. But the caller then throws the single
message `"Backend URL host is in a blocked range (loopback/link-local/metadata)"`. Empirically confirmed:
`http://nonexistent-9f3a2b1c.example.com:8080` (which resolves to nothing at all) produces exactly that
string.

Two places where this now matters, both created by this branch:

- **The ADMIN `422` body** (`BackendRegistryService.java:267`) preserves the validator's own message,
  justified by BSR-04/§3.3 as *"an operator registering a backend needs to know **which** rule it
  failed"*. For the single most likely admin mistake — a typo'd hostname — it names the wrong rule.
- **The server-side WARN** (`BackendRegistryService.java:105-106`) is the *only* record of a rejected
  announce (BSTB-AUDIT). It cannot distinguish a fat-fingered hostname from a deliberate probe at
  `169.254.169.254`. BST-11's forensics argument applies directly.

**Fix:** split the two branches in `isBlockedHost` — return a small enum or throw two distinct constant
messages (`"Backend URL host does not resolve"` vs. the existing blocked-range one). Both remain
compile-time constants that reflect no input, so BSQ-13's constraint is untouched, and the WORKER path
still collapses both to the same `422` body. (Sequencing note: after F-BSR-06's reorder, an unresolvable
host is only reachable when it already matched the allowlist, which makes the distinction *more*
interesting, not less.)

---

### F-BSR-09 — **Low**. BSQ-04 is a silent breaking change for already-registered rows; nothing warns the operator

*CWE-1059 — A05:2021. Relates to **BSQ-04 / BST-19**; the threat model anticipated the *path* case and
framed it as desirable, but not the trailing-slash case nor the absence of any upgrade note.*

BSQ-04 is enforced at **probe time** as well as write time (`BackendProberImpl.java:42-48` — verified,
and this is the right design: it preserves SR-10's "validated on every use"). The consequence is that any
existing `backends.url` that is not a bare origin **starts failing every health probe the moment this
JAR is deployed** — the row goes `ACTIVE` → failing streak → `SUSPECT` after `failure-grace`, and stops
receiving dispatch. `V6__backend_self_registration.sql` neither checks nor normalises existing rows, and
there is no startup warning and no upgrade note (F-BSR-03).

`http://host:8080/` — a **trailing slash**, which is what a URL-typed value in a `.env` or a copy-paste
from a browser most often looks like — is rejected (`rawPath` is `"/"`, non-empty; confirmed against
`BackendUrlValidator.java:89-92` and the developer's own test vectors). Every `INSERT INTO backends`
example in the shipped docs is a clean bare origin (`DEPLOYMENT.md:498`, `:1372`, `:1502`;
`README.md:262`), so this is not doc-induced — but hand-typed production rows are not bound by the
examples.

**Fix:** a one-line upgrade note in `DEPLOYMENT.md`'s V6/upgrade section and a comment in
`V6__backend_self_registration.sql`, both naming the check an operator can run before deploying:

```sql
SELECT name, url FROM backends WHERE url ~ '[/?#]' AND url !~ '^https?://[^/?#]+$';
```

No code change needed — the probe-time rejection *is* the intended behaviour and its log line names the
cause.

---

### F-BSR-10 — **Low**. `WorkerProperties.validateBackendUrl` does not check that a host is present

*CWE-20 — A03:2021. Relates to **BSQ-20**, whose stated purpose is "fail at startup with a precise
message instead of collecting an opaque `422`".*

**Where:** `C:\Develop\AIReviewWorker\src\main\java\com\review\worker\config\WorkerProperties.java:210-243`.

`isLoopbackHost(null)` returns `false` (`:352-355`, null-safe) and `isBareOrigin` only checks
path/query/fragment/userinfo — so a value whose authority `java.net.URI` cannot parse as a host
(`http://foo_bar:8080`, `http://:8080`) passes all four BSQ-20 rules. The Gateway then rejects it with
`"Backend URL has no host"` → `422` → which after this branch is **fatal**, so the Worker dies at startup
anyway — but with `WorkerRunner.java:151-152`'s wrong hint (*"rejected by the Gateway's host allowlist,
or was not a bare origin"*).

**Fix:** one guard before the loopback check —
`if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalStateException("backend.url has no host — refusing to start");`
Matches the Gateway's rule exactly, names the property, never echoes the value.

---

### F-BSR-11 — Info. `decommissionTx` omits the `SET LOCAL lock_timeout` the other two write paths apply

`BackendRegistryService.java:322-334` takes `findByNameForUpdate` (a `PESSIMISTIC_WRITE` row lock)
without calling `applyLockTimeout()`, which `announceTx` (`:143`) and `upsertByAdminTx` (`:250`) both do.
The comment on `applyLockTimeout` (`:347`) states its purpose — *"CSR-17-style bound on how long the
pessimistic lock wait can pin a Hikari connection"*. ADMIN-only path, so the exposure is an operator
hanging their own request behind a health-checker phase-C transaction (short by construction, WOC-14), but
the inconsistency will read as an oversight to the next person. One line.

### F-BSR-12 — Info. Audit log lines are emitted inside the transaction that may still roll back

`BackendRegistryService.java:169-171` (the INSERT INFO) and `:214-216` (the BSQ-02 repoint WARN) both fire
before their `TransactionTemplate` commits. If the commit then fails (lock timeout at commit, connection
loss), the log asserts a registration/repoint that did not persist. Ordinarily negligible; here it is
worth recording because BSTB-AUDIT establishes that **these log lines are the entire audit trail** for
this feature — a false positive in the only audit record is a different thing from a false positive in a
convenience log. If tightened: move the log emission into a `TransactionSynchronization`
`afterCommit` callback, or accept and document it. The metrics counters (`:107`, `:156`, `:180`, `:194`,
`:213`) have the same property and are correctly non-transactional by design (BSQ-15).

### F-BSR-13 — Info. `max-backends` counts soft-decommissioned rows

`BackendRegistryService.java:153` uses `backendRepository.count()` — all rows, including `OFFLINE` ones
that `DELETE /backends/{name}` produced and that nothing will ever probe. Security-wise this is
fail-closed and therefore correct (it is *stricter* than BST-08's probe-loop concern, which only counts
`ACTIVE`+`SUSPECT`). Operationally it is a slow ratchet: because §5 offers no hard delete by design, the
cap is consumed permanently by every host replacement, and at 16 replacements self-registration stops
with the misleading error of F-BSR-04. Either count only `ACTIVE`/`SUSPECT`/`MAINTENANCE`, or document
that raising `BACKEND_MAX_BACKENDS` is the supported remedy.

### F-BSR-14 — Info. BSQ-12's literal "same property-name string in both places" assertion was not written

BSQ-12 asks for *"a test asserts the property name string is identical in both"*. No such test exists.
The implementation is arguably better than the requirement: `SecurityConfig.java:48` reads the **typed**
accessor `properties.getBackend().getSelfRegistration().isEnabled()` rather than a duplicated string,
while `BackendAnnounceController.java:30` uses
`@ConditionalOnProperty(prefix = "gateway.backend.self-registration", name = "enabled", havingValue = "true")`
— so there is no pair of strings to compare, and the two can only disagree through relaxed binding.
Behavioural equivalence is proven far more strongly than a string assertion would, by
`SecurityMatrixSelfRegistrationEnabledTest` (flag on) and `SecurityMatrixTest:300` (flag off) running
against a real `@SpringBootTest` context. **BSQ-12's intent is met**; recording the literal gap so the
next reader does not re-derive this.

### F-BSR-15 — Info (process). The architecture doc was never force-added

`git status --porcelain --ignored docs/backend-self-registration-architecture.md` → `!!` (ignored,
untracked). `4c15d75` force-added the threat model only. Every prior feature in this repo force-added
both. The design doc for this feature currently exists only in one working tree and is one `git clean`
from gone. `git add -f docs/backend-self-registration-architecture.md` in the fix round.

### F-BSR-16 — Info. Tracked SHOULDs not done (all correctly non-blocking)

- **BSQ-23** — the registry size was **not** added to `BackendHealthChecker.java:91`'s WOC-17 skip WARN
  (verified: the line is unchanged from `master`).
- **The two repo-local Semgrep rules** from §6's CI-gate note (RestClient URI built by concatenation from
  a DB-sourced value; non-constant `Pattern.compile`/`String.matches` on a `@RestController` path) were
  not added — there is no `.semgrep/` directory in either repo. I verified both properties by hand
  instead (see BSQ-04/06/07/24 below), so the rules would be regression guards, not discoveries.
- **BSQ-21** — verified that no *production* code path deletes a `backends` row (`grep` finds
  `backendRepository.deleteAll()` in test `@AfterEach` teardown only), so the invariant holds today; the
  `REVOKE DELETE ON backends` recommendation is correctly present in the `V6` migration comment but not
  in `DEPLOYMENT.md` (part of F-BSR-03).

---

## Blocking-MUST checklist (BSQ-01..BSQ-20)

| BSQ | Verdict | Evidence |
|---|---|---|
| **BSQ-01** first-claim may take ownership but not repoint | ✅ **PASS** | `BackendRegistryService.java:184-196` — `firstClaim && urlChanging` ⇒ `nameTaken()`, **zero mutation** (the `setUrl`/`setModel`/`save` block is after the early return). Tests `firstClaimOfAnUnownedRow_differentUrl_isBlockedAsMisconfigurationRowByteIdentical`, `…_identicalUrl_claimsOwnership`, `ownerReannouncingWithANewUrl_urlIsUpdated` — all three rows of the requirement. Re-verified independently of the orchestrator's spot-check. |
| **BSQ-02** dedicated WARN + counter on `NULL→value` and any url change | ✅ **PASS** | `:210-216` — a distinct WARN carrying `name`, `workerId`, old and new origin, plus `incrementBackendUrlRepointed()`, structurally separated from the routine `INFO`/`DEBUG` arms. `ownershipClaimIncrementsTheUrlRepointedCounter`, `unchangedReannounceDoesNotIncrementTheUrlRepointedCounter`. See F-BSR-07 for the `oldOrigin` caveat. |
| **BSQ-03** registry cap on the INSERT branch only | ✅ **PASS** (mapping defect ⇒ F-BSR-04) | `:151-158`, inside the same transaction, before `save()`. `max-backends` default **16** (`GatewayProperties.java:1178`). Admin path exempt — `registryFullCapDoesNotApplyToTheAdminPath`. |
| **BSQ-04** bare-origin normalisation, in the validator, both paths | ✅ **PASS** | `BackendUrlValidator.java:76-98` rejects userinfo/path/query/fragment; `:109-111` returns the normalised origin; both write paths persist the **return value** (`BackendRegistryService.java:103`, `:264`), never the raw input. `announcePersistsTheNormalizedBareOriginNotTheRawInput`. Enforced at probe time too (BSQ-07). |
| **BSQ-05** IP-literal + randomised sentinels, grouped | ✅ **PASS, and better than specified** | `GatewayProperties.java:290-352`. The grouped (per-shape) check is a genuine improvement over the requirement's flat list — a flat AND would never flag `^[0-9.]+$`. Covers all seven mandated IP literals plus a single-label group plus a per-boot random label/TLD. `GatewayPropertiesBackendSelfRegistrationValidationTest` covers `.*`, `.+`, `[\s\S]*`, `(?s).*`, `^[0-9.]+$`, `^[^.]*$`, blank; `ApplicationYamlBootTest` proves BST-03's own example through a ConfigData-driven context (see the QA-test assessment below). Startup message states plainly that passing "only proves the pattern is not universal for that shape, never that it is narrow enough". `DEPLOYMENT.md` half ⇒ F-BSR-03. |
| **BSQ-06** compile once + ReDoS budget + 255-char host cap | ✅ **PASS** | `GatewayProperties.java:211-231` compiles once into `Backend#compiledAllowedHostPattern` (no public setter — deliberately not a binder target) and `validateAllowedHostPatternOnStartup()` runs **unconditionally**, so a `PatternSyntaxException` refuses startup even with the flag off, exactly as required. `runBacktrackingBudgetProbe` (`:236-268`) uses a 100 ms `Future.get` on a **daemon** thread and abandons rather than joins — the right call, since `java.util.regex` has no cooperative cancellation, and the comment says so. Host cap at `BackendUrlValidator.java:51, 84-86`, applied **before** the regex. |
| **BSQ-07** probe-time re-validation binds the concatenation | ✅ **PASS** | `BackendProberImpl.java:42-52` — `validate()` now *returns* the origin and the concat uses that return value, not `backend.getUrl()`. Both mandated tests present: `legacyRowWithAPathIsRejectedBeforeAnyHttpCall` and `legacyRowWithACurlyBraceNeverThrowsFromTheHttpClientsUriTemplateParser` (the `RestClient.uri(String)`-is-a-template trap). |
| **BSQ-08** insert-race retry outside the transaction | ✅ **PASS** | `BackendRegistryService.java:100-140`. Non-`@Transactional` orchestrator; `doAnnounceOnce` runs the whole read-modify-write in a `PROPAGATION_REQUIRES_NEW` `TransactionTemplate` and catches `DataIntegrityViolationException` from the **completed** call; exactly one retry; a second failure is an explicit `IllegalStateException` ⇒ `500`, not a loop. Find-then-`save()`, no native upsert. `concurrentAnnouncesOfTheSameBrandNewNameBothSucceedExactlyOneInsert` exercises it. The `TransactionTemplate`-over-`@Transactional` choice is correctly justified in the constructor comment (`:72-76`). |
| **BSQ-09** explicit hard-coded field set on the WORKER path | ✅ **PASS** | `:164-165` and `:203-207` — direct setter calls only; no mapper, no reflection, no `BeanUtils`. `announceNeverTouchesStatusCapacityOrModeColumnsOnAnExistingRow` and `announceOntoAParkedRowNeverResurrectsIt`. `AnnounceBackendRequest` has no `status`/`capacity`/mode field at all, so those columns are unreachable **by construction**, which is what the requirement asked for. |
| **BSQ-10** documented as a misconfiguration guard, guard-language test names | ✅ **PASS** | Class javadoc `:36-43`, `Backend.java:87-95`, `V6` column comment, and the inline `:189-192` all say "misconfiguration guard, never an authorization boundary" citing T-03/T-15/WT-16. Test names use guard language (`differentWorkerId_isBlockedAsMisconfiguration_notAuthorization`). I grepped for any other control whose argument leans on the `409` — **none does**. |
| **BSQ-11** body cap wired end to end | ✅ **PASS** | Both halves present: `WebConfig.java:21` (`addUrlPatterns(… "/backends", "/backends/announce")` — the half the architecture doc missed) **and** `RequestBodySizeLimitFilter.java:51-60, 100-102`, 8 KiB hardcoded, registered **unconditionally** regardless of the flag as required. `announceRejectsAnOversizedBodyThroughTheRunningApplication` asserts `413` **through the real running app**, not through a unit test of the filter class — the specific thing BSQ-11 demanded. (`/backends/*` was registered as the two exact paths instead; `DELETE` carries no body, so this is equivalent-and-tighter.) |
| **BSQ-12** full role matrix incl. trailing-slash / percent-encoded | ✅ **PASS** (sub-clause ⇒ F-BSR-14) | `SecurityMatrixSelfRegistrationEnabledTest` (flag on) + `SecurityMatrixTest` (flag off), both real `@SpringBootTest` + Zonky. Covers WORKER/CI/ADMIN/none × both flag states across all three paths, plus `POST /backends/` and `/backends/%61nnounce`. Confirmed ADMIN cannot reach announce and WORKER cannot reach the admin writes. |
| **BSQ-13** constant messages, collapsed `422`, no rejected value | ✅ **PASS** for this feature's own paths (⚠ **F-BSR-01** breaches the same principle globally) | All six `BackendUnavailableException` messages in `BackendUrlValidator` are compile-time constants with no interpolation (verified line by line). WORKER collapse at `BackendRegistryService.java:110`; ADMIN preserves the specific one at `:267`. `GlobalExceptionHandler.handleValidation:243-246` still emits `field + ": " + defaultMessage` and never `getRejectedValue()`; the new `BackendAnnounceController.handleValidation:56-60` is byte-identical in shape. No new DTO uses `${validatedValue}`. Tests: `announceCollapsesEveryUrlRejectionCauseToTheSameFixedMessage`, `adminPathKeepsTheValidatorsOwnSpecificMessage`. |
| **BSQ-14** `scheme://host[:port]`, no token, no stack trace, sanitised DB values | ⚠ **PASS-WITH-FINDING** (F-BSR-07) | `newOrigin`/`origin` are the normalised value; every field goes through `safe()` → `TextSanitizer.sanitizeSingleLine(v, 200)`; scheme is logged (BSQ-INH-4's requirement). `aLegacyRowsControlCharactersInAnnouncedByProduceASingleSanitizedLogLine` covers the CR/LF case. The `oldOrigin` field is the gap. |
| **BSQ-15** closed counter vocabulary + `backendUrlRepointed` | ✅ **PASS** | `MetricsCounters.java:39-46, 93-101, 132-138`. Keys are only `NAME_TAKEN`/`URL_REJECTED`/`VALIDATION`/`REGISTRY_FULL` — every call site passes a literal; **no key is ever derived from request input** (verified at all five increment sites). Surfaced through `MetricsSnapshot` → `MetricsResponse` → `GET /metrics` (ADMIN). The `VALIDATION` bucket is wired via a controller-local `@ExceptionHandler` that leaves every other endpoint's `400` shape untouched — a neat solution. |
| **BSQ-16** `DEPLOYMENT.md` states log-only audit / log retention = audit retention | ❌ **FAIL** | Not attempted. See **F-BSR-03**. |
| **BSQ-17** url-free `BackendView` built from the persisted entity | ✅ **PASS** | `AdminController.java:52-65` builds via `statisticsService.snapshotOf(outcome.backend())`; `BackendUpsertOutcome` carries the **entity**, never the request DTO, and its javadoc says why. `BackendView`/`BackendSnapshot`/`AnnounceBackendResponse` all carry no `url` (verified field by field). The lifecycle test asserts `doesNotContainKey("url")` on both the announce and admin-upsert responses. The requirement's "grep test that `Backend.getUrl()` has no call site outside `BackendProberImpl`/`BackendRegistryService`" was not written as an automated test; I verified it manually — the only call sites are `BackendProberImpl.java:46` (pre-BSQ-04 form removed) and `BackendRegistryService.java:185`. |
| **BSQ-18** `403`/`404` WARN-and-continue; `409`/`422` terminal | ⚠ **PASS-WITH-FINDINGS** (F-BSR-04, F-BSR-05) | `GatewayClient.java:172-193` + `WorkerRunner.java:117-158`. The mandated split is implemented exactly, with correct cause strings, and `403` correctly does **not** crash-loop the fleet (BST-14a closed). The defects are in codes the requirement did not enumerate. |
| **BSQ-19** interruptible, bounded backoff loop | ✅ **PASS** | `WorkerRunner.java:39, 50, 58-61, 167-183`. `ApplicationListener<ContextClosedEvent>` sets an `AtomicBoolean`; `sleepInSlices` re-checks it every ≤200 ms and propagates `InterruptedException` by restoring the interrupt flag and returning `false`; `run` then returns without starting the loop. The class javadoc correctly explains why a plain `Thread.sleep` here is **not** interruptible by the shutdown hook (it runs on the main thread inside `callRunners`). BST-14b closed. |
| **BSQ-20** all four `backend.url` rules + bare origin | ⚠ **PASS-WITH-FINDING** (F-BSR-10) | `WorkerProperties.java:216-243`: URI parse, scheme, loopback (reusing the existing private `isLoopbackHost`), bare origin, plus the `backend.url == llama.url` WARN. No message echoes the value. The `isLoopbackHost`-is-a-string-check asymmetry is recorded in the javadoc as required. Missing only the null-host guard. |

**Blocking-MUST result: 19 of 20 pass or pass-with-non-blocking-findings; BSQ-16 fails outright.**

---

## Non-regression set (§6) — re-verified

| Item | Verdict |
|---|---|
| **SR-10** SSRF guard, "on write" half implemented + "on every use" half strengthened | ✅ Both halves verified. The write-time call and the probe-time call go through the identical `BackendUrlValidator.validate` and the probe uses its **return value**. |
| **SR-11** edge body cap extended to two new paths | ✅ BSQ-11, wired in `WebConfig` **and** the filter, proven at `413` through the running app. |
| **SR-12 / T-09** no secrets/URLs/tokens in logs | ⚠ One gap — F-BSR-07 (`oldOrigin`). No token, no stack trace, no full URL anywhere else in the new log sites. |
| **SR-15** https for backend urls | ⚠ Knowingly deviated (BSQ-INH-4) — but **not recorded in the operator docs** as the residual requires (F-BSR-03). |
| **SR-16** exactly one role per path | ✅ Three new paths, `POST /backends` and `POST /backends/announce` both **exact** matchers (an exact `/backends` matcher cannot shadow `/backends/announce`, so the ordering is inert-as-designed and correctly commented as defence against a future widening). `anyRequest().denyAll()` fallthrough survives. Proven by the 24-cell matrix. |
| **SR-17** no internal detail / rejected value in error bodies | ❌ **Regressed application-wide** — F-BSR-01. Intact for this feature's own responses. |
| **T-17** `GET /backends` url minimisation survives three new response paths | ✅ BSQ-17. |
| **WOC-14/15/17/21** health-checker phase split, `last_seen` semantics, re-entrancy guard, parked-row bail | ✅ `BackendHealthChecker.java` is **unchanged on this branch** (`git diff master...HEAD` empty for that file), and announce writes none of `status`/`last_seen`/`probe_failed_since` (BSQ-09). |
| **WOR-10 / F-WOC-01** `probe_failed_since` fail-fast; admin `status: ACTIVE` clears it | ✅ Implemented at `BackendRegistryService.java:289-293` on the **ADMIN path only**; unreachable from the WORKER path (no `status` field on `AnnounceBackendRequest`). `settingStatusActiveClearsProbeFailedSince` pins it. |
| **WSR-09 / WSR-12** Worker startup validation and loopback binding | ✅ Unchanged; `validateBackendUrl()` is additive and only runs when `backend.url` is non-blank. |
| **BSQ-24** `String`/`Matcher.matches()` full-match semantics kept | ✅ `BackendUrlValidator.java:105` uses `.matches()`; grepped every `Matcher` use in `com.review.gateway` — **no `.find()` anywhere on an allowlist path**. |

---

## Specific items the task asked me to scrutinise

### The QA-added `ApplicationYamlBootTest` cases — do they prove what they claim?

**They test a real path, not a mock — but the claim is overstated.** The `runner`
(`ApplicationYamlBootTest.java:38-48`) is an `ApplicationContextRunner` with
`ConfigDataApplicationContextInitializer` pointed at the real
`src/main/resources/application.yml`, plus `withUserConfiguration(GatewayPropertiesTestConfig.class)` —
which (`:195-197`) is an `@EnableConfigurationProperties(GatewayProperties.class)` shell. So the context
contains **`GatewayProperties` and nothing else** — no `SecurityConfig`, no controllers, no `DataSource`.

The commit message calls it *"an end-to-end `@SpringBootTest`"* and the javadoc says *"the real Spring
Boot application context"*. Neither is literally true.

**But the chain it exercises is the entire load-bearing chain for BST-03/BSQ-05**, and every link is
real: the shipped `application.yml` (not the test-resources one that shadows it), Spring's own ConfigData
+ relaxed-binding machinery resolving the documented `BACKEND_SELF_REGISTRATION_ENABLED` env-var name
into `gateway.backend.self-registration.enabled`, the real `@PostConstruct validateOnStartup()`, and a
real context-startup failure. `hasRootCauseInstanceOf(IllegalStateException.class)` plus the message
assertion is genuine. There is no stub or mock anywhere in the path.

What it does **not** prove — and what a reader of the commit message would wrongly assume it does — is
that `SecurityConfig`'s matcher and `BackendAnnounceController`'s `@ConditionalOnProperty` read the same
flag. That is proven instead, and more strongly, by `SecurityMatrixSelfRegistrationEnabledTest` and
`SecurityMatrixTest:300`, which are real `@SpringBootTest(RANDOM_PORT)` contexts over Zonky Postgres
driving the actual filter chain. **Verdict: the test is sound and the QA fix it guards
(`5148eed`) is a genuine catch — the env-var placeholder really was missing and the documented
activation mechanism really was a silent no-op. Only the prose overclaims; correct the javadoc/commit
narrative, not the test.**

### The `400`-is-now-fatal fix on the Worker side

**The fix is correct for `400` itself, and incomplete as a rule.** Enumerating everything that can
produce `400` on `POST /backends/announce`:

1. `MethodArgumentNotValidException` (`BackendAnnounceController.java:53-61`) — `backendId`/`workerId`/
   `model` failing `@Pattern`/`@Size`. Non-self-healing Worker misconfiguration. **Fatal is right.**
2. `HttpMessageNotReadableException` → `MALFORMED_REQUEST`. Only reachable on a wire-shape mismatch
   between the two repos. Non-self-healing. **Fatal is right.**
3. **`GlobalExceptionHandler.handleIllegalArgument` (new, F-BSR-01)** — any `IllegalArgumentException`
   anywhere on the announce path now becomes a `400`. This is the one case where "400 ⇒ fatal" is wrong,
   and it exists *because of this branch*. Fixing F-BSR-01 removes it.
4. A reverse proxy's own `400` (malformed request line / header). Not reachable from a well-formed
   `RestClient` POST; ignorable.

So: no *currently reachable* legitimate-transient `400` other than the one F-BSR-01 introduces. **The fix
is sound.** The real defect is the rule it left in place — the fatal/non-fatal classification is a closed
allowlist whose *default* is "transient, retry forever", which strands `401` (F-BSR-05) and mis-buckets
`BACKEND_REGISTRY_FULL` (F-BSR-04). Fixing the default subsumes the `400` fix.

### BSQ-INH-1..4 — still accurately described post-implementation?

- **BSQ-INH-1** (T-03 widened by this feature) — ✅ **Accurate, and its listed bounds are all real.**
  Every one of the six compensating controls it names is implemented and verified above: kill switch
  default-off (`GatewayProperties.java:1170`), startup-enforced allowlist (BSQ-05/06), bare-origin
  normalisation (BSQ-04), registry cap (BSQ-03), first-claim URL immutability (BSQ-01), detection
  counters (BSQ-02/15). No amendment needed.
- **BSQ-INH-2** (allowlist/DNS oracle at 1 bit/request) — ⚠ **Accurate on the HTTP-status bit; too
  pessimistic on the DNS half, and silent on timing.** F-BSR-06 shows the DNS primitive is *not*
  irreducible: it is only unbounded because `isBlockedHost` runs before the allowlist regex. It also
  omits that the collapsed `422` body is separable by wall-clock (measured 0 ms vs 46–57 ms). Amend after
  the reorder: "bounded by the allowlist; only allowlist-matching hosts can provoke a resolver query".
- **BSQ-INH-3** (DNS rebinding TOCTOU) — ✅ **Accurate, and its `SHOULD` half was actually done.** The
  recommended `getAllByName`-and-reject-if-**any**-address-is-blocked upgrade is implemented
  (`BackendUrlValidator.java:121-127`), closing the multi-A-record case. Genuine time-of-use rebinding
  remains open and accepted, as written. The residual should be updated to record that the multi-record
  half is now closed, so a later reader does not re-open it.
- **BSQ-INH-4** (`http://` accepted, `SR-15` deviation) — ⚠ **Accurate as an assessment, but its own
  requirement is unmet.** The residual says the deviation *"must be recorded as an explicit deviation
  from a MUST in `DEPLOYMENT.md` and `README.md` §4.3"*. It is recorded nowhere outside the threat model
  (F-BSR-03). The scheme-logging half **is** done.
- **BSQ-INH-5** (parking does not lock a URL) — ✅ Accurate; implemented with the loud WARN
  (`BackendRegistryService.java:224-229`) and verified benign (nothing reads a non-`ACTIVE` row's url).
  Operator-facing documentation missing (F-BSR-03).

### BSQ-22 — the DB-grant verification note

✅ **PASS, documentation-only as scoped.** `V6__backend_self_registration.sql:15-30` carries an accurate,
complete comment: it states that Flyway does not manage GRANTs in this project, that this feature is the
application role's **first `INSERT`** into `backends`, that `DEPLOYMENT.md`'s grant is table-level so
`announced_by` is covered automatically and no sequence grant is needed (`GENERATED BY DEFAULT AS
IDENTITY`), that it must be **verified against the deployed role** rather than assumed, and — correcting
BSR-26 honestly — that the architecture doc's original "INSERT was never granted" claim was false. It
additionally carries BSQ-21/BST-16's `REVOKE DELETE` recommendation with the correct reasoning. This is
exactly right and could not be verified further without a live database. The matching `DEPLOYMENT.md`
note is missing (F-BSR-03).

---

## Must-fix list for the fix round

**Blocking (merge is gated on these five):**

1. **F-BSR-01** — remove or neuter `GlobalExceptionHandler.handleIllegalArgument`
   (`GlobalExceptionHandler.java:256-260`). Prefer a dedicated exception for the two
   `BackendRegistryService` throws; if the handler stays, emit a fixed body and add `log.warn(…, ex)`.
   Regression test: an arbitrary IAE from a non-`/backends` endpoint still yields `500 INTERNAL_ERROR`.
2. **F-BSR-02** — revert `docker-compose.yml` to its `master` state.
3. **F-BSR-03** — write BSQ-16's three sentences in `DEPLOYMENT.md`, plus BSQ-INH-4's and BSQ-INH-5's
   explicit records and BSQ-05's "not narrow, only not universal" text; delete/replace `README.md:258`
   and `DEPLOYMENT.md:491`/`:1500`; add the new env vars, endpoints, error codes, `announcedBy` field and
   metrics counters to the config/API references (architecture §9's table is the checklist).
4. **F-BSR-04** — map `BackendRegistryFullException` to `503` (no Worker change needed) or to `409` with
   a split `WorkerRunner` cause `switch`. Do not leave it sharing `422` with `BACKEND_URL_REJECTED`.
5. **F-BSR-05** — invert the Worker's `4xx` default: `403`/`404` non-fatal, `429` transient, every other
   `4xx` fatal; add a `401` cause string. Test the `401` cell.

**Strongly recommended in the same round (small, high value):**

6. **F-BSR-06** — swap the allowlist match above `isBlockedHost` in `BackendUrlValidator.validate`
   (2 lines), and amend BSQ-INH-2.
7. **F-BSR-07** — origin-normalise `previousUrl` before logging it.
8. **F-BSR-08** — split "does not resolve" from "blocked range" as two constant messages.
9. **F-BSR-10** — null-host guard in `WorkerProperties.validateBackendUrl`.
10. **F-BSR-15** — `git add -f docs/backend-self-registration-architecture.md`.

**Tracked, not blocking:** F-BSR-09 (upgrade note), F-BSR-11 (`applyLockTimeout` in `decommissionTx`),
F-BSR-12 (post-commit audit logging), F-BSR-13 (`count()` includes `OFFLINE`), F-BSR-14 (BSQ-12
sub-clause), F-BSR-16 (BSQ-23, the two Semgrep rules, BSQ-21's `DEPLOYMENT.md` half). Plus the two
pre-existing `master` gitleaks findings, which belong to a separate cleanup, not to this branch.

---

## Bottom line

The security-critical machinery of this feature — the write path, the SSRF blast-radius reduction, the
startup gate, the concurrency structure, the role matrix, the counters — is **correctly and carefully
built**, and all nine of the threat model's §5 corrections landed for real rather than in name. Every
blocking MUST except BSQ-16 verifies against the code.

What did not get the same care is the *edge* of the feature: an exception handler whose scope was never
assessed, a compose-file change that nobody was supposed to make, an entire documentation obligation that
was skipped, and two status-code decisions that are each locally defensible and jointly wrong. None of
these is exploitable as an SSRF or an authorisation bypass; all four are the kind of defect that only
shows up when you read the two repositories as one system.

**PASS-WITH-FINDINGS — send to `backend-developer` for a fix round; re-verify F-BSR-01..F-BSR-05 before
merging to `master`.**
