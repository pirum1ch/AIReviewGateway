# Worker/Gateway repository split — architecture note

**Feature slug:** `worker-repo-split` (suggested branch: `chore/worker-repo-split`)
**Type:** repository + deployment topology change. **No Java code changes.** No new infrastructure, no new abstractions, no runtime behavior change on either side of the Worker protocol.

Design input for the `appsec-engineer` threat-model round and the `backend-developer` implementation round. Describes *what* the split looks like when done; it does not create the GitHub repo, touch remotes, or run `git submodule add`.

## 1. Decision summary (confirmed with the user)

1. `worker/` becomes its own private repo `github.com/pirum1ch/AIReviewWorker`, **fresh history**.
2. Wired back into the Gateway repo as a **submodule at the same path `worker/`**, so a `--recurse-submodules` clone looks exactly like today's checkout.
3. Gateway `docker-compose.yml` deploys **only** `postgres` + `gateway` + `backend-seed`. No Worker service, no `network_mode` trick, no Worker image built from it.
4. Worker repo gets its **own** `docker-compose.yml`: one `worker` service against a **remote** Gateway and a **remote** `llama-server`.
5. `network_mode: "service:gateway"` disappears entirely — §6.
6. Two `.env` files, three values that must stay equal across them — §7.
7. Doc updates enumerated in §9.

Nothing about the Worker protocol, `WorkerProperties` validation, the PostgreSQL queue, or `CLAUDE.md`'s non-negotiables changes. In particular: **`WorkerProperties`' plain-HTTP/loopback rule is not relaxed, weakened, or made configurable by this split.**

## 2. Final layout

### 2.1 Gateway repo (this one)

```
AIReviewGateway/
├── pom.xml                     # review-gateway (unchanged, still standalone)
├── Dockerfile                  # unchanged
├── docker-compose.yml          # REWRITTEN: postgres + gateway + backend-seed only
├── .env                        # gitignored; gateway-only keys after the split (§7.1)
├── .env.example                # NEW (optional) — key list, no values
├── certs/                      # gitignored; GitLab mkcert CA truststore (unchanged)
├── .github/workflows/security-gate.yml   # sca-worker + build-test-worker REMOVED (§8)
├── .gitmodules                 # NEW: worker -> github.com/pirum1ch/AIReviewWorker
├── worker/                     # NOW A SUBMODULE (gitlink), not tracked content
├── src/…                       # unchanged
└── docs/                       # unchanged; stays the platform docs home (§9.6)
```

### 2.2 Worker repo (new)

```
AIReviewWorker/                 # == today's worker/ tree verbatim, minus target/
├── pom.xml                     # llm-worker (already standalone: own Boot parent, not a root module)
├── Dockerfile                  # unchanged (build context was already ./worker)
├── .dockerignore               # unchanged
├── docker-compose.yml          # NEW (§5)
├── .env / .env.example         # worker-only keys (§7.2)
├── .gitignore                  # NEW: target/, .env, certs/, *.log, .idea/
├── .gitleaks.toml              # NEW: copy, trimmed to the Worker's own fixtures
├── .semgrepignore               # NEW: copy verbatim
├── .github/workflows/security-gate.yml   # NEW: gitleaks + sca + semgrep + build-test (§8)
├── README.md                   # today's worker/README.md, link/command fixes (§9.4)
└── src/…                       # unchanged
```

`worker/pom.xml` (`<relativePath/>`, own Boot 3.5.16 parent, explicit "NOT a module of the root pom" comment) and `worker/Dockerfile` (`COPY pom.xml` / `COPY src`, context already `./worker`) are **already** repo-root-relative — nothing in the Worker build changes to make it standalone. That was the plan when it was written.

`.semgrep/rules.yml` is **not** copied: both rules are GitLab-diff-fetch-specific (WHR-06/WHR-19), i.e. Gateway-only code paths.

## 3. Submodule setup (implementation-time commands — do not run now)

Git Bash, `master` on both sides to match this repo.

```bash
# --- Step 1: seed the new repo from the current worker/ tree (fresh history) ---
mkdir -p /c/Develop/AIReviewWorker && cd /c/Develop/AIReviewWorker
cp -r /c/Develop/AIReviewGateway/worker/. .
rm -rf target                                   # build output must not be in the initial commit
git init -b master
# add .gitignore / .env.example / .gitleaks.toml / .semgrepignore / .github + docker-compose.yml first
git add . && git commit -m "chore: import LLM Worker from AIReviewGateway (fresh history)"
# create the PRIVATE repo in the GitHub UI (or `gh repo create`), then:
git remote add origin git@github.com:pirum1ch/AIReviewWorker.git
git push -u origin master
```

Run `gitleaks dir .` locally before that first push: the initial commit is the one commit the Gateway repo's history-wide scan will never cover.

```bash
# --- Step 2: replace worker/ with a submodule in the Gateway repo ---
cd /c/Develop/AIReviewGateway
git checkout -b chore/worker-repo-split
git rm -r worker                                # must be COMMITTED before `submodule add`:
git commit -m "chore: worker moves to its own repository (github.com/pirum1ch/AIReviewWorker)"
#                                                 git refuses to add a submodule at a path that
#                                                 still has index entries or a non-empty directory
git submodule add git@github.com:pirum1ch/AIReviewWorker.git worker
git commit -m "chore: wire the Worker repo in as a submodule at worker/"
```

`git rm -r worker` also deletes the working-tree files; `submodule add` re-clones them. A stale `worker/target/` (gitignored, so untouched by `git rm`) will block the add — delete it manually first.

### 3.1 Consumer-side commands (for the docs)

```bash
git clone --recurse-submodules git@github.com:<owner>/AIReviewGateway.git   # fresh clone
git submodule update --init worker                                          # existing checkout

cd worker && git checkout master && git pull && cd ..                       # bump the pin
git add worker && git commit -m "chore: bump worker submodule to <sha>"
```

### 3.2 What the pin means (and does not)

The gitlink records **exactly one Worker commit** per Gateway commit. That is a *documentation* artifact: the answer to "which Worker build was this Gateway build designed against" — precisely what the "Workers first, Gateway second" rollout rule (`DEPLOYMENT.md` §2/§8c, `README.md` §4.5) needs and never had. It is **not** a deployment mechanism: each Mac mini deploys from its own clone, the Gateway never reads the submodule at runtime, and a stale pin cannot break production.

## 4. Gateway `docker-compose.yml` after the split

Today's file **minus** the two Worker services, minus the loopback prose in the header.

| Service | Change | Notes |
|---|---|---|
| `postgres` | **unchanged** | `postgres:14-alpine`, `127.0.0.1:5432` publish, `postgres-data` volume, `pg_isready` healthcheck, `airg` network. |
| `gateway` | **unchanged** | Same `environment:` block (all keys stay — `WORKER_TOKEN` is the Gateway's own required secret), `ports: 8080:8080`, `extra_hosts: gitlab.local`, `certs/cacerts` mount, baked-in `HEALTHCHECK`. |
| `backend-seed` | **unchanged** | Stays Gateway-side: only the Gateway owns PostgreSQL (non-negotiable principle). Still seeds `llama-01`/`llama-02` from `LLAMA_URL_1`/`LLAMA_URL_2`/`LLAMA_MODEL`, still `ON CONFLICT (name) DO NOTHING`. |
| `worker1`, `worker2` | **DELETED** | Along with `network_mode: "service:gateway"`, the `WORKER_HTTP_PORT` de-collision, `WORKER_ALLOW_INSECURE_GATEWAY`, the `LLAMA_*` worker knobs and `WORKER_ID_1/2`. |
| `volumes:`, `networks:` | **unchanged** | `postgres-data`, `airg`. |

Header comment rewrite (the current ~45-line block is mostly about the loopback trick):

- What this stack is: Postgres + Gateway + one-shot backend registration. **Workers are not part of it** — they live in `github.com/pirum1ch/AIReviewWorker`, one per `llama-server` host.
- `LLAMA_URL_*`/`LLAMA_MODEL` here register **backend rows** (health-probe target + the `BACKEND_ID` lookup key); they are not what the Worker uses for inference — the Worker has its own `LLAMA_URL` on its own host, and the two must point at the same `llama-server`.
- Required-secrets list, minus the Worker-only ones.
- One line on `WORKER_TOKEN`: the Gateway validates it; each Worker host gets the same value as its `GATEWAY_API_KEY` (§7.3).

**Deployment note (not a file change):** with Workers off-host, `ports: "8080:8080"` is now the only path a Worker has to the Gateway. In production that port must sit behind the TLS-terminating reverse proxy `DEPLOYMENT.md` §2/§11.1 already assumes (and can then be re-bound to `127.0.0.1:8080` like Postgres). See §6.

## 5. Worker `docker-compose.yml` (new, in the Worker repo)

**One service.** Both of its dependencies — Gateway and `llama-server` — are remote and not in this file. That is the point of the split.

```yaml
# Outline, not final YAML.
services:
  worker:
    build: { context: ., dockerfile: Dockerfile }
    image: llm-worker:local
    restart: unless-stopped          # replaces launchd KeepAlive / systemd Restart=always
    environment:
      # --- required; no defaults, WorkerProperties fails fast without them ---
      GATEWAY_URL:      ${GATEWAY_URL:?e.g. https://gateway.internal}
      GATEWAY_API_KEY:  ${GATEWAY_API_KEY:?must equal the Gateway's WORKER_TOKEN}
      WORKER_ID:        ${WORKER_ID:?e.g. worker-llama-01}
      BACKEND_ID:       ${BACKEND_ID:?must equal a backends.name row in the Gateway DB}
      LLAMA_MODEL:      ${LLAMA_MODEL:?}
      LLAMA_URL:        ${LLAMA_URL:?this host's own llama-server}
      # --- optional; each default must equal Dockerfile ENV and application.yml (DEPLOYMENT §8d) ---
      LLAMA_MAX_TOKENS:              ${LLAMA_MAX_TOKENS:-12000}
      LLAMA_ENABLE_THINKING:         ${LLAMA_ENABLE_THINKING:-true}
      LLAMA_ALLOW_NON_LOOPBACK:      ${LLAMA_ALLOW_NON_LOOPBACK:-false}
      WORKER_HEARTBEAT_INTERVAL_SEC: ${WORKER_HEARTBEAT_INTERVAL_SEC:-60}
      WORKER_MAX_CONSTRAINT_BYTES:   ${WORKER_MAX_CONSTRAINT_BYTES:-69632}
    # NO ports: -- Actuator is loopback-only by design (server.address 127.0.0.1, WSR-12/FW-01).
    #   docker compose exec worker curl -s http://127.0.0.1:8081/actuator/health
    # volumes:  # uncomment if the Gateway's reverse proxy uses a private/self-signed CA (§6.2)
    #   - ./certs/cacerts:/opt/java/openjdk/lib/security/cacerts:ro
    #
    # --- dev only, same-host Gateway over plain HTTP (§6.1). Linux hosts only. ---
    # network_mode: "host"
    # + GATEWAY_URL=http://127.0.0.1:8080 and WORKER_ALLOW_INSECURE_GATEWAY=true in .env
```

Deliberately **not** included:

- No `networks:` block — the default bridge suffices for one outbound-only container.
- No second Worker service. One host pairs 1:1 with one `llama-server`; a host genuinely running two copies the service block once, exactly as the old file documented. No scaling knob, no profiles, no `deploy.replicas`.
- No `postgres`/`gateway`/`llama-server` service. The Worker has no DB access at all, and a containerized `llama-server` is another project's concern.
- No `depends_on`/wait-for-Gateway logic — the Worker already retries `POST /jobs/claim` with capped backoff forever and never exits on a Gateway outage (`worker/README.md` §9).
- No pass-through for the ~10 remaining tuning knobs (poll interval, timeouts, byte caps). They are already `ENV`-defaulted in `Dockerfile` + `application.yml`; adding a compose line for each is `DEPLOYMENT.md` §8d's "three layers must agree" trap times ten. The five above are there because they are what this deployment actually tunes (the silent `LLAMA_MAX_TOKENS=4096` regression in §8d is exactly why that one is explicit).

## 6. The loopback-vs-HTTPS decision

**Confirmed: HTTPS-to-a-remote-Gateway is the right and only assumption for the standalone Worker compose. The `network_mode: "service:gateway"` trick does not survive the split and must not be reproduced in another form.**

- The trick existed only because both containers were in one compose project on one host. After the split there is no `gateway` service in the Worker's compose file to share a namespace with, so it is not merely undesirable — it is unexpressible.
- The real topology (Worker on a Mac mini next to its `llama-server`) has the Gateway across the network. `WorkerProperties` requires `https://` for any non-loopback host **regardless of `WORKER_ALLOW_INSECURE_GATEWAY`** — correct for exactly this topology: the bearer token and the full diff cross that hop.
- `DEPLOYMENT.md` §2's network matrix and §11.1 already state this is production. The split introduces no new requirement; it **removes the last setup that was hiding it**.

### 6.1 Is a dev-loopback affordance still needed?

Marginally — worth exactly the three commented-out lines in §5, not a second compose file, an override file, or a profile:

- `network_mode: "host"` + `GATEWAY_URL=http://127.0.0.1:8080` + `WORKER_ALLOW_INSECURE_GATEWAY=true` reproduces the verified `DEPLOYMENT.md` §11.2 recipe for a Worker container on the same host as a Gateway with no TLS in front. **Linux hosts only** — Docker Desktop on macOS/Windows does not hand a container the host's loopback under host networking, and the Mac mini deployment runs the jar under launchd anyway. This is a Linux-dev-box affordance.
- Anything more elaborate (an SSH-tunnel sidecar, a bundled nginx TLS terminator for dev) is more moving parts than the problem deserves.

### 6.2 Two prerequisites the split makes load-bearing — verify before implementing

1. **A TLS endpoint in front of the Gateway must actually exist** before the first Worker moves off-host. Today's stack reaches it on plain `http://127.0.0.1:8080`; nothing in this repo terminates TLS (`server.ssl.*` unset by design). If no reverse proxy is deployed yet, a remote Worker refuses to start — cleanly, with a clear message, but it will not run. Deployment prerequisite, **not** a reason to touch `WorkerProperties`.
2. **If that proxy presents a private/self-signed certificate** (likely — this environment already uses an mkcert CA for `gitlab.local` and mounts `certs/cacerts` into the gateway container), the Worker's JRE rejects it: the Worker uses `java.net.http.HttpClient` with the default truststore and has no CA config of its own. Fix is the pattern the Gateway already uses — mount a `cacerts` copy trusting the proxy's CA (commented out in §5) — or use a publicly-trusted cert. Explicit paragraph in the Worker README; no code change.

## 7. `.env` split

Today's single root `.env` (gitignored, 24 lines) becomes two gitignored files. Both repos should carry a committed `.env.example` (key names, no values) — cheap, and it makes the coupling table discoverable.

### 7.1 Gateway `.env`

| Key | Keep? | Why |
|---|---|---|
| `DB_PASSWORD` | keep | Gateway/Postgres only. |
| `CI_TOKEN`, `ADMIN_TOKEN` | keep | Gateway only; the Worker never sees either. |
| `WORKER_TOKEN` | **keep — shared value** | The Gateway's own required secret; its *value* must equal each Worker's `GATEWAY_API_KEY` (§7.3). |
| `GITLAB_TOKEN`, `GITLAB_BASE_URL` | keep | Gateway only by design (the Worker holds no GitLab credentials). |
| `BACKEND_ALLOWED_HOST_PATTERN` | keep | Gateway-side health-probe host allowlist. |
| `LLAMA_MODEL` | **keep (also on the Worker)** | Here: the `backends.model` value for seeded rows. There: what is sent to `llama-server` and reported as `ResultRequest.model`. Two independent settings that must agree. |
| `LLAMA_URL_1`, `LLAMA_URL_2` | keep | `backends.url` = the Gateway's health-probe target. Must point at the same `llama-server` as the matching Worker's `LLAMA_URL`. |
| `LLAMA_MAX_TOKENS` | **REMOVE** | Worker-only, and already dead weight here: no Gateway property reads it; it was only forwarded to the worker services. |
| `PROMPT_MANAGER_ENABLED`, `GITLAB_PROMPT_TOKEN`, `PROMPT_CORPORATE_*`, `PROMPT_ON_ERROR` | keep | Gateway-only (V3). |
| `ALLOWED_PROMPT_VERSIONS`, `STRUCTURED_OUTPUT_*` | keep | Gateway-only (V5). |
| `WEBHOOK_*`, `GITLAB_DIFF_TOKEN`, `GITLAB_DIFF_MAX_RESPONSE_BYTES` | keep (when that feature lands) | Gateway-only. |

### 7.2 Worker `.env` (per Worker host)

| Key | Source today | Notes |
|---|---|---|
| `GATEWAY_URL` | new (was hardcoded `http://127.0.0.1:8080` in compose) | `https://…` for any remote Gateway. |
| `GATEWAY_API_KEY` | was `${WORKER_TOKEN}` in compose | **Same value** as the Gateway's `WORKER_TOKEN`. |
| `WORKER_ID` | was `WORKER_ID_1`/`_2` | One host, one Worker, one id — no numeric suffixes. |
| `BACKEND_ID` | was hardcoded `llama-01`/`llama-02` | Must equal the `backends.name` row the Gateway seeded. |
| `LLAMA_URL` | was `LLAMA_URL_1`/`_2` | This host's own `llama-server`. |
| `LLAMA_MODEL` | shared name with the Gateway | §7.1. |
| `LLAMA_MAX_TOKENS`, `LLAMA_ENABLE_THINKING` | were in the Gateway `.env`, forwarded | Worker-only from now on. |
| optional tuning (`WORKER_HEARTBEAT_INTERVAL_SEC`, `WORKER_MAX_CONSTRAINT_BYTES`, …) | defaults | Set only what you change. |

Explicitly **absent** Worker-side: no `DB_*`, no `GITLAB_*`, no `CI_TOKEN`, no `ADMIN_TOKEN`. The split makes that structural.

### 7.3 Cross-repo couplings (the part that actually bites)

Nothing validates these across processes. Each is a silent misconfiguration:

| # | Gateway side | Worker side | Symptom when they disagree |
|---|---|---|---|
| 1 | `WORKER_TOKEN` | `GATEWAY_API_KEY` | Every claim `401`s; Worker logs `Gateway unavailable while claiming`; reviews sit in `QUEUED`. |
| 2 | `backends.name` (seeded `llama-01`/`llama-02`) | `BACKEND_ID` | Claims return `204` forever — indistinguishable from an empty queue by design. Reviews sit in `QUEUED`. |
| 3 | `LLAMA_URL_1/2` (= `backends.url`, health probe) | `LLAMA_URL` (inference) | Backend flips `SUSPECT` while the Worker happily runs jobs, or vice versa. |
| 4 | `BACKEND_ALLOWED_HOST_PATTERN` | — | Backend registration/probe rejected Gateway-side. |
| 5 | `ALLOWED_PROMPT_VERSIONS` (e.g. adding `v3`) | prompt templates baked into the Worker jar | "Workers first, Gateway second" is now a **cross-repo release-ordering** rule; the submodule pin (§3.2) records the intended pairing. |
| 6 | `gateway.structured.max-schema-bytes`, `gateway.diff.answer-reserve` | `worker.limits.max-constraint-bytes`, `v3.yml maxTokens`, `LLAMA_MAX_TOKENS` | The entire `DEPLOYMENT.md` §8c budget table now spans two repositories. |
| 7 | reverse proxy / TLS endpoint | `GATEWAY_URL` | Worker refuses to start (non-loopback + plain HTTP) or fails the TLS handshake (§6.2). |

## 8. CI impact — the one thing the split genuinely breaks

`.github/workflows/security-gate.yml` has two jobs running `mvn -B -ntp -f worker/pom.xml …` (`sca-worker`, `build-test-worker`), plus header comments asserting gitleaks/semgrep cover `worker/`. `actions/checkout@v4` does **not** fetch submodules by default, so after the split `worker/` is an empty directory in CI and both jobs fail on a missing POM. Must be handled in the same change or the Worker's dependency/test gates silently regress.

**Chosen fix — each repo gates its own code:**

- Gateway workflow: delete `sca-worker` and `build-test-worker`; update the header block. Do **not** add `submodules: recursive` to the checkout — the Worker repo is private (needs a PAT secret in the Gateway repo) and it would make Gateway PRs block on code they did not change.
- Worker repo: a trimmed copy of the same workflow — `gitleaks` (full history, `fetch-depth: 0`), `sca` (CycloneDX SBOM + osv-scanner, same pinned versions, `mvn` at repo root), `semgrep` (`p/java` + `p/secrets`; drop `p/sql-injection` and `.semgrep/rules.yml` — the Worker has no SQL and no GitLab client), `build-test` (`mvn -B -ntp verify`).
- `.gitleaks.toml`: copy with the `target/` path allowlist and `useDefault = true`, keeping only value-scoped exemptions the Worker's own fixtures need.
- `.semgrepignore`: copy verbatim.

`.dockerignore` at the Gateway root keeps its `worker/` line.

## 9. Documentation update checklist

### 9.1 `CLAUDE.md` (Gateway repo)
- "Repository state": the `worker/` bullet — "a separate Maven module" → **a separate Git repository** (`github.com/pirum1ch/AIReviewWorker`), present here as a submodule at `worker/`; clone with `--recurse-submodules` or `git submodule update --init worker`.
- "Build toolchain": `mvn -f worker/pom.xml …` only works after submodule init.
- "Workflow for new features": Worker-side features branch in the Worker repo; SDLC stages unchanged; architecture/threat-model/SAST artifacts still land in this repo's `docs/`; add the submodule-pin bump as the last step of a Worker-side change.
- Add this document to the `docs/` bullet list.

### 9.2 `README.md` (Gateway repo)
- Docker/compose section: stack is Postgres + Gateway + backend registration only; point at the Worker repo's compose for the Worker side.
- Every `worker/README.md` link: still resolves in a `--recurse-submodules` checkout — give the absolute GitHub URL for web readers too.

### 9.3 `DEPLOYMENT.md` (Gateway repo) — biggest surface
- Topology intro: two repositories.
- Prerequisites/network-matrix: plain-HTTP-loopback is no longer a real deployment option, HTTPS via the reverse proxy is mandatory (§6.2); private-CA truststore note.
- Worker build/deploy steps: run from the Worker repo now.
- Config reference: the `LLAMA_MAX_TOKENS` "three places must agree" checklist now spans two repos; Worker budget-table rows marked as living in `AIReviewWorker`.
- Docker section: rework around §11.1 (production, off-host) as primary; §11.2 (`--network host` smoke test) stays as a same-host dev recipe needing both repos checked out; the `network_mode: "service:gateway"` paragraph is deleted; add the §7.3 coupling table.

### 9.4 `README.md` in the Worker repo (today's `worker/README.md`)
- Header note: consumed as a submodule of `AIReviewGateway` at `worker/`.
- Build commands: drop the `worker/` path prefix (now repo root).
- New "Docker Compose" section: the one-service file (§5), `.env` keys (§7.2), the Actuator check, the private-CA truststore mount note.
- Rewrite the `GATEWAY_URL`/loopback section around remote-Gateway-over-HTTPS as the default.

### 9.5 CI / scanner configs
Per §8.

### 9.6 Docs that stay put
`docs/worker-architecture.md`, `docs/worker-threat-model.md`, `docs/security/worker-sast-report.md`, `docs/worker-observability-and-claim-latency-*.md`, and the root spec docs stay in the Gateway repo — platform-level artifacts describing both sides of one protocol. The Worker README gets one line pointing at them.

`docs/` is gitignored here — commit this file with `git add -f docs/worker-gateway-split-architecture.md`.

## 10. Risks and trade-offs

| Risk | Mitigation |
|---|---|
| **CI coverage regression** — Worker code ungated if the new Worker workflow is not created in the same change. | §8 is a hard must-do, not a follow-up. Verify four green jobs on the first Worker-repo push before merging the Gateway-side removal. |
| **HTTPS prerequisite blocks the first off-host Worker** if no reverse proxy exists yet. | §6.2 — verify before implementing. Interim: keep the Worker on the Gateway host with the commented-out `network_mode: host` block. Never by relaxing `WorkerProperties`. |
| **Private-CA TLS failure** Worker → Gateway proxy. | §6.2 truststore mount, mirroring the Gateway's existing `certs/cacerts` pattern. |
| **Submodule friction** — a plain `git clone` leaves `worker/` empty. | One line in `CLAUDE.md` + `README.md`; `git submodule update --init worker` is the whole fix. |
| **Pin drift.** | Accepted. The pin is documentation (§3.2), not deployment. |
| **Two-repo release coordination** for "Workers first, Gateway second". | Coupling table (§7.3) lands in `DEPLOYMENT.md`; the pin records the intended pairing. |
| **Secret sprawl** — `GATEWAY_API_KEY` in a `.env` on every Worker host. | Already true for the launchd/systemd path. Net posture *improves*: Worker hosts no longer sit next to a `.env` holding `GITLAB_TOKEN`/`DB_PASSWORD`/`ADMIN_TOKEN`. |
| **Loss of the one-command local stack**. | Accepted; it is the point of the split. `DEPLOYMENT.md` §11.2's `--network host` recipe remains the verified same-host end-to-end path. |

## 11. Hand-off notes

- **appsec-engineer** — review surface: (a) the Worker→Gateway hop moves from a shared container network namespace to a real network, making TLS load-bearing rather than incidental (§6); (b) secret distribution changes shape — one shared `.env` becomes two, with a strictly smaller secret set on Worker hosts (§7.2); (c) the CI security gate splits in two and must not lose Worker coverage (§8); (d) a new private repo's initial commit is the one commit the Gateway repo's full-history gitleaks scan will never see (§3). No trust boundary, authN/authZ decision, or validation rule changes — `WorkerProperties`, the bearer-token model, and the Worker protocol are untouched by design.
- **backend-developer** — deliverables: the two `docker-compose.yml` files, two `.env.example` files, the Worker repo's `.gitignore`/`.gitleaks.toml`/`.semgrepignore`/workflow, the Gateway workflow trim, the submodule wiring (§3), and the doc updates (§9). No Java changes; `mvn -q test` must stay green on both sides.

---

Skipped: a shared-config mechanism, a CI submodule checkout with a PAT, moving worker docs to the new repo, any `WorkerProperties` change. Add the first two only if cross-repo config drift or a genuine need to gate Worker code from Gateway PRs actually shows up.
