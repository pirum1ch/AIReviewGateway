-- Structured Review Output (docs/structured-review-output-architecture.md SRO-05/SRO-43/§7,
-- docs/structured-review-output-threat-model.md SOR-20).
--
-- Two columns, both nullable and additive, plus one CHECK -- no backfill, no change to any existing
-- constraint. Rollback-tolerant like V4: an older JAR ignores both columns (Hibernate ddl-auto:
-- validate does not fail on extra DB columns, and an older JAR never writes either column, so the
-- CHECK cannot be violated) and degrades to today's behavior.
--
-- SOR-20 (TRACKED): explicit, short lock_timeout so a stuck ALTER TABLE (ACCESS EXCLUSIVE on
-- review_results, the largest table in the schema) fails fast instead of silently blocking every
-- claim/result-submission behind it -- same WOT-16 precedent V4 already applies.
SET lock_timeout = '5s';

-- SRO-05: per-backend structured-output wire shape. NULL = use gateway.structured.default-mode.
-- Deliberately a plain VARCHAR, never bound to a JPA @Enumerated -- Backend.structuredOutputMode is
-- parsed via StructuredOutputMode.fromNullable (never Enum.valueOf), so a stale/hand-edited value
-- degrades to gateway.structured.default-mode with a WARN instead of taking the claim path down
-- (same belt-and-braces split as backends.prompt_message_format, PMR-22).
ALTER TABLE backends
    ADD COLUMN structured_output_mode VARCHAR(32);

ALTER TABLE backends
    ADD CONSTRAINT ck_backends_structured_output_mode
    CHECK (structured_output_mode IS NULL OR structured_output_mode IN
        ('OFF', 'RESPONSE_FORMAT_JSON_SCHEMA', 'RESPONSE_FORMAT_SCHEMA', 'TOP_LEVEL_JSON_SCHEMA'));
-- NOTE for a future 5th mode: the CHECK above must be relaxed (DROP + re-ADD with the new value in
-- the IN-list) before any row can be set to it -- see DEPLOYMENT.md.

-- SRO-43: llama-server's finish_reason for this chunk's completion. NULL = not reported (old Worker,
-- or a backend/llama-server build that omits the field). Whitelist-parsed on the Gateway
-- (stop/length/content_filter/tool_calls/unknown) before being stored here -- never trusted verbatim.
ALTER TABLE review_results
    ADD COLUMN finish_reason VARCHAR(32);
