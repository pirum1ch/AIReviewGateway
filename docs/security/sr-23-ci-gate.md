# SR-23 — CI/CD Security Gate (implementation & local verification)

Status: **CLOSED (implemented).** SR-23 was the one open MUST control at the end of feature-03
(`docs/security/feature-03-sast-report.md`). This document records the implementation on branch
`feature/04-ci-security-gate` and the local verification of each scanner.

Host reality: the code is hosted on **GitHub** (`github.com/pirum1ch/AIReviewGateway`) — GitLab is the
*product* integration, not the code host — so the gate is **GitHub Actions**.

## Files added

| File | Purpose |
|------|---------|
| `.github/workflows/security-gate.yml` | The gate: 4 jobs on every PR + push to `master`. |
| `.gitleaks.toml` | Full default ruleset + **value-scoped** allowlist for known test fixtures (no rule disabled, no whole-file exemption of source). |
| `.semgrepignore` | Excludes generated build output (`target/`) and non-code (`docs/`, `*.md`) from semgrep. |

No production Java (`src/main/**`) or test code was modified.

## The gate (`security-gate.yml`)

`on: pull_request` and `on: push` to `master`; workflow-level `permissions: contents: read` (least
privilege). Scanner binary versions are pinned (gitleaks `8.30.1`, osv-scanner `2.4.0`). Four independent
jobs — any red job blocks the merge:

1. **gitleaks** — `actions/checkout` with `fetch-depth: 0` (full history), then
   `gitleaks git --redact -c .gitleaks.toml --exit-code 1 .`. **Blocking** on any leak. `--redact` keeps
   any hit out of the build log.
2. **sca (osv-scanner)** — Temurin JDK 21, generate a **CycloneDX SBOM** with the CycloneDX Maven plugin
   (`makeAggregateBom`, fully-resolved transitive tree), then `osv-scanner -L target/bom.json`. A `jq`+`awk`
   gate **blocks on High/Critical** (CVSS ≥ 7.0, or a GHSA labeled HIGH/CRITICAL); Medium/Low are printed
   but non-blocking. This is the control that would have caught the F01-01 EOL-Spring-Boot CVEs.
   *Why SBOM, not osv-scanner's native pom scan:* the native Maven resolver chokes on this project's
   BOM-managed empty-version entries (`spring-security-test`) and hits Maven-registry 429s; a
   Maven-generated SBOM is deterministic.
3. **semgrep** — container `semgrep/semgrep`; one informational full scan (`|| true`) then a **blocking**
   `--error --severity ERROR` scan over `p/java`, `p/spring`, `p/sql-injection`, `p/secrets`.
4. **build-test** — Temurin JDK 21, `mvn -B -ntp verify`. Zonky embedded-postgres runs on the ubuntu
   runner **without Docker**.

## Local verification (scanners run against the repo exactly as CI invokes them)

Env: `JAVA_HOME=$HOME/tools/jdk-21.0.11+10`, plus Maven 3.9.9. gitleaks and osv-scanner binaries were
downloaded to `~/tools` and run locally. semgrep could **not** be run locally (see below).

### gitleaks 8.30.1 — full history + working tree

- **Raw scan (no config):** 2 findings, both `generic-api-key` in
  `src/test/java/com/review/gateway/service/EventServiceTest.java` (lines 61, 66) — the strings
  `apikey=AKIA1234567890` / `AKIA1234567890`. **Analysis:** these are intentional fake secrets fed *into*
  `EventService.record(...)` to prove the SR-12/SR-14 scrubber redacts them; not real secrets.
- **`src/main/**`: zero findings.** No real or fixture secret exists in production code (nothing to report
  to the dev agent).
- **Resolution — `.gitleaks.toml` value-scoped allowlist (not whole-file, no rule disabled):**
  exemptions are keyed on the **exact known fixture values** via `regexTarget = "line"` regexes —
  the dummy ≥32-char context-boot tokens (`test-{ci,worker,admin,gitlab}-token-…`, `totally-unrecognized-
  token-value-xyz-000`) and the EventService scrubber inputs (`AKIA1234567890`, `s3cr3t-value-xyz`,
  `Bearer abc.def.ghi123`, `password: hunter2`, `secret=topsecret`). `target/` (generated, gitignored) is
  path-exempted as build output.
- **Post-config result:** `gitleaks git` (full history, 19 commits) → **no leaks**; `gitleaks dir`
  (working tree) → **no leaks**.
- **Negative control (proves the allowlist is not over-broad):** a GitHub-PAT canary
  (`ghp_…36chars`) appended to the *exempted* `EventServiceTest.java` is **still caught** (`leaks found: 1`,
  exit 1). The exemption is value-specific — any *new* secret in these same test files still trips the gate.

### osv-scanner 2.4.0 — dependency CVEs (via CycloneDX SBOM, 80 packages)

Two advisories found, **both below the High/Critical gate threshold** → SCA gate **PASS**:

| Package | Version | Advisory | Severity | Fix |
|---------|---------|----------|----------|-----|
| `ch.qos.logback:logback-core` | 1.5.34 | GHSA-jhq6-gfmj-v8fx (CVE-2026-10532) | **Low** (CVSS 2.9) | 1.5.35 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.21.4 | GHSA-5jmj-h7xm-6q6v (CVE-2026-54515) | **Medium** (CVSS 5.3) | none yet |

- Gate reproduced locally: highest CVSS = 5.3, High/Critical-labeled = 0 → **PASS** (exit 0).
- **Disposition (dependency findings — for the dev agent, not fixed here per scope):** `logback-core`
  1.5.34 → **1.5.35** is a trivial patch bump that clears the Low advisory — recommended.
  `jackson-databind` 2.21.4 (Medium, information-integrity `C:N/I:L/A:N`, no fix published) — monitor;
  re-evaluate when a fixed release exists. Neither blocks the gate. Not suppressed in config — they stay
  visible in the SCA table so the team sees them.

### semgrep — NOT verified locally

The sandbox has no `pip`/`pipx`/`ensurepip` and no Docker, so semgrep could not be installed or run
locally. Only its **configuration** (`.semgrepignore` + the workflow's rule set/severity gate) is provided;
it executes in CI via the official `semgrep/semgrep` container, which has network access to the semgrep
registry (`p/java`, `p/spring`, `p/sql-injection`, `p/secrets`). **This is the one gate component whose
findings have not been observed locally** — its first real run will be on the opening PR of this branch.
(Note: features 01–03 were manually reviewed by AppSec for the same rule classes — injection, secrets,
Spring misconfig — and came back clean, so a large ERROR-severity surprise is unlikely, but unverified.)

## Residuals / hardening notes

- **semgrep unverified locally** (above) — first CI run is the real check.
- **Action version pinning:** `actions/checkout@v4`, `actions/setup-java@v4`, and the `semgrep/semgrep`
  container use moving tags. Optional supply-chain hardening: pin to commit SHAs / a digest.
- **osv-scanner Medium/Low** (logback, jackson) are intentionally non-blocking; the logback bump is
  recommended to the dev agent.

## SR-23 outcome

**CLOSED.** Every threat-model §5 gate element is implemented and (except semgrep) locally verified against
a clean result: secret scan over full history (blocking), SCA blocking on High/Critical over the resolved
dependency tree, semgrep ERROR-severity gate, and build+test on JDK 21. The whole-system MUST-controls set
(feature-03 report) now has **no open MUST** — SR-23 moves from OPEN to CLOSED.
