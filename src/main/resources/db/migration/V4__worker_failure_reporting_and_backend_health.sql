-- Worker Observability & Claim Latency (docs/worker-observability-and-claim-latency-architecture.md
-- WOC-10/WOC-40, docs/worker-observability-and-claim-latency-threat-model.md WOT-16).
--
-- Both columns are nullable and additive, with no backfill and no constraint change. Rollback-tolerant:
-- an older JAR simply ignores both columns and degrades to today's behavior (immediate requeue,
-- single-probe demotion) -- see DEPLOYMENT.md for the full rationale.
--
-- WOT-16: an explicit, short lock_timeout so a stuck ALTER TABLE (ACCESS EXCLUSIVE on the queue table)
-- fails fast instead of silently blocking every claim behind it.
SET lock_timeout = '5s';

-- Part 2 (WOC-10): restart-safe probe-failure streak. NULL = not currently failing.
ALTER TABLE backends
    ADD COLUMN probe_failed_since TIMESTAMPTZ;

-- Part 3 (WOC-40): earliest time a requeued job may be claimed again. NULL = immediately (today's behavior).
ALTER TABLE review_jobs
    ADD COLUMN not_before TIMESTAMPTZ;
