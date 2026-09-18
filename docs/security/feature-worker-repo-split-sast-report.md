# AppSec SAST / Final-Verification Report — `chore/worker-repo-split` (Worker/Gateway repository & deployment-topology split)

Scope: `master..chore/worker-repo-split`, HEAD `1364cb8`, working tree clean. 6 commits
(`8deff2a` architecture + threat model → `7195c30` Gateway compose → `7dcf82d` docs catch-up →
`68eaf8f` `git rm -r worker/` → `0d8476e` submodule wiring → `1364cb8` Gateway CI job removal),
75 files, +840/−8565.

Plus the two commits on the new Worker repository's own `master`
(`github.com/pirum1ch/AIReviewWorker`): `60b7adc` (WRR-08 initial import) and `896301e` (CI-fix round:
`log4j2.version`/`tomcat.version` pins + removal of the non-existent `p/spring` semgrep config).
`896301e` is the commit the `worker/` gitlink pins.

In scope: `docker-compose.yml` (Gateway), `.env.example` (Gateway, new), `.gitmodules` (new),
`.github/workflows/security-gate.yml` (Gateway), `CLAUDE.md`, `README.md`, `DEPLOYMENT.md`, the removal
of the `worker/` tree, and the **entire content of the new Worker repository** (its `docker-compose.yml`,
`.env.example`, `.gitignore`, `.dockerignore`, `.gitleaks.toml`, `.semgrepignore`, `Dockerfile`,
`README.md`, `pom.xml`, `.github/workflows/security-gate.yml`, and the 61-file `src/` tree).

Out of scope by design: no Java source changes anywhere (this is the WRR-01 property, verified below).

Method: independent re-verification of every one of **WRR-01…WRR-17**
(`docs/worker-repo-split-threat-model.md` §4, release gate §5) against what is actually on disk and in
git — reading the shipped artifacts and querying git/GitHub directly, never the architecture doc and
never the developer's own summaries. Where a requirement was checkable only against live GitHub state
(WRR-11, WRR-14) I queried the GitHub REST API directly rather than taking the reported status.

**Independent evidence collected by me (not reported to me):**

- **Tree-identity proof for WRR-01.** `git rev-parse master:worker/src` = `git -C worker rev-parse
  60b7adc:src` = `git -C worker rev-parse 896301e:src` = **`dc55f9ff74da7a0c7d15d55aab4aa3a06863631d`**.
  A matching Git tree object over 61 files is a byte-for-byte identity proof, stronger than any diff.
- **Worker CI, verified via the public Actions API** (not the Actions UI): run
  [`34355749834`](https://github.com/pirum1ch/AIReviewWorker/actions/runs/34355749834) on `896301e`,
  event `push`, conclusion `success`, **all four jobs** `success` — `Secret scan (gitleaks, full
  history)`, `Dependency CVEs (osv-scanner, block High/Critical)`, `SAST (semgrep, block
  ERROR-severity)`, `Build & test (Temurin JDK 21)`. The prior run `34349976401` on `60b7adc` shows the
  two genuine failures (`semgrep`, `sca`) that `896301e` fixed — the gate demonstrably fails red before
  it passes green, which is the evidence that matters.
- **`p/spring` really does not exist.** `curl -o /dev/null -w '%{http_code}' https://semgrep.dev/c/p/spring`
  → **404**; `p/java` → 200; `p/secrets` → 200. See F-WRS-04 / the WRR-12 amendment.
- **Worker repo visibility, queried unauthenticated.** `GET api.github.com/repos/pirum1ch/AIReviewWorker`
  → HTTP 200, `"private": false`, `"visibility": "public"`; unauthenticated
  `git ls-remote https://github.com/pirum1ch/AIReviewWorker` succeeds and returns the tip. See F-WRS-06.
- **Compose validity/binding, resolved not eyeballed.** `docker compose config` on the Gateway file emits
  `host_ip: 127.0.0.1 / target: 8080 / published: "8080"`; `docker compose --env-file /dev/null config`
  → exit 1 with `required variable WORKER_TOKEN is missing a value` / `DB_PASSWORD is missing a value`
  (the fail-fast works, and my first attempt only passed because this machine has a real untracked
  `.env` — noted so the next round does not repeat the mistake). The Worker compose parses clean with
  its six required vars supplied.
- **Gateway build:** `mvn -q compile` on this branch → exit 0.

**Scanners:** `gitleaks`, `semgrep` and `osv-scanner` are not installed on this machine. For the Worker
tree the authoritative evidence is the green CI run above (full-history gitleaks with `fetch-depth: 0`,
semgrep `p/java`+`p/secrets` ERROR gate, osv-scanner over a CycloneDX SBOM) — a real run on the exact
pinned commit, which is better evidence than a local re-run. For the Gateway-side diff I did a manual
secret review of every added line (`git diff master..HEAD | grep '^+'` filtered for ≥32-char tokens,
`BEGIN * PRIVATE KEY`, `AKIA`, `ghp_`, `glpat-`) → **no secret-shaped content**; the only new
secret-adjacent file is `.env.example`, which is key-names-with-empty-values throughout.

---

## Verdict: **PASS WITH CONDITIONS** — the branch itself is clean; the release gate is not fully met, and the two gaps are outside the branch diff.

Severity counts: Critical 0 · High 0 · **Medium 4** · Low 1 · Info 2.

**Twelve of the fourteen blocking MUSTs are cleanly and independently verified.** The branch content is
the best-executed of the artifacts I have reviewed on this project: WRR-01's tree identity is proven by
object hash rather than argued; WRR-08's initial-commit hygiene is provably perfect (the first commit's
non-`src/` file list is *exactly* the ten-item known-good set the threat model specified, and the full
two-commit history contains no `target/`, no `.env`, no `.idea`, no `certs/`, no `*.hprof`, no `*.log`);
WRR-11's ordering constraint — the one the threat model called out as "the difference between a clean
split and an incident" — genuinely held, with a red run followed by a green one on the pinned commit
before the Gateway-side job removal was even committed to a mergeable branch.

The two failures are both **WRR-14 (new-repo hardening)**, and neither lives in the diff:

1. **F-WRS-06 (Medium, blocking MUST WRR-14a). `pirum1ch/AIReviewWorker` is PUBLIC, not private.**
   This is WRT-06's exact predicted scenario. The architecture doc, the threat model (§1: "`github.com/
   pirum1ch/AIReviewWorker` (private)") and the hand-off brief all describe the repo as private; it is
   not. Marginal impact is genuinely small — `pirum1ch/AIReviewGateway` is *also* public and already
   carries `docs/worker-architecture.md`, `docs/worker-threat-model.md`,
   `docs/security/worker-sast-report.md` and the Worker README in its tracked tree, so the Worker source
   and its token model are already world-readable regardless. But the deviation from a blocking MUST must
   become an **explicit, recorded owner decision** rather than a default nobody looked at, and it makes
   WRT-06's other legs live (fork PRs against `pull_request` CI, world-readable Actions logs).
2. **F-WRS-07 (Medium, blocking MUST WRR-14d). No branch protection, no ruleset, and no ACCEPTED-RISK
   line.** `GET /branches` → `master: protected: false`; `GET /rulesets` → `[]`; both Worker commits went
   straight to `master` with no PR. WRR-14d's own escape hatch (record it as an explicit ACCEPTED-RISK
   line in the Worker README) was not taken either, so the gap is currently implicit — which is the one
   outcome WRR-14d was written to prevent.

**Neither is a reason to hold this branch.** The Worker repo is already created, already public and
already pushed; refusing the merge changes none of that and reduces no risk. Both are settings/one-line-doc
actions, closable in minutes, independent of the merge.

Two further Medium findings are ordinary doc gaps in this repo, routable to `backend-developer`:

3. **F-WRS-01 (Medium, WRR-07 SHOULD).** The reverse-proxy requirements block was not written. WRT-14
   (a proxy body limit below 500,000 bytes, an aggressive read timeout, or a proxy that rewrites
   `Authorization`, turning every result into a fleet-wide silent stall) is currently unmitigated in the
   runbook.
4. **F-WRS-02 (Medium, contradicts WRR-06).** `DEPLOYMENT.md` §11.1 still instructs operators to run the
   Gateway with `docker run -p 8080:8080` — publishing the origin port on all interfaces, which is
   precisely WRT-04 and precisely what WRR-06 closed in `docker-compose.yml`. The compose path was fixed;
   the bare-Docker path documented three sections later was not.

**AppSec position on the merge:** merge `chore/worker-repo-split` → `master`. Close F-WRS-06 and F-WRS-07
in the same working session (they are the release gate); route F-WRS-01 and F-WRS-02 to
`backend-developer` as a small docs pass.

---

## 1. WRR-01…WRR-17 checklist

| WRR | Tag | Verdict | Evidence |
|---|---|:---:|---|
| **WRR-01** — `WorkerProperties` / `worker/src` untouched | MUST | **PASS** | `git diff --name-status master..HEAD -- worker/src` → 61 entries, **all `D`**, zero `M`/`A`. Per-commit: only `68eaf8f` touches `worker/` at all (65 deletions) and only `0d8476e` adds the gitlink. Tree identity: `master:worker/src` ≡ `60b7adc:src` ≡ `896301e:src` = `dc55f9f`. No new flag, no widened loopback list, no `worker.allow-insecure-gateway` semantics change — there is no Java delta at all to carry one. |
| **WRR-02** — dev-loopback affordance commented + forbidden-pattern warning ×3 | MUST | **PASS** | `worker/docker-compose.yml:46-58`: `network_mode: "host"` and the `WORKER_ALLOW_INSECURE_GATEWAY=true` line are both comments; the warning names `socat` / plain `proxy_pass` / published-port shim as FORBIDDEN and `ssh -L` / WireGuard / mTLS sidecar as the acceptable encrypting alternative. Same text at `worker/README.md:412-422`. `DEPLOYMENT.md` §11.3 states the forbidden half (see Info F-WRS-05 on the third location). No active `network_mode: host` and no active `WORKER_ALLOW_INSECURE_GATEWAY` anywhere in the shipped compose. |
| **WRR-03** — no active `WORKER_ALLOW_INSECURE_GATEWAY` key in `.env.example` | MUST | **PASS** | `grep -c '^WORKER_ALLOW_INSECURE_GATEWAY' worker/.env.example` → **0**. The only occurrence is a commented line under a "dev-only loopback escape hatch — DO NOT set this for a remote Gateway" block carrying the WRR-02 warning. |
| **WRR-04** — TLS endpoint exists before the compose change merges | MUST | **PARTIAL** | Documentation half done: `DEPLOYMENT.md` §2 now calls a TLS endpoint a "**hard prerequisite before the first off-host Worker is deployed**, not an optional hardening step"; §11.1/§11.3 carry it through. The documented interim (Worker stays on the Gateway host, commented `network_mode: host`, genuine loopback) is present **and actually workable** — I confirmed the Gateway compose publishes on host loopback, which a Linux `network_mode: host` Worker can reach. The *factual* state (does the proxy exist; can a Worker reach it over `https://`?) is not observable from this repo and there is no PR description recording which of the two states holds. → **F-WRS-03**, an operator sign-off item, not a code defect. |
| **WRR-05** — private-CA guidance, four ordered points | MUST | **PASS** | `worker/README.md:424-433` — (1) prefer publicly-trusted/dedicated-internal CA; (2) do not reuse the `gitlab.local` mkcert CA, do not distribute an mkcert CA to the fleet, with the workstation-key rationale spelled out; (3) copy of JDK `cacerts` with the internal CA **imported** ("extend, never replace"), bind-mounted `:ro`, never baked into the image; (4) disabling cert/hostname verification, a trust-all `SSLContext`, or `-Dcom.sun.net.ssl…` are forbidden. No trust-all construct exists in the Worker tree — implied by the WRR-01 tree identity with the already-cleared `master` tree. |
| **WRR-06** — Gateway port not published on all interfaces | MUST | **PASS** | `docker-compose.yml` `gateway.ports: ["127.0.0.1:8080:8080"]`, matching the `postgres` service's existing pattern. Resolved, not eyeballed: `docker compose config` → `host_ip: 127.0.0.1`. Header comment states it plainly: "a TLS-terminating reverse proxy is the ONLY intended ingress to the Gateway — the origin port must not be reachable from the Worker network." (See F-WRS-02: `DEPLOYMENT.md` §11.1 undercuts this for the bare-Docker path.) |
| **WRR-07** — reverse-proxy requirements block in `DEPLOYMENT.md` | SHOULD | **FAIL** | Not written. None of the four required items appear anywhere: no "forwards `Authorization` unmodified", no body-limit floor tied to `gateway.publish.max-request-body-bytes` (500,000) + headroom, no read/idle-timeout guidance above `WORKER_GATEWAY_TIMEOUT_SEC` (10s) / heartbeat cadence (60s), no "expose only `/jobs/*` to the Worker network". → **F-WRS-01**. Non-blocking per §5. |
| **WRR-08** — initial-commit procedure | MUST | **PASS** | `git -C worker ls-tree -r --name-only 60b7adc` non-`src/` entries = `.dockerignore .env.example .github/workflows/security-gate.yml .gitignore .gitleaks.toml .semgrepignore Dockerfile README.md docker-compose.yml pom.xml` — **exactly** the ten-item known-good set from step 3, nothing extra, plus 61 `src/` files. Full-history sweep across both commits (`git log --all --pretty=format: --name-only \| sort -u`) matching `target/|\.env$|\.env\.|\.idea|certs/|\.hprof|\.log$|\.iml` returns **only `.env.example`**. Step 4's pre-commit `gitleaks` is corroborated after the fact by the green full-history `gitleaks git` CI job on `896301e`. |
| **WRR-09** — `.gitignore` / `.dockerignore` / no secret in image | MUST | **PASS** | (a) `worker/.gitignore` = `target/ .env .env.* !.env.example certs/ *.log *.hprof .idea/ *.iml .vscode/` — the `!.env.example` negation is correctly present, otherwise `.env.*` would have swallowed the committed example. (b) `worker/.dockerignore` gained exactly `.env`, `.env.*`, `certs/`, `*.hprof` on top of the original four. (c) `worker/Dockerfile` — every `ENV` is a non-secret tuning default (`WORKER_HTTP_PORT`, `LLAMA_*`, timeouts); no `ARG`, no token, no `COPY` of any truststore; the only `COPY`s are `pom.xml`, `src`, and the built jar. `docker history` not run (no image built here) — the source-level evidence is conclusive. |
| **WRR-10** — Worker `.gitleaks.toml` carries no Gateway value exemptions | MUST | **PASS** | `worker/.gitleaks.toml` = `[extend] useDefault = true` + a single `paths` allowlist for `target/`. **Zero** `regexes`/`stopwords` entries; none of the Gateway's five value-scoped exemptions (`test-(ci\|worker\|admin\|gitlab)-token-[0-9]+`, `AKIA1234567890`, `hunter2`, …) were copied. The file's own header documents the rationale and the "value-scoped only, never file- or path-scoped" rule for future additions. |
| **WRR-11** — Worker gate exists, four green jobs, before the Gateway drops its jobs | MUST | **PASS** | Run `34355749834` on `896301e`: 4/4 `success` (see evidence block). Workflow content matches spec: `gitleaks` with `fetch-depth: 0` and `--exit-code 1`; `sca` with CycloneDX `2.9.1` → `osv-scanner`, identical High/Critical `awk` gate logic; `build-test` with `mvn -B -ntp verify`. Versions pinned **identically** to the Gateway's: `GITLEAKS_VERSION: "8.30.1"`, `OSV_SCANNER_VERSION: "2.4.0"`. Triggers `pull_request` + `push: [master]`, matching. **Ordering held**: the Worker gate was green on the pinned commit before `1364cb8` (the Gateway-side job removal) reached a mergeable state. |
| **WRR-12** — semgrep `p/java` + `p/secrets` + `p/spring` | MUST | **PASS (amended)** | Shipped config is `p/java` + `p/secrets`, ERROR-gated, over the whole tree; `p/sql-injection` and `.semgrep/rules.yml` correctly dropped; `.semgrepignore` copied verbatim. **`p/spring` was an erroneous requirement on my part** — it is not a Semgrep registry shorthand at all (`https://semgrep.dev/c/p/spring` → **HTTP 404**, verified independently by me; the first CI run `34349976401` failed red on exactly this). The developer removed it and recorded the reason in the workflow header (lines 12-15), which is precisely what WRR-12's own escape clause demands ("record that decision explicitly in the workflow header rather than letting it disappear in the copy"). Requirement satisfied as written for the achievable part; see the §3 threat-model amendment and Low finding **F-WRS-04** for the residual. |
| **WRR-13** — Gateway workflow header rewritten, no `submodules:` added | MUST | **PASS** | `sca-worker` and `build-test-worker` deleted in the same commit (`1364cb8`) that rewrote the header. New header (lines 12-22) states `worker/` is a submodule pointing at `github.com/pirum1ch/AIReviewWorker`, that this workflow **does not check it out**, that none of its jobs scan or build it, and that it is gated by its own repo's workflow — closing the WRT-05 "silent green" gap. The false claim "gitleaks/semgrep cover `worker/`" is gone. `grep -rn "submodules" .github/` returns **only the two explanatory comment lines**; no `actions/checkout` step anywhere gained a `submodules:` key, and no PAT/deploy-key secret was introduced. Remaining jobs: `gitleaks`, `sca`, `semgrep`, `build-test`. |
| **WRR-14** — new-repo hardening | MUST | **FAIL (a, d)** | **(a) FAIL** — `"private": false`, `"visibility": "public"`; unauthenticated `git ls-remote` over HTTPS succeeds. → **F-WRS-06**. **(b) PASS** — `permissions: contents: read` declared at workflow top level (line 30-31), so the repo-level `GITHUB_TOKEN` default is irrelevant. **(c) PASS (partial)** — `GET /environments` → `total_count: 0`; the secrets endpoint requires auth, but `grep 'secrets\.'` over the workflow returns nothing, so the gate provably needs and uses no repo secret. **(d) FAIL** — `GET /branches` → `master: protected: false`; `GET /rulesets` → `[]`; both commits pushed directly to `master` with no PR; and no ACCEPTED-RISK line in the Worker README. → **F-WRS-07**. **(e) PASS (partial)** — `GET /contributors` → `[DmitryPirumov]` only; the authoritative collaborator list needs credentials, owner to confirm. |
| **WRR-15** — post-split `.env` cleanup, perms, rotation order | MUST | **PASS** | `DEPLOYMENT.md` §11.3 "Post-split operational step": delete the pre-split combined `.env` from every Worker host; set the new Worker `.env` to `0600` owned by the Worker service user; if a host ever held the full Gateway `.env` (`DB_PASSWORD`/`GITLAB_TOKEN`/`CI_TOKEN`/`ADMIN_TOKEN`), treat those as exposed **and rotate**; `WORKER_TOKEN` rotation documented as a two-repo N-host operation with the self-healing `401` window named as the interim. Cross-repo coupling table present (7 rows, incl. `WORKER_TOKEN`↔`GATEWAY_API_KEY` and the release-ordering row). Incidental confirmation: this machine's own untracked `.env` is already Gateway-only (no `GATEWAY_API_KEY`/`WORKER_ID`/`BACKEND_ID`/`LLAMA_URL`), gitignored at `.gitignore:7`, and `git ls-files --error-unmatch .env` confirms it is **not tracked**. |
| **WRR-16** — CI assertion: no DB/GitLab dependency | SHOULD | **PASS** | `worker/.github/workflows/security-gate.yml:121-123`, inside `build-test`, before `mvn verify`: `! grep -Eq 'postgresql\|jdbc\|spring-boot-starter-data\|gitlab4j' pom.xml`. I ran the predicate against the shipped `pom.xml`: **no match**, so the assertion passes today and will fail loudly the day a driver is added. Prose statement of the same invariant at `worker/README.md:41` ("no business logic, no persistent state, and no GitLab or PostgreSQL access"), `:49-51`, and `:186`. Dependency tree confirmed by inspection: web, actuator, micrometer-prometheus, validation, snakeyaml, + test-scope starter-test and mockwebserver. Nothing else. |
| **WRR-17** — `CLAUDE.md` documents the Worker-side SDLC | SHOULD | **PASS** | `CLAUDE.md:175-182`: Worker-side changes run the same security-gated SDLC in the Worker repo on its own branch/PR gated by *that* repo's `security-gate.yml`; architecture/threat-model/SAST artifacts still land in this repo's `docs/`; the submodule pin bump is the last step, with the exact command; the pin is documentation, not a deployment mechanism. Also amended: "Repository state" (`worker/` is a submodule, `--recurse-submodules`), "Build toolchain" (an uninitialized submodule leaves `worker/` empty). |

**Release gate (§5) roll-up:** 12 of 14 blocking MUSTs **PASS**, 1 **PARTIAL** (WRR-04, ops sign-off),
1 **FAIL** (WRR-14a + WRR-14d). Tracked SHOULDs: WRR-16 PASS, WRR-17 PASS, WRR-07 **FAIL**.

---

## 2. Findings

| # | Sev | CWE / OWASP | Where | Description | Fix |
|---|:---:|---|---|---|---|
| **F-WRS-01** | Medium | CWE-1188, CWE-400 / A04, A05 | `DEPLOYMENT.md` (missing block) | **WRR-07 not implemented.** No reverse-proxy requirements anywhere in the runbook. WRT-14 is therefore unmitigated: an nginx `client_max_body_size` below the Gateway's 500,000-byte `POST /jobs/{id}/result` cap turns every result into a `413`, an aggressive `proxy_read_timeout` turns every long generation into a dropped connection, and a proxy that rewrites or strips `Authorization` turns every call into a `401` — each of which manifests as an opaque fleet-wide heartbeat-timeout retry loop, not as an obvious error. The proxy is now unconditionally in the data path for every Worker call. | Add a short block to `DEPLOYMENT.md` §11.1 with the four WRR-07 items: forwards `Authorization` unmodified; body limit ≥ 500,000 bytes + headroom (nginx default `1m` is adequate — say so, so a hardening pass does not silently lower it); read/idle timeout comfortably above `WORKER_GATEWAY_TIMEOUT_SEC` (10s) and the 60s heartbeat cadence; and, defence in depth, expose only `/jobs/*` to the Worker network with `/reviews*`, `/backends`, `/metrics`, `/actuator/*` restricted to their own sources. A worked nginx snippet would be worth more than prose. |
| **F-WRS-02** | Medium | CWE-668, CWE-1327 / A01, A05 | `DEPLOYMENT.md` §11.1 | **Contradicts WRR-06.** §11.1 tells operators that for the production topology "`docker run -p 8080:8080` for the Gateway … is all that's required" — a bare all-interfaces publish of the origin port, which is verbatim WRT-04 and exactly what WRR-06 just closed in `docker-compose.yml`. An operator following §11.1's bare-Docker path gets a Gateway whose `8080` is directly reachable from the Worker network, bypassing the proxy and every proxy-level control (TLS, IP allowlist, path restriction, body cap, rate limit). The compose path was hardened; the `docker run` path documented three sections later was not. Gateway-side token authz still holds, so this is defence-in-depth/TLS-bypass, not an authz break. | Change §11.1's example to `-p 127.0.0.1:8080:8080` and add the same one-line rationale the compose header already carries ("the reverse proxy is the only intended ingress; the origin port must not be reachable from the Worker network"), with the private-interface + host-firewall variant as the alternative for a proxy that is not co-located. |
| **F-WRS-03** | Info (ops) | CWE-319 / A02 | WRR-04, process | **WRR-04's factual half is unrecorded.** The docs now state the TLS prerequisite correctly and the interim (Worker stays on the Gateway host over genuine loopback) is documented and workable. But nothing records **which of the two states actually holds at merge time** — WRR-04 asked for that in the branch's PR description, and this branch merges locally with no PR. Not a defect in the branch; an unanswered operator question that WRT-02 (schedule pressure to weaken the check) makes worth closing explicitly. | Before the first **off-host** Worker is deployed, the owner states in one line: either (a) `https://gateway.internal` exists and a Worker reached it, or (b) not yet — every Worker stays on the Gateway host on genuine loopback. Record it in the merge commit message or as a line in `DEPLOYMENT.md` §11.1. This does not gate the merge; it gates the first off-host deployment. |
| **F-WRS-04** | Low | CWE-1059 / A06 | `worker/.github/workflows/security-gate.yml` | **No Spring-specific ruleset gates the Worker.** Not the developer's error — `p/spring` does not exist (HTTP 404, verified) and WRR-12's premise was wrong. The removal is correct and correctly documented in the workflow header. The residual is simply that a Spring-framework-specific ruleset covers neither repo. Small in context: the Worker is a ~61-file HTTP client with no controllers accepting untrusted input, no persistence, no templating, no auth surface of its own; `p/java` already carries the Java rules that matter here. | No action on this branch. If a Spring pack is ever wanted, the real path is `--config r/java.spring` (rule-namespace, not a curated pack) or a repo-local `.semgrep/rules.yml`, evaluated for noise first. Track as an accepted residual. |
| **F-WRS-05** | Info | — | `DEPLOYMENT.md` §11.3 | **WRR-02's third location is half-stated.** §11.3 carries the *forbidden* half ("forwarding a loopback setup to a remote Gateway through a non-encrypting relay is forbidden even with `WORKER_ALLOW_INSECURE_GATEWAY=true`") but defers the *acceptable alternative* (an encrypting tunnel: `ssh -L`, WireGuard, mTLS sidecar) to `worker/README.md` §6.3 by reference. A reader who only ever opens `DEPLOYMENT.md` is told what not to do and not what to do instead — which is the shape of prohibition that gets worked around. | One clause in §11.3: "…is forbidden; an **encrypting** tunnel (`ssh -L`, WireGuard, an mTLS mesh sidecar) terminated locally is the supported alternative." Ten words, closes the loop. |
| **F-WRS-06** | **Medium** | CWE-1188, CWE-732 / A05 | `github.com/pirum1ch/AIReviewWorker` settings | **BLOCKING MUST WRR-14a FAILS: the Worker repository is public.** `GET /repos/pirum1ch/AIReviewWorker` (unauthenticated) → HTTP 200, `"private": false`, `"visibility": "public"`; unauthenticated `git ls-remote https://…` returns the tip. This is WRT-06's exact predicted default-settings scenario, and the architecture doc, threat model §1 and the hand-off brief all describe the repo as private. **Mitigating context, verified:** `pirum1ch/AIReviewGateway` is *also* public and its tracked tree already contains `docs/worker-architecture.md`, `docs/worker-threat-model.md`, `docs/security/worker-sast-report.md` and the Worker README — so the Worker's source, protocol and token model are already world-readable, and the marginal disclosure from this repo is near zero. **Non-mitigating:** the repo being public makes WRT-06's other legs live — `pull_request` CI now runs on fork PRs from anyone (bounded: `permissions: contents: read`, zero repo secrets, `pull_request` not `pull_request_target`, so the blast radius is runner minutes rather than credentials), and Actions logs are world-readable. No secret is exposed: the repo demonstrably contains none (WRR-08/WRR-10 evidence + green full-history gitleaks), so **no rotation is required**. | Owner decision, one of two — and it must be *recorded*, not defaulted: (1) `gh repo set-default-branch`-adjacent: `gh repo edit pirum1ch/AIReviewWorker --visibility private --accept-visibility-change-consequences`, then re-verify with an unauthenticated `curl` (WRR-14a explicitly says verify, do not assume); or (2) consciously accept public visibility for **both** repos as the project's posture and amend `docs/worker-repo-split-threat-model.md` §1/§5 and the architecture doc to stop describing the repo as private. I have no preference between the two; I object only to the current state, where the documents say one thing and reality says another. |
| **F-WRS-07** | **Medium** | CWE-1188 / A05 | `github.com/pirum1ch/AIReviewWorker` settings + `worker/README.md` | **BLOCKING MUST WRR-14d FAILS: `master` is unprotected and the gap is implicit.** `GET /branches` → `master: protected: false`; `GET /rulesets` → `[]`; both commits were pushed straight to `master` with no PR, so the four-job gate has never actually gated anything — it has only reported. WRR-14d's fallback (record an explicit ACCEPTED-RISK line in the Worker README if protection is unavailable on the account plan) was not taken either. Note the Gateway repo's own `master` is likewise unprotected, so this is a project-wide posture, not a Worker-repo regression — but the split is exactly the moment the threat model said to fix it, because after it nobody reviews both trees together. **Status update (docs fix round, 2026-09-09):** branch protection is being enabled by the repo owner directly in GitHub settings, in parallel with this docs pass; this document's job is done once the setting is confirmed (a follow-up `GET /branches`/`GET /rulesets` check), not to configure it — no GitHub access is available from this round to do so. **Left as a pending/tracked item, not marked PASS**, since GitHub settings cannot be verified from here. | Either enable a ruleset on `master` requiring a PR with all four checks green (free on public repos; rulesets are also available on private repos on current plans), **or** add one explicit line to `worker/README.md`: "ACCEPTED-RISK (WRR-14d): `master` has no branch protection; the four-job `security-gate` workflow is advisory and PR-only discipline is by convention." Same call should be made for `AIReviewGateway/master` while the topic is open. |

Nothing else. No injection, access-control, deserialization, crypto, path-traversal, SSRF or
error-handling finding — as expected, since the branch contains **zero Java delta** and the Worker `src`
tree is object-identical to the one already cleared by `docs/security/worker-sast-report.md` and the
`fix/worker-observability-and-claim-latency` round.

---

## 3. Threat-model amendments (`docs/worker-repo-split-threat-model.md`)

Two corrections to my own pre-implementation model, recorded here rather than silently:

1. **WRR-12 / WRT-12 were factually wrong about `p/spring`.** I wrote that WSR-17 mandates semgrep
   `p/java` + `p/spring` + `p/secrets` and that the Gateway workflow "silently perpetuates a deviation"
   by omitting `p/spring`. There is no deviation to perpetuate: **`p/spring` is not a Semgrep registry
   pack** (`https://semgrep.dev/c/p/spring` → HTTP 404, verified independently; the Worker repo's first
   CI run failed red on it). The original WSR-17 text carried the same error. WRR-12 should read
   `p/java` + `p/secrets`, with the `p/spring` note demoted to the accepted residual F-WRS-04.
2. **WRA1's "private" attribute in §1 is aspirational, not descriptive.** The asset table records the
   Worker repo as "`github.com/pirum1ch/AIReviewWorker` (private)". It is public (F-WRS-06). Depending
   on the owner's decision, either the setting or the table changes — but they must be made to agree.

Unchanged and re-confirmed: the accepted residuals in §5 (submodule pin drift; WSR-INH-1/WSR-INH-2 —
shared `WORKER_TOKEN`, a compromised Worker host reads its own diffs). The §0 verification of
`WorkerProperties.validateGatewayUrl()` still holds by tree identity — that code is byte-for-byte the
code I read when writing the model.

---

## 4. Non-negotiables (CLAUDE.md) — re-checked

| Principle | Holds? | Evidence |
|---|:---:|---|
| Gateway is the sole owner of business logic and state | ✔ | No Java delta. `backend-seed` correctly stayed Gateway-side in `docker-compose.yml` — the Worker compose has no Postgres service and no DB credentials. |
| PostgreSQL is the single source of truth | ✔ | Unchanged. |
| Worker is a fully stateless HTTP client; never talks to GitLab or PostgreSQL | ✔ | Enforced twice over now: the shipped `pom.xml` has no JDBC/data/GitLab dependency (verified by inspection), and WRR-16's CI assertion fails the build the day one appears — a strictly stronger guarantee than the pre-split co-location it replaces. |
| Queue in PostgreSQL via `FOR UPDATE SKIP LOCKED` | ✔ | Unchanged. |
| No extra infrastructure (no Redis/Kafka/K8s/…) | ✔ | The reverse proxy is not new infrastructure — `DEPLOYMENT.md` §2 assumed it before this branch. The submodule adds no runtime component. |
| Idempotency everywhere a retry can happen | ✔ | Unchanged; `backend-seed` retains its `ON CONFLICT (name) DO NOTHING`. |
| Fail fast at the edge | ✔ | Extended, not weakened: both compose files use `${VAR:?}` for every required secret — `docker compose --env-file /dev/null config` exits 1 naming the missing variable rather than starting with a blank value. |
| Gateway restarts must not disturb in-flight work | ✔ | Unchanged. |

---

## 5. Status

- **Closed / verified this round:** WRR-01, 02, 03, 05, 06, 08, 09, 10, 11, 12 (amended), 13, 15, 16, 17 — **14 items**.
- **Open, blocking the §5 release gate (settings/doc, not code, not in the branch diff):** F-WRS-06 (WRR-14a), F-WRS-07 (WRR-14d) — **2 items**.
- **Open, non-blocking, route to `backend-developer`:** F-WRS-01 (WRR-07), F-WRS-02, F-WRS-05 — **3 items**.
- **Open, operator sign-off before the first off-host Worker (not before merge):** F-WRS-03 (WRR-04) — **1 item**.
- **Accepted residuals:** F-WRS-04 (no Spring ruleset); submodule pin drift (§3.2); WSR-INH-1/WSR-INH-2.

**Merge recommendation: MERGE `chore/worker-repo-split` → `master`.**

The branch's own content passes every check it can be held to, and the two gate failures are properties
of a GitHub repository that already exists, is already public and is already pushed — holding this
branch does not un-publish it, does not add branch protection, and reduces no risk whatsoever. Close
F-WRS-06 and F-WRS-07 as immediate out-of-band actions (both are minutes of work), and route F-WRS-01 /
F-WRS-02 / F-WRS-05 to `backend-developer` as one small docs pass. F-WRS-02 is the one I would not let
sit long: a runbook that says `-p 8080:8080` will eventually be followed.

**Post-merge update (2026-09-09):** `chore/worker-repo-split` merged to `master` (commit `3b27690`) with
F-WRS-06 closed as a recorded owner decision (repo stays public; see the WRR-14a amendment in
`docs/worker-repo-split-threat-model.md`) and F-WRS-01/02/05 closed by `backend-developer`'s docs-only fix
round (commit `683bd4a`, pre-merge). **F-WRS-07 is PARTIALLY closed**: the owner enabled "Require pull
request before merging" on `AIReviewWorker`'s `master`, confirmed via
`GET /repos/pirum1ch/AIReviewWorker/branches/master` → `protected: true` (direct pushes now blocked, the
core WRR-14d ask). Required status checks for the four `security-gate.yml` jobs are not yet configured —
downgraded from blocking MUST to a tracked SHOULD (see the WRR-14d amendment) since PR-required review is
the primary control and is now in place. No open item blocks anything already merged.

---

`docs/` is gitignored in this repo — commit with
`git add -f docs/security/feature-worker-repo-split-sast-report.md`.
