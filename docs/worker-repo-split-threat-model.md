# Worker/Gateway Repository & Deployment-Topology Split — Threat Model (pre-implementation)

Status: **PRE-IMPLEMENTATION**. Nothing implemented; no branch yet (suggested `chore/worker-repo-split`).
This model threat-models the approved design in `docs/worker-gateway-split-architecture.md` (authoritative
— not re-derived here).

It **extends** `docs/threat-model.md` (SR-01..SR-24) and `docs/worker-threat-model.md` (WT-01..WT-21 /
WSR-01..WSR-18) and rewrites neither. New IDs use the previously-unused prefixes **WRT-xx** (threats) and
**WRR-xx** (requirements); the SAST round on this branch uses **`F-WRS-xx`** in
`docs/security/feature-worker-repo-split-sast-report.md`.

Methodology: STRIDE per element/flow + OWASP Top 10 (2021) + CWE. Risk = qualitative Likelihood × Impact.
Every requirement is MUST / SHOULD / ACCEPTED-RISK and is written as something the `backend-developer`
can implement and the SAST round can check.

---

## 0. Framing — what actually changes, and what does not

**No Java code changes.** `WorkerProperties`, the bearer-token model, the Worker protocol, the PostgreSQL
queue, and every WSR-01..WSR-18 control are untouched by design. This is a **deployment-topology and
supply-chain** change, and that is where every finding below lives.

Three structural deltas:

1. **The Worker→Gateway hop leaves the kernel.** Today the shipped compose runs both containers in one
   network namespace (`network_mode: "service:gateway"`), so `WORKER_TOKEN` and the full diff cross a
   loopback socket that never touches a wire. After the split that hop is a real LAN/WAN path and **TLS
   is the only thing protecting it** — load-bearing where it was previously incidental.
2. **A secret set that was one file becomes N+1 files across two repos.** The Worker-side set gets
   strictly smaller (no `DB_*`/`GITLAB_*`/`CI_TOKEN`/`ADMIN_TOKEN`) — a real posture improvement, but only
   if the old files are actually removed (WRT-10).
3. **A second git repository and a second CI security gate come into existence.** A new repo starts with
   *no* history, *no* branch protection, *no* required checks and a fresh `GITHUB_TOKEN` default — and it
   ships code that runs on every Worker host holding `WORKER_TOKEN` and every diff. The controls that
   exist today in this repo exist there only if someone writes them in the same change.

**Verified against the current tree while writing this model** (so the developer does not have to
re-check):

- `WorkerProperties.validateGatewayUrl()` (lines 151–182) genuinely fails closed: plain `http://` +
  non-loopback host is `IllegalStateException` **regardless** of `worker.allow-insecure-gateway`. The
  loopback test is a literal string comparison against `localhost/127.0.0.1/::1/[::1]` plus a `127.`
  prefix — **no DNS resolution**, so there is no DNS-rebinding or `localhost.evil.com` bypass, and
  `http://127.0.0.1@evil.com/` resolves via `URI.getHost()` to `evil.com` and is correctly refused. The
  architecture's promise "the loopback rule is not relaxed" is a promise about a control that already
  holds. **The residual is not the code — it is what an operator can put in front of it (WRT-01).**
- `worker/` today contains exactly `.dockerignore Dockerfile README.md pom.xml src target`. No `.env`,
  no `.idea/`, no `certs/`. `git status --ignored` reports one ignored path: `worker/target/`.
- `worker/pom.xml` has **no** JDBC driver, no `spring-data`, no GitLab client — only web, actuator,
  micrometer, validation, snakeyaml, test. The three `grep`-hits for "gitlab" in `worker/src` are single
  comment lines. The CLAUDE.md non-negotiable ("Worker never talks to GitLab or PostgreSQL") holds today
  by construction and the split does not change what the Worker can reach — see WRR-13 for keeping it that
  way now that nobody reviews the two trees side by side.
- The Gateway's `.gitleaks.toml` value-scoped exemptions (`test-*-token-\d+`, `AKIA1234567890`,
  `hunter2`, …) match **nothing** under `worker/src`. Copying them into the Worker repo would pre-exempt
  regexes for secrets that do not exist there (WRR-07c).
- `worker/.dockerignore` is `target/ .git/ *.md Dockerfile .dockerignore` — it does **not** exclude
  `.env`. Harmless today (the Dockerfile only does `COPY pom.xml` / `COPY src`), but after the split the
  build context *is* a repo root that will contain a real `.env` (WRR-09b).

---

## 1. Assets — new or re-scoped

| # | Asset | C | I | A | Where it lives after the split | Delta |
|---|-------|:-:|:-:|:-:|---|---|
| WA1′ | `WORKER_TOKEN` / `GATEWAY_API_KEY` (same value) | **H** | **H** | — | Gateway `.env` **and** one `.env` per Worker host | Same secret, more copies, now crossing a real network on every request |
| WA2′ | Diff content (crown jewels) | **H** | M | — | claim response body, **on the wire** | Was kernel loopback, now network |
| WRA1 | **The Worker git repository** | M | **H** | M | `github.com/pirum1ch/AIReviewWorker` (private) | **New.** Push access ⇒ code execution on every Worker host, i.e. WA1′+WA2′ exfiltration |
| WRA2 | **The Worker CI security gate** | — | **H** | M | `AIReviewWorker/.github/workflows/security-gate.yml` | **New.** Absent or non-blocking ⇒ WSR-17 silently unenforced |
| WRA3 | Gateway TLS endpoint / reverse proxy | — | **H** | **H** | not in this repo; a deployment prerequisite | Was optional, becomes **mandatory** |
| WRA4 | Gateway `8080` listener | **H** | **H** | **H** | `ports:` in the Gateway compose | Was reachable only from the same host; must now be reachable from the Worker network |
| WRA5 | Worker-host truststore (`cacerts`) | — | **H** | — | optional bind-mount on each Worker host | **New** on the Worker side (WSR-09's "custom truststore may add a CA" becomes an operational reality) |
| WRA6 | The new repo's initial commit | **H** | M | — | one commit, created by copying a working tree | **New.** The only commit this repo's full-history gitleaks scan will never cover |

---

## 2. Trust boundaries — changed and new

| # | Boundary | Before | After |
|---|---|---|---|
| **WTB-GW** (from `worker-threat-model.md` §2) | In the shipped compose: shared network namespace, plain HTTP over kernel loopback | **Real network hop.** Confidentiality/integrity of WA1′+WA2′ rests entirely on TLS terminated by WRA3 and on the Worker JVM's cert validation (WSR-09, unchanged) |
| **WRTB-REPO** *(new)* | Worker source ↔ Worker hosts, via a repo only this project's maintainers can push to | Same, but the repo is **new**: default settings, no protections, no history of review |
| **WRTB-CI** *(new)* | One security gate over both trees | Two gates; a change to either repo is gated only by *that* repo's gate |
| **WRTB-INGRESS** *(new emphasis)* | The Gateway port was a same-host concern | The Gateway port is now an **ingress from the Worker network**, shared by the CI, Admin and Worker APIs |

Unchanged: WTB-LLAMA, WTB-HOST, WTB-TMPL, WTB-CFG. No authN/authZ decision, no validation rule, and no
Gateway trust boundary is modified by this change.

---

## 3. Threats — WRT-01..WRT-15

| ID | STRIDE | CWE / OWASP | Element | Scenario | Sev |
|----|--------|-------------|---------|----------|:---:|
| **WRT-01** | Info disc. / Spoofing | CWE-319, CWE-300 / A02 | WTB-GW | An operator reintroduces a plaintext shortcut *around* the (correct) `WorkerProperties` check: `GATEWAY_URL=http://127.0.0.1:8080` + `WORKER_ALLOW_INSECURE_GATEWAY=true` pointed at a **non-encrypting local forwarder** (`socat TCP-LISTEN:8080,bind=127.0.0.1 TCP:gateway.internal:8080`, a loopback-bound nginx `proxy_pass http://…`, a `docker run -p 127.0.0.1:8080:` shim). The Worker starts happily — the check sees loopback — while `WORKER_TOKEN` and every diff cross the network in cleartext. This is the *only* surviving way to ship this without TLS, and it is an ops action, not a code path. | **High** |
| **WRT-02** | Info disc. (indirect) | CWE-1188, CWE-319 / A05 | WRA3 | The reverse proxy does not exist yet (`DEPLOYMENT.md` §2 "this runbook **assumes** `https://gateway.internal`"; nothing in either repo sets `server.ssl.*`). The first off-host Worker therefore **refuses to start** — fail-closed, correct — which creates schedule pressure to "just relax `WorkerProperties`" / "just set the flag" / "just terminate plain HTTP for now". The threat is the pressure, not the code. | **High** |
| **WRT-03** | Spoofing / MITM | CWE-295, CWE-1104 / A02 | WRA5 | The Gateway proxy presents a private cert and each Worker mounts a `cacerts` trusting its CA — mirroring the Gateway's existing `certs/cacerts` pattern, which trusts an **mkcert** CA. An mkcert CA's private key lives on a developer workstation and can mint a valid cert for **any** hostname. Trusting it fleet-wide turns one workstation compromise into MITM of every Worker→Gateway hop (WA1′ + every diff). Secondary: a hand-built truststore that *replaces* rather than *extends* the JDK defaults, or a "just disable verification" fix when the handshake fails. | **High** |
| **WRT-04** | Elevation / Info disc. | CWE-668, CWE-1327 / A01, A05 | WRA4 / WRTB-INGRESS | `ports: "8080:8080"` (all interfaces) is now the only path a Worker has to the Gateway. That one listener serves **`POST /reviews`, `DELETE /reviews/{id}`, `/backends`, `/metrics`, `/health`, `/actuator/health`** alongside `/jobs/*`. Anything that can reach the Worker network can reach the Gateway's origin port **directly**, bypassing the proxy entirely: no TLS, and any proxy-level control (IP allowlist, path restriction, rate limit, request-size cap) bypassed with it. Gateway-side token authz still holds — this is a defence-in-depth and TLS-bypass finding, not an authz break. | **High** |
| **WRT-05** | Tampering (supply chain) | CWE-1104, CWE-937 / A06, A08 | WRTB-CI | The Gateway workflow's `sca-worker` + `build-test-worker` are deleted before the Worker repo's own gate exists / is green ⇒ Worker dependencies and its 98-test suite ship ungated (exactly the FW-02 regression WSR-17 was written for). Worse variant: `actions/checkout@v4` does not fetch submodules, so the surviving `gitleaks`/`semgrep` jobs scan an **empty `worker/`** and report **green** — a control that silently degrades to no control while reporting success. | **High** |
| **WRT-06** | Elevation / Info disc. | CWE-1188, CWE-732 / A05 | WRA1, WRA2 | The new repo ships with defaults: created public by accident (`gh repo create` without `--private`), `GITHUB_TOKEN` default write permissions, no required status checks, no branch protection, Actions enabled with whatever the account default is. Any of these makes WRA2 advisory rather than gating, or makes WRA1 (which contains the Worker source and its README describing the token model) world-readable. | **High** |
| **WRT-07** | Info disclosure | CWE-540, CWE-312 / A05 | WRA6 | The initial commit is produced by `cp -r worker/. .` from a live working tree and `git add .`. Anything sitting in `worker/` at that moment is swept in permanently: `target/` (build output incl. compiled test resources), an ad-hoc `.env`, `.idea/`, a `*.hprof` **heap dump** (which by WT-10's own analysis contains `WORKER_TOKEN` and a full diff), a stray `certs/`. The Gateway repo's full-history gitleaks scan never covers this commit, and rewriting it is only cheap **before** the first push. | **High** |
| **WRT-08** | Info disclosure | CWE-798, CWE-1188 / A05 | `.env.example` ×2, `docker-compose.yml` ×2 | Two new committed files that describe secrets. Failure modes: a real value pasted into `.env.example` "to show the format"; a real value inlined in a committed compose file instead of `${VAR:?}`; `.env` not gitignored in the **new** repo before the first commit; `WORKER_ALLOW_INSECURE_GATEWAY=true` shipped as an *active* key in `.env.example`, making WRT-01 a copy-paste away. | **Medium** |
| **WRT-09** | Info disclosure | CWE-538, CWE-1104 / A05 | Worker image | A secret ends up in an image layer: a `COPY . .`-style Dockerfile change picking up the repo-root `.env` (today's `worker/.dockerignore` does **not** exclude `.env`), an `ARG`/`ENV`-baked token, or a truststore/`certs/` copied into the image instead of bind-mounted. Layers survive `docker history` and any registry push. | **Medium** |
| **WRT-10** | Info disclosure | CWE-522 / A07 | WA1′ distribution | The claimed posture improvement ("Worker hosts no longer sit next to `GITLAB_TOKEN`/`DB_PASSWORD`/`ADMIN_TOKEN`") is only real if the **pre-split `.env` copies are actually deleted from Worker hosts**. A leftover full `.env` gives the improvement on paper and none in fact. Related: `WORKER_TOKEN` rotation is now an N-host, two-repo operation with no single source of truth, and world-readable `.env` perms on a Worker host expose WA1′ to any local user (WTB-HOST). | **Medium** |
| **WRT-11** | Tampering (supply chain) | CWE-829, CWE-1357 / A08 | WRA1, `.gitmodules` | Push access to the new repo ⇒ arbitrary code on every Worker host, i.e. `WORKER_TOKEN` + all diffs. Deployment pulls `master` directly (the gitlink pin is documentation only, §3.2) so nothing verifies what is deployed. Future variant: someone "fixes" the empty-`worker/`-in-CI annoyance by adding `submodules: recursive` plus a **classic PAT** secret in the Gateway repo — a token with `repo` scope over *all* private repos, exposed to every workflow run in the Gateway repo. | **Medium** |
| **WRT-12** | Tampering (detection gap) | CWE-1059 / A06 | WRA2 ruleset | Ruleset drift on copy: WSR-17 mandates semgrep `p/java` + **`p/spring`** + `p/secrets`; the shipped Gateway workflow actually runs `p/java` + `p/sql-injection` + `p/secrets` (no `p/spring`). Copying it forward silently perpetuates the deviation. Dropping `p/sql-injection` and `.semgrep/rules.yml` for the Worker is **verified correct** (no JDBC, no GitLab client in `worker/`). Also: unpinned scanner versions or a missing `fetch-depth: 0` turn the Worker gitleaks job into a tip-only scan. | **Low** |
| **WRT-13** | Elevation (principle erosion) | CWE-1008 / — | CLAUDE.md non-negotiable | Post-split, nobody reviews both trees together. A future Worker-side change adds a JDBC driver, a GitLab client, or a DB env var and no reviewer notices that "Worker is a fully stateless HTTP client… never talks to GitLab or PostgreSQL" just became false. Today the co-location made this self-evident; the split removes that. | **Low** |
| **WRT-14** | DoS / integrity | CWE-400, CWE-770 / A04 | WRA3 | The proxy is now in the data path for a `POST /jobs/{id}/result` body up to the Gateway's 500,000-byte cap. A proxy body limit below that (nginx `client_max_body_size` default is 1m — currently adequate, but a hardening pass that lowers it is not), an aggressive read timeout, or a proxy that rewrites/strips `Authorization`, turns every result into a `413`/`401` and every job into a heartbeat-timeout retry loop. Fails as a fleet-wide stall, not as an obvious error. | **Medium** |
| **WRT-15** | Availability | CWE-1188 / — | submodule | A plain `git clone` leaves `worker/` empty; a force-push in the Worker repo can orphan a recorded gitlink. Build/dev friction only — the Gateway never reads the submodule at runtime and a stale pin cannot affect production (§3.2). | **Low** |

Tally: **High = 7** (WRT-01..WRT-07), **Medium = 5** (WRT-08, 09, 10, 11, 14), **Low = 3** (WRT-12, 13, 15).

---

## 4. Security requirements — WRR-01..WRR-17

Tag = MUST / SHOULD / ACCEPTED-RISK. Each is testable/inspectable by the `F-WRS-xx` SAST round.

### Transport — the hop that left the kernel

- **WRR-01 (MUST, WRT-01).** `WorkerProperties` is **not** modified by this change — no new flag, no
  widened loopback list, no config-driven relaxation, no `worker.allow-insecure-gateway` semantics change.
  *Check:* `git diff master -- worker/src` is empty for the whole branch (and, post-split, the Worker
  repo's initial commit is byte-identical to today's `worker/src` tree).

- **WRR-02 (MUST, WRT-01).** The Worker repo's `docker-compose.yml` ships the dev-loopback affordance
  **commented out** (as designed in §5) with an explicit warning naming the prohibited pattern: plain
  `http://127.0.0.1` is acceptable **only** when the Gateway process itself listens on that loopback
  address on the same host. Forwarding loopback to a remote Gateway through a **non-encrypting** relay
  (`socat`, a plain `proxy_pass`, a published-port shim) is **forbidden**; an encrypting tunnel
  (`ssh -L`, WireGuard, a mesh sidecar with mTLS) is acceptable and must be named as the supported
  alternative. Same paragraph in the Worker README and in `DEPLOYMENT.md` §11.
  *Check:* the shipped compose has no active `network_mode: host` / `WORKER_ALLOW_INSECURE_GATEWAY`
  lines; the warning text exists in all three places.

- **WRR-03 (MUST, WRT-08/WRT-01).** `WORKER_ALLOW_INSECURE_GATEWAY` **must not appear as an active key**
  in the Worker `.env.example`. If mentioned at all, it is a commented line with the WRR-02 warning.
  *Check:* `grep -c '^WORKER_ALLOW_INSECURE_GATEWAY' .env.example` is `0`.

- **WRR-04 (MUST, WRT-02).** Deployment prerequisite, verified **before** the Gateway-side compose change
  merges: a TLS endpoint in front of the Gateway exists and a Worker can reach it over `https://`. If it
  does not exist yet, the documented interim is **keep the Worker on the Gateway host** (commented
  `network_mode: host` block, genuine loopback) — never relaxing `WorkerProperties`, never a plaintext
  relay (WRR-02). The branch's PR description records which of the two states holds at merge time.

- **WRR-05 (MUST, WRT-03).** Private-CA guidance in the Worker README must state, in this order:
  1. Prefer a publicly-trusted or dedicated-internal-CA certificate for the Gateway proxy.
  2. **Do not reuse the `gitlab.local` mkcert CA** for the Gateway proxy, and do not distribute an
     mkcert CA to the Worker fleet — its key sits on a workstation and can mint a cert for any host.
  3. If a custom truststore is unavoidable, it must be a **copy of the JDK `cacerts` with the internal CA
     imported** (extend, never replace), bind-mounted `:ro`, and never baked into the image (WRR-09).
  4. Disabling certificate/hostname verification, a trust-all `SSLContext`, or `-Dcom.sun.net.ssl…`
     workarounds are **forbidden** — WSR-09 is unchanged and remains a blocking MUST.
  *Check:* the four points present; no trust-all construct anywhere in the Worker tree (already true).

- **WRR-06 (MUST, WRT-04).** In the Gateway `docker-compose.yml`, the Gateway port is **not** published on
  all interfaces once Workers are off-host. Either `127.0.0.1:8080:8080` (proxy co-located on the host,
  matching the `postgres` service's existing pattern) or a bind to a private interface plus a host
  firewall rule admitting only the proxy. The rewritten header comment states plainly: **the reverse proxy
  is the only ingress; the origin port must not be reachable from the Worker network.**
  *Check:* the committed compose has no bare `"8080:8080"`; the header says it.

- **WRR-07 (SHOULD, WRT-04/WRT-14).** `DEPLOYMENT.md` gains a short reverse-proxy requirements block:
  forwards `Authorization` unmodified; allows a request body of at least
  `gateway.publish.max-request-body-bytes` (500,000) plus headroom; read/idle timeout comfortably above
  the Worker's `WORKER_GATEWAY_TIMEOUT_SEC` (10s) and heartbeat cadence (60s); and — defence in depth —
  exposes only `/jobs/*` to the Worker network, with `/reviews`, `/backends`, `/metrics` and `/actuator/*`
  restricted to their own sources.

### The new repo's first commit (WRA6)

- **WRR-08 (MUST, WRT-07).** The initial-commit procedure is executed in this exact order, and each step
  is evidenced in the branch PR:
  1. `cp -r`, then `rm -rf target` **and** `find . -name '*.hprof' -o -name '.env*' -o -name '.idea' -o
     -name 'certs' -o -name '*.log'` — expect an empty result.
  2. Write `.gitignore` (`target/`, `.env`, `.env.*`, `certs/`, `*.log`, `*.hprof`, `.idea/`, `*.iml`,
     `.vscode/`) **before** `git init`.
  3. `git init -b master`, then `git add -A -n` (dry run) and review the file list **item by item**
     against the known-good set (`.dockerignore Dockerfile README.md pom.xml src/ docker-compose.yml
     .env.example .gitignore .gitleaks.toml .semgrepignore .github/`). Anything else is investigated, not
     waved through.
  4. `gitleaks dir . --no-banner --redact -c .gitleaks.toml --exit-code 1` — **clean before the commit**,
     not merely before the push.
  5. Commit; `git ls-files | sort` reviewed once more; **only then** `git remote add` + push.
  *Rationale:* after the push, remediation stops being `rm -rf .git` and becomes secret rotation.

- **WRR-09 (MUST, WRT-07/WRT-09).**
  a. `.env` / `.env.*` / `certs/` / `*.hprof` are in the Worker repo's `.gitignore` from the first commit.
  b. `worker/.dockerignore` gains `.env`, `.env.*`, `certs/`, `*.hprof` (it currently has only
     `target/ .git/ *.md Dockerfile .dockerignore`). Harmless today given the narrow `COPY`s; cheap
     insurance now that the build context is a repo root that will hold a real `.env`.
  c. No secret is introduced as a Dockerfile `ARG`/`ENV` and no truststore is `COPY`ed into the image —
     truststores are bind-mounted `:ro` only.
  *Check:* the three files; `docker history` on a locally built image shows no secret-bearing layer.

- **WRR-10 (MUST, WRT-12/WRT-07).** The Worker `.gitleaks.toml` is **`useDefault = true` + the `target/`
  path allowlist only**. The Gateway's five value-scoped exemption regexes are **not** copied — verified:
  none of those fixture values appear anywhere under `worker/src`, so copying them would pre-exempt
  patterns for secrets that do not exist. If a Worker fixture genuinely trips a default rule (candidates:
  `SECRET-CORPORATE-RULEBOOK-CONTENT`, `THE-SECRET-RAW-MODEL-RESPONSE-CONTENT`), add a **value-scoped**
  exemption for that literal — never a file- or path-scoped one.

### CI security gate (WRA2)

- **WRR-11 (MUST, WRT-05).** Ordering is a hard gate, not a follow-up: the Worker repo's
  `security-gate.yml` must exist and show **four green jobs on a real run** (run URL pasted into the
  Gateway PR) **before** the Gateway-side commit that deletes `sca-worker`/`build-test-worker` is merged.
  Worker workflow content: `gitleaks` (`fetch-depth: 0`, `--exit-code 1`), `sca` (CycloneDX
  `2.9.1` → `osv-scanner`, same High/Critical gate logic and the **same pinned versions**:
  `GITLEAKS_VERSION 8.30.1`, `OSV_SCANNER_VERSION 2.4.0`), `semgrep` (see WRR-12), `build-test`
  (`mvn -B -ntp verify`). Triggers `pull_request` + `push: [master]`, matching the Gateway's.

- **WRR-12 (MUST, WRT-12).** Worker semgrep config = `p/java` + `p/secrets` + **`p/spring`**, blocking on
  ERROR. `p/sql-injection` and `.semgrep/rules.yml` are correctly dropped (no JDBC, no GitLab client in
  `worker/` — verified). `p/spring` closes the WSR-17 deviation the Gateway workflow currently carries; if
  it proves too noisy to gate on, record that decision explicitly in the workflow header rather than
  letting it disappear in the copy. `.semgrepignore` is copied verbatim.

- **WRR-13 (MUST, WRT-05).** The Gateway workflow's header comment block is rewritten in the same commit
  that removes the two jobs: it currently asserts "gitleaks/semgrep cover `worker/`", which becomes false
  and would otherwise read as coverage that no longer exists. New text must state that `worker/` is a
  submodule, is **not** checked out in CI, and is gated by its own repo's workflow (with the URL).
  **`submodules: recursive` must not be added to `actions/checkout` in the Gateway workflow** (WRT-11); if
  a future need is proven, use a read-only deploy key or a fine-grained token scoped to
  `AIReviewWorker: contents:read` — never a classic `repo`-scoped PAT.

- **WRR-14 (MUST, WRT-06).** New-repo hardening, verified by inspection **before** the first push where
  possible and immediately after otherwise:
  a. Created **private** (`gh repo create … --private`), visibility re-verified via the API after
     creation — not assumed from the create command.
  b. Every workflow declares `permissions: contents: read` at the top level (as the Gateway's does), so
     the repo-level `GITHUB_TOKEN` default is irrelevant.
  c. **No Actions secrets and no environments** are configured in the Worker repo — its gate needs none.
  d. `master` requires a PR with the four checks green. If branch protection/rulesets are unavailable on
     this account plan, record it as an explicit **ACCEPTED-RISK** line in the Worker README rather than
     leaving it implicit, and keep the PR-only discipline by convention.
  e. Collaborator set is no wider than the Gateway repo's (WRT-11).

### Principle preservation & operations

- **WRR-15 (MUST, WRT-10).** `DEPLOYMENT.md` gains an explicit post-split step: **delete the pre-split
  `.env` from every Worker host** and set the new Worker `.env` to `0600`, owned by the Worker service
  user. If any Worker host ever held the full `.env` (DB/GitLab/CI/Admin secrets), those secrets are
  treated as exposed to that host and **rotated**. Also document `WORKER_TOKEN` rotation order: rotate the
  Gateway value and every Worker `.env` together; the interim is a self-healing `401` window (Workers
  retry `POST /jobs/claim` forever and never exit — `worker/README.md` §9), never data loss.

- **WRR-16 (SHOULD, WRT-13).** One line in the Worker repo's `build-test` job asserting the non-negotiable
  that the split makes non-obvious — no DB driver and no GitLab client in the Worker's dependency tree,
  e.g. `! grep -Eq 'postgresql|jdbc|spring-boot-starter-data|gitlab4j' pom.xml`. Cheap, and it fails loudly
  the day someone adds one. Same sentence stated in prose in the Worker README's header note.

- **WRR-17 (SHOULD, WRT-11/WRT-15).** `CLAUDE.md` §"Workflow for new features" is amended to say that a
  Worker-side change runs the **same** security-gated SDLC in the Worker repo, that its
  architecture/threat-model/SAST artifacts still land in this repo's `docs/`, and that the submodule pin
  bump is the last step. The pin remains documentation, not deployment (§3.2) — **ACCEPTED-RISK** for pin
  drift, unchanged.

---

## 5. Release gate

**Blocking MUSTs:** WRR-01, WRR-02, WRR-03, WRR-04, WRR-05, WRR-06, WRR-08, WRR-09, WRR-10, WRR-11,
WRR-12, WRR-13, WRR-14, WRR-15.
**Tracked SHOULDs (non-blocking):** WRR-07, WRR-16, WRR-17.
**Accepted residuals:** submodule pin drift (§3.2); WSR-INH-1/WSR-INH-2 (shared `WORKER_TOKEN`, a
compromised Worker host reads its own diffs) — unchanged by this split; branch protection availability
(WRR-14d) if the account plan does not offer it.

The two ordering constraints that make the difference between a clean split and an incident:

1. **Worker CI green before the Gateway drops its Worker jobs** (WRR-11).
2. **`gitleaks dir` clean before the first commit, and the file list reviewed before the first push**
   (WRR-08) — the only point where remediation is free.

---

## 6. Answers to the architecture doc's §11 hand-off questions

1. **Is TLS actually enforced; can this ship without it?** Enforced in code, and the code is correct
   (§0, verified). It cannot ship over plaintext *through* the Worker — but it can ship over plaintext
   *around* it, via a non-encrypting loopback relay (WRT-01). That is an ops control (WRR-02/WRR-03), not
   a code control, and it is the one thing to watch for after this lands. The second-order risk is
   WRT-02: the fail-closed refusal creates pressure to weaken the check; WRR-01/WRR-04 exist to absorb it.
2. **Is the `.env` split safe?** Yes, and it is a net improvement — but conditionally: only after the old
   full `.env` is removed from Worker hosts (WRR-15). New leak paths are the two committed
   `.env.example`s and the two committed compose files (WRT-08 → WRR-03/WRR-08), the image layers
   (WRT-09 → WRR-09), and the initial commit (WRT-07 → WRR-08). All are cheap to close before the fact and
   expensive after.
3. **Does the CI split create a gap?** Yes, two: an **ordering** gap (WRT-05 → WRR-11) and a **silent-green**
   gap — the surviving whole-tree scanners would scan an empty `worker/` and pass (WRT-05 → WRR-13). Plus
   the new repo's defaults (WRT-06 → WRR-14).
4. **Initial-commit residual risk?** Real but currently small: today's `worker/` holds no `.env`, no IDE
   files, only `target/`. The risk is drift between now and implementation — including a `*.hprof` heap
   dump, which by WT-10's own reasoning would contain `WORKER_TOKEN` and a full diff. WRR-08 is the
   procedure; the dry-run file review is its load-bearing step.
5. **Non-negotiables?** None violated. "Gateway is the sole owner of business logic and state",
   "PostgreSQL is the single source of truth", "Worker is a fully stateless HTTP client… never talks to
   GitLab or PostgreSQL", and "no extra infrastructure" all hold — `backend-seed` correctly stays
   Gateway-side, and the Worker's dependency tree contains no DB or GitLab client (verified). The
   reverse proxy is not new infrastructure; `DEPLOYMENT.md` §2/§11.1 already assumes it. The only erosion
   risk is **future** (WRT-13), which WRR-16 turns into a one-line CI assertion.

---

`docs/` is gitignored in this repo — commit with
`git add -f docs/worker-repo-split-threat-model.md`.
