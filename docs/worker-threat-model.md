# LLM Worker (Executor) — Threat Model (pre-implementation)

Status: PRE-IMPLEMENTATION. No Worker code exists yet. This model threat-models the approved
`docs/worker-architecture.md` (authoritative; §13 has preliminary security notes), the Worker spec
`# LLM Worker (Executor) — Техническая спецификация.md`, and the Gateway API the Worker consumes
(`README.md` §6/§9). It **extends** the Gateway threat model `docs/threat-model.md` (SR-01..SR-24);
it does not rewrite it. Deviation ledger D1–D9 and abandon-on-failure (D6) are approved.

Methodology: STRIDE per element/flow, kept proportional to a small stateless transport agent.
Risk = qualitative Likelihood × Impact (Critical/High/Medium/Low). Every requirement is tagged
MUST / SHOULD / ACCEPTED-RISK and mapped to a class from `worker-architecture.md`.

Threat-model boundary note: the Worker runs on a **dedicated single-tenant Mac mini** co-located 1:1
with its `llama-server`. "Local processes on the Worker host" is therefore a *real but constrained*
attacker: it means other software/users on that host, not arbitrary internet. Several threats below
are rated on that basis.

---

## 1. Assets

| # | Asset | C | I | A | Where it lives | Notes |
|---|-------|:-:|:-:|:-:|----------------|-------|
| WA1 | `WORKER_TOKEN` (Gateway bearer) | **H** | **H** | — | env var → `gateway.apiKey` → `Authorization: Bearer`; JVM heap | Holder can claim any job and read **every diff** (Gateway A3/T-05). Shared across all Workers. |
| WA2 | Diff content (proprietary source) | **H** | M | — | JVM heap only, inside the claim payload + assembled prompt | Crown-jewel data (Gateway A6). Leaves Worker only toward loopback llama. |
| WA3 | `rawResponse` (model output) | M | M | — | JVM heap only | May quote source lines (Gateway A7). Submitted to Gateway, published to MR. |
| WA4 | Prompt templates | L | **H** | M | `src/main/resources/prompts/<pv>.yml` in the fat JAR | **Integrity is the point.** A tampered template = prompt injection at the source (§0.3). |
| WA5 | llama-server loopback socket | — | **H** | **H** | `127.0.0.1:8000`, **unauthenticated** | Anything on the host can call it; anything that binds the port first can impersonate it. |
| WA6 | Actuator port (`/health`, `/prometheus`) | L | L | M | `management.server.address` (must be `127.0.0.1`) | Leaks topology/uptime/versions if bound off-loopback. |
| WA7 | Gateway TLS trust (truststore) | — | **H** | — | JVM default truststore or a custom CA store | Weakened validation ⇒ MITM of WA1/WA2/WA3. |
| WA8 | Worker host + process identity | M | M | M | launchd service, run-as user | Blast radius if the process runs privileged. |
| WA9 | Worker availability (keep draining the queue) | — | — | M | the single JVM process | Not SPOF (N Workers); a wedged Worker silently stops draining its backend. |

---

## 2. Trust boundaries

| # | Boundary | Direction / channel | Crosses it | Trust posture |
|---|----------|---------------------|-----------|---------------|
| WTB-GW | Worker → Gateway | outbound HTTPS, `Authorization: Bearer WORKER_TOKEN` | WA1 out; WA2 (diff) **in** on claim; WA3 out on result | Gateway is authenticated by TLS cert; **Gateway response data is UNTRUSTED input to the Worker** (diff, promptVersion). |
| WTB-LLAMA | Worker → llama-server | outbound **loopback HTTP, unauthenticated** | WA2/prompt out; WA3 in | No auth, no TLS. Relies entirely on the loopback + single-tenant-host assumption. Response is untrusted. |
| WTB-HOST | Worker ↔ other local processes/users | shared host: env table, `/proc`-equivalent, listening sockets, filesystem | WA1 (env/heap), WA4 (files), WA5 (port), WA6 (actuator) | Only as strong as host OS user separation and file perms. |
| WTB-TMPL | Template artifact ↔ host filesystem | classpath resource lookup keyed by `promptVersion` | WA4 | Classpath-in-JAR is far stronger than an external mutable dir; `promptVersion` is attacker-influenced (comes from Gateway data). |
| WTB-CFG | Deployment config/flags ↔ Worker | env vars / `application.yml` | `worker.allow-insecure-gateway`, `management.server.address`, truststore | A misconfig here silently removes a control (TLS, loopback binding). |

---

## 3. STRIDE threats — WT-01..WT-21

"Inh" = inherited from the Gateway threat model (already covered Gateway-side; listed here for
completeness / Worker-side hygiene). "New" = Worker-specific, must be handled in Worker code.

| ID | STRIDE | CWE / OWASP | Component | Scenario | Sev | New/Inh |
|----|--------|-------------|-----------|----------|:---:|:-------:|
| **WT-01** | Spoofing/Tampering | CWE-306, CWE-300 / A05 | `LlamaClient` ↔ WA5 | A local process binds `127.0.0.1:8000` (while llama is down/at boot) and **impersonates llama-server**, feeding the Worker attacker-chosen `rawResponse` → published verbatim to a real MR. llama is unauthenticated loopback. | **High** | New |
| **WT-02** | Spoofing | CWE-522 / A07 | `GatewayClient` (WA1) | Stolen/shared `WORKER_TOKEN` lets any holder claim jobs and read every diff. | High | Inh (Gateway T-05/SR-05, README §11) |
| **WT-03** | Tampering | CWE-345, CWE-829 / A08 | `PromptTemplateService`, WA4 | An attacker with write access to the install dir replaces `prompts/<pv>.yml` → the model is instructed to emit malicious/misleading comments or to exfiltrate the diff into its output. **Prompt injection at the source.** Worst if templates live in an external mutable dir instead of the fat JAR. | **High** | New |
| **WT-04** | Tampering/Info disc. | **CWE-22 / A01** | `PromptTemplateService` resource lookup | Hostile `promptVersion` from the Gateway payload (`"../../etc/passwd"`, `"../application"`, `"v1/../../secrets"`) is concatenated into the template path → path traversal reads arbitrary classpath/filesystem resources into the prompt (info disclosure) or crashes the lookup. `promptVersion` is **untrusted Gateway data**. | **High** | New |
| **WT-05** | Tampering/RCE | CWE-1336, CWE-94 / A03 | `PromptTemplateService` render | If template rendering uses an **expression-evaluating engine** (SpEL / Freemarker / Velocity / `${}`), a diff containing template directives is evaluated → SSTI, info disclosure, or RCE. The diff is attacker-controllable end-to-end (any MR author). | **High** | New |
| **WT-06** | Info disc./Spoofing | CWE-295, CWE-319 / A02 | `HttpClientConfig` TLS to Gateway | Disabled/loose cert validation, a trust-all custom truststore, or TLS downgrade lets a network MITM steal `WORKER_TOKEN` and inject job payloads/forged results. Worker↔Gateway may traverse WAN/VPN. | **High** | Inh-extended (Gateway T-12/SR-15; Worker owns the client side) |
| **WT-07** | Spoofing/Config | CWE-16, CWE-319 / A05 | `worker.allow-insecure-gateway` flag | The dev-only insecure flag (allows `http://localhost`) is left enabled / pointed at a non-local host in prod → `WORKER_TOKEN` + diffs travel in cleartext. | **High** | New |
| **WT-08** | Info disclosure | CWE-522, CWE-215, CWE-532 / A09 | WA1 lifecycle | Token recovered from `ps e`/`/proc/<pid>/environ` (same-uid or root), a heap dump (String on heap), a crash dump, or an accidental log line. | **High** | New (Gateway-analogous T-09) |
| **WT-09** | Info disclosure | CWE-532 / A09 | Logging (`logback-spring.xml`) | Diff (WA2) or `rawResponse` (WA3) content written to logs instead of only ids/sizes → proprietary source in log files/aggregation. | Medium | Inh (Gateway T-09/SR-12) — must re-enforce Worker-side |
| **WT-10** | Info disclosure | CWE-312, CWE-459 / A04 | JVM / host | Diff/response persisted to disk against the "in-memory only" NFR: `-XX:+HeapDumpOnOutOfMemoryError`, core dumps, or Spring writing them anywhere. | Medium | New |
| **WT-11** | Info disclosure | CWE-16, CWE-497 / A05 | Actuator (WA6) | `management.server.address` mis-set (blank/`0.0.0.0`) or `exposure.include` widened to `env`/`configprops`/`heapdump`/`threaddump` → metrics/config/heap reachable off-host. `heapdump` off-loopback would leak WA1/WA2. | **High** (if widened) / Med (metrics only) | New |
| **WT-12** | Denial of Service | CWE-400, CWE-789 / A04 | `GatewayClient` claim → prompt build | A malicious/buggy Gateway (or MITM) returns an **oversized diff**; the Worker buffers it + builds the prompt in memory → OOM crash. Worker must treat Gateway data as untrusted and cap what it will process. | **High** | New |
| **WT-13** | Denial of Service | CWE-400, CWE-770 / A04 | `LlamaClient` response read | A runaway/hostile llama (see WT-01) streams an **unbounded completion** → OOM. Related: if the response is not bounded below the Gateway's **500 KB `/jobs/{id}/result` cap**, every result gets `413` and the job never completes. | **High** | New |
| **WT-14** | Denial of Service | CWE-835, CWE-400 / A04 | `WorkerLoop` poll cadence | On persistent Gateway errors the poll loop **tight-spins** (no/broken backoff) → CPU burn, Gateway hammering, log flood. | Medium | New |
| **WT-15** | DoS / Repudiation | CWE-703, CWE-459 / A04 | `HeartbeatScheduler` | An uncaught exception in the scheduled heartbeat task silently kills the heartbeat thread while the job runs → Gateway sees no heartbeat, reclaims after 180 s and may re-assign; **`shouldContinue:false` abort never arrives**, so the job can't be cancelled. Silent zombie. | **High** | New |
| **WT-16** | Repudiation | CWE-778, CWE-345 / A09 | self-declared `workerId` | `workerId`/`backendId` are self-asserted under the shared token; audit attribution is only as good as honesty. | Low | Inh (Gateway T-15; README §11 accepted residual) |
| **WT-17** | Elevation of privilege | CWE-250, CWE-269 / A01 | launchd service (WA8) | Worker runs as root / a privileged user → token theft, template tamper, and host compromise all get easier and blast wider. | Medium | New |
| **WT-18** | Tampering (supply chain) | CWE-1104, CWE-937 / A06 | new `worker/pom.xml` | A vulnerable/compromised dependency (Spring Boot, Jackson, micrometer) introduces RCE/deserialization. The Worker is a **new, separate Maven project** — it needs its own SCA/secret gate, not just the Gateway's. | Medium | New (Gateway T-25/SR-23 analog) |
| **WT-19** | Info disclosure | CWE-93, CWE-117 / A09 | log lines with `promptVersion` | Unsanitized `promptVersion`/ids in log lines allow CRLF log injection / forged log entries. | Low | New |
| **WT-20** | Tampering | CWE-807 / A08 | `GatewayClient` result path | The Worker submits a `rawResponse` that is **silently truncated** to fit the 500 KB cap → truncated JSON array → the Gateway's parser falls back to "whole response as one comment" and posts partial/garbled source to the MR. Correctness+integrity, not just DoS. | Medium | New |
| **WT-21** | DoS (local) | CWE-406 / A04 | WA5 loopback | A rogue local process floods the unauthenticated llama socket, starving the Worker's inference capacity. | Low | New |

Risk tally: **High = 8** (WT-01, WT-03, WT-04, WT-05, WT-06, WT-07, WT-08, WT-11 conditional, WT-12,
WT-13, WT-15 — count the confirmed-High set), Medium = 6, Low = 4. (WT-02 High but Gateway-owned.)

---

## 4. Security requirements — WSR-01..WSR-18 (dev implements, SAST audits)

Each is a testable assertion mapped to WT-ids and to a class from `worker-architecture.md`.
Tag = MUST / SHOULD / ACCEPTED-RISK. **New** = Worker-specific; **Inherited** = satisfied Gateway-side,
Worker only must not regress it.

### Input validation — Gateway data is untrusted (the biggest new surface)

- **WSR-01 (MUST, WT-04) — New.** `promptVersion` is validated against a strict allowlist regex
  (`^[A-Za-z0-9._-]{1,64}$`, no `/`, no `.` sequences) **before** any resource resolution, and the
  template is loaded **only** as a fixed classpath resource `classpath:prompts/<pv>.yml` (never a
  filesystem path built by concatenation). A `promptVersion` not matching a known bundled template ⇒
  **abandon the job** (D6). *Test:* `promptVersion="../../etc/passwd"`, `"v1/../secret"`, `"../application"`
  are all rejected/abandoned and never touch the filesystem.

- **WSR-02 (MUST, WT-05) — New.** Prompt assembly uses **literal string substitution** of a single
  `{{DIFF}}` placeholder — **no** expression-evaluating template engine (no SpEL, Freemarker, Velocity,
  `StringSubstitutor` with `${}`), and the diff is **never** re-processed as template text after insertion.
  *Test:* a diff containing `${T(java.lang.Runtime)...}`, `#{...}`, `{{7*7}}` is passed through verbatim,
  never evaluated.

- **WSR-03 (MUST, WT-12) — New.** The Worker enforces a hard upper bound on the accepted diff size from
  the claim payload (config `worker.limits.max-diff-bytes`, defaulted to the Gateway's diff budget +
  margin); an oversized diff ⇒ **abandon**, not OOM. *Test:* a claim payload with a diff over the cap is
  abandoned without buffering it into a prompt.

### llama boundary (WTB-LLAMA / WA5)

- **WSR-04 (MUST, WT-13/WT-21) — New.** `LlamaClient` bounds the response it will read
  (`worker.limits.max-response-bytes`, set **below** the Gateway's 500 KB `/jobs/{id}/result` cap) and
  caps generation via `max_tokens`. Exceeding the read bound ⇒ **abandon** (not truncate — see WSR-05).
  *Test:* an oversized llama body is abandoned; the Worker never OOMs and never posts a `>500 KB` result.

- **WSR-05 (MUST, WT-20) — New.** The Worker **must not silently truncate** `rawResponse` to fit the
  Gateway cap. If the model output exceeds the safe size, the job is **abandoned** (D6), never submitted
  as a truncated/garbled JSON array. *Test:* an oversize response yields no `/jobs/{id}/result` call, only
  `jobs_failed_total++`.

- **WSR-06 (SHOULD, WT-01) — New.** `LLAMA_URL` must be a loopback address (`127.0.0.1`/`::1`); a
  non-loopback llama URL requires an explicit opt-in flag and is warned at startup. Document that the
  llama socket is unauthenticated and that host hardening (see WSR-15) is the control. *Test:* a
  non-loopback `LLAMA_URL` without the opt-in flag is rejected/warned at startup.

### Templates (WA4 / WTB-TMPL)

- **WSR-07 (MUST, WT-03) — New.** Prompt templates ship **only on the classpath inside the fat JAR**
  (`src/main/resources/prompts/`). The Worker **must not** load templates from an external, writable
  filesystem directory in production (`prompt.location` stays `classpath:`). *Test:* the resolver reads
  from the classpath; there is no code path that reads a template from an operator-writable dir.

- **WSR-08 (SHOULD, WT-03) — New.** Templates are governed as versioned platform artifacts co-reviewed
  with the Gateway team (§0.3); the JAR is deployed to a **read-only install location** owned by a
  different user than the runtime user. *Verify:* deployment/runbook checklist item.

### Token & transport (WA1 / WA7 / WTB-GW)

- **WSR-09 (MUST, WT-06/WT-07) — New (extends Gateway SR-15).** `HttpClientConfig` uses standard TLS
  cert + hostname validation to the Gateway; there is **no** trust-all `SSLContext` / `NoopHostnameVerifier`.
  A custom truststore may add an internal CA but must never disable validation. `worker.allow-insecure-gateway`
  (a) defaults false, (b) is honored **only** when the Gateway host is loopback/`localhost`, (c) is refused
  (fail-fast) for any non-local host. *Test:* insecure flag + a non-local `GATEWAY_URL` ⇒ app refuses to start;
  no trust-all context exists.

- **WSR-10 (MUST, WT-08/WT-09) — New (mirrors Gateway SR-12).** `WORKER_TOKEN` is read **only** from env
  (`gateway.apiKey`←`WORKER_TOKEN`), `≥32` chars enforced at startup with a **secret-safe** message
  (property name only, never the value). It is **never** logged, never a metric label, never in an
  exception message or `toString()`. Diff/`rawResponse` **content** is never logged — only `jobId`/`reviewId`
  and byte sizes. *Test:* boot with a short token fails; a log/exception scan shows no token/diff/response body.

- **WSR-11 (MUST, WT-06) — New.** `gateway.url` must start with `https://` (except under the guarded
  WSR-09 loopback exception); startup fails otherwise. *Test:* `http://` non-local Gateway URL refused.

### Actuator (WA6)

- **WSR-12 (MUST, WT-11) — New.** `management.server.address=127.0.0.1` and
  `management.endpoints.web.exposure.include` is **exactly** `health,prometheus` — never `env`,
  `configprops`, `heapdump`, `threaddump`, `beans`. Add a `@PostConstruct` assertion that the management
  address is loopback and fail fast if not. *Test:* off-loopback management address ⇒ startup fails;
  `/actuator/heapdump` and `/actuator/env` return 404.

- **WSR-13 (SHOULD, WT-11) — New.** `management.endpoint.health.show-details=never` (or `when-authorized`),
  so `/health` does not leak component/config detail to a co-located scraper. *Test:* `/actuator/health`
  returns status only.

### Availability & lifecycle

- **WSR-14 (MUST, WT-14) — New.** The poll loop uses capped exponential backoff on Gateway errors
  (cap ~30–60 s) and never busy-spins; the process never exits on Gateway/llama failure. *Test:* Gateway
  down during claim ⇒ backoff observed, `gateway_errors_total++`, no tight loop, process stays up.

- **WSR-15 (MUST, WT-15) — New.** The heartbeat scheduled task wraps its body so an exception **cannot**
  kill the scheduler thread; repeated heartbeat failure sets the abort signal (fail-safe: stop the job
  rather than run blind). The abort path from `shouldContinue:false`/`403`/`404` is exercised even if a
  heartbeat tick throws. *Test:* an injected exception in one heartbeat tick does not stop subsequent
  ticks nor the abort delivery; the job is not left as a silent zombie.

### Host & supply chain

- **WSR-16 (SHOULD, WT-08/WT-10/WT-17) — New.** The launchd service runs as a **dedicated non-root user**;
  the fat JAR + `application.yml` are `0640`/owned by a separate admin user; JVM flags **disable**
  `HeapDumpOnOutOfMemoryError` (or point it at a `0700` dir on an encrypted volume) so a crash cannot spill
  the diff/token to disk. *Verify:* plist runs non-root; `-XX:-HeapDumpOnOutOfMemoryError` (or restricted
  path) in launch flags; config file perms.

- **WSR-17 (MUST, WT-18) — New.** The `worker/` project has its **own** CI security gate mirroring the
  Gateway's (`gitleaks` full-history, `osv-scanner`/SCA on `worker/pom.xml` blocking High/Critical,
  `semgrep` `p/java`+`p/spring`+`p/secrets`). Spring Boot pinned to **3.5.16** to match the Gateway's CVE
  posture. *Verify:* pipeline present and gating on the Worker module.

- **WSR-18 (SHOULD, WT-19) — New.** Log lines that include `promptVersion` (or any Gateway-supplied
  string) strip CR/LF before logging. *Test:* a `promptVersion` containing `\r\n` cannot forge a second
  log line.

### Inherited / accepted residuals (no new Worker code)

- **WSR-INH-1 (ACCEPTED-RISK, WT-02/WT-16).** Self-declared `workerId` under a shared `WORKER_TOKEN`
  (README §11): the Worker keeps `workerId` config-driven and stable, reused across a job's
  heartbeat/result. Per-worker lease/per-backend token binding is a **Gateway-side** enhancement
  (Gateway SR-06/SR-07) — nothing for the Worker to implement, but do not paper over it. A leaked token =
  full diff exfiltration remains a Gateway-owned residual.
- **WSR-INH-2 (ACCEPTED-RISK).** The Worker must receive the diff to function; a fully-compromised Worker
  host reading its own claimed diffs is inherently unavoidable (Gateway T-05 accepted-risk). Scope-limited
  by host hardening (WSR-16) and network segmentation of the Worker fleet.

---

## 5. Release gate

Blocking MUSTs for the Worker: **WSR-01, WSR-02, WSR-03, WSR-04, WSR-05, WSR-07, WSR-09, WSR-10,
WSR-11, WSR-12, WSR-14, WSR-15, WSR-17.** Strongly recommended SHOULDs (tracked, non-blocking):
WSR-06, WSR-08, WSR-13, WSR-16, WSR-18. Accepted residuals: WSR-INH-1, WSR-INH-2.

---

## 6. Architecture-level corrections recommended BEFORE dev starts

These are places in the approved `worker-architecture.md` where the security posture should be made
explicit/tightened before coding (none reopen the frozen Gateway):

1. **§0.3 / §3.2 `PromptTemplateService`: add an explicit `promptVersion` allowlist step.** The doc says
   "loads classpath template by `promptVersion`, unknown ⇒ abandon" but does not mandate *validating the
   string before resolution*. Without WSR-01 this is a path-traversal sink. Make input validation a named
   precondition, not an implicit consequence of "unknown version".

2. **§0.3: mandate literal placeholder substitution, forbid expression engines.** "Single `{{DIFF}}`
   placeholder" reads as intent, not a control. State that no SpEL/Freemarker/`${}` engine may touch the
   diff (WSR-02) — otherwise the whole diff, which is fully attacker-controlled, becomes an SSTI vector.

3. **§13 "truncate to stay under 500 KB" is the wrong control — change to abandon-on-oversize.** Silently
   truncating `rawResponse` produces a broken JSON array that the Gateway's parser degrades into a single
   garbled comment posted to the MR (WT-20). Align with D6: cap generation via `max_tokens`, and if the
   response still exceeds the safe bound, **abandon** rather than submit a truncated result.

4. **§4.2 / §9: harden the `allow-insecure-gateway` flag and the actuator binding as fail-fast rules, not
   just config.** The insecure flag must be structurally impossible to use against a non-local Gateway
   (WSR-09), and `management.server.address` being loopback should be *asserted at startup* (WSR-12), not
   left to whoever edits `application.yml`. A blank/overridden management address silently exposes actuator.

5. **§10 NFR / §13: disable heap dumps (or confine them).** The whole confidentiality story rests on
   "diff + rawResponse in memory only, never on disk". A default `-XX:+HeapDumpOnOutOfMemoryError` (or an OS
   core dump) violates that on the first OOM (WT-10). Call out the JVM flag and dump-path hardening in the
   packaging branch (WSR-16).

6. **§11 / §14: the new `worker/pom.xml` needs its own security gate.** The Gateway's
   `.github/workflows/security-gate.yml` covers the Gateway module; the standalone Worker project must be
   wired into SCA/secret/SAST scanning too (WSR-17), or its dependency tree ships unaudited.

7. **§3 (minor): document the unauthenticated loopback llama socket as an accepted, host-scoped risk and
   pin `LLAMA_URL` to loopback (WSR-06).** The architecture treats "loopback HTTP is fine, no secret" as
   settled; make explicit that the *only* thing standing between a local process and both impersonating
   llama (WT-01) and consuming it (WT-21) is single-tenant host hardening — so WSR-16 is load-bearing, not
   optional polish.
