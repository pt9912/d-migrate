-- ImpPlan-1.2.0-mcp-server-state-schema-artifact-persistence.md AP1.
--
-- Additive companion to V1__server_state_initial.sql: adds durable
-- storage for the schema/artifact catalog (previously In-Memory-only
-- even with --server-state, see the ImpPlan's "Kontext / Ist-Stand").
-- Same conventions as V1 (JSONB Source-of-Truth column + extracted
-- filter/sort columns, Postgres-only).
--
-- DDL generated (not hand-written) via d-migrate's own `schema migrate`
-- diff engine, per ADR 0051 -- source of truth is
-- db/schema/server-state-schema.yaml (cumulative desired state). Verified
-- that `schema generate` renders V1's existing tables byte-for-byte
-- (matches module names/types/indexes, differs only cosmetically in
-- identifier quoting) before trusting the delta for these two tables.

-- ============================================================
-- SchemaStore
-- ============================================================

CREATE TABLE schema_index_entries (
    tenant_id  TEXT        NOT NULL,
    schema_id  TEXT        NOT NULL,
    entry      JSONB       NOT NULL,  -- SchemaIndexEntry serialized
    job_ref    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, schema_id)
);

CREATE INDEX schema_index_expiry ON schema_index_entries (expires_at);
CREATE INDEX schema_index_job_ref ON schema_index_entries (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;

-- ============================================================
-- ArtifactStore
-- ============================================================

CREATE TABLE artifact_records (
    tenant_id          TEXT        NOT NULL,
    artifact_id        TEXT        NOT NULL,
    record             JSONB       NOT NULL,  -- ArtifactRecord serialized
    kind               TEXT        NOT NULL,
    owner_principal_id TEXT        NOT NULL,
    job_ref            TEXT,
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, artifact_id)
);

CREATE INDEX artifact_records_expiry ON artifact_records (expires_at);
CREATE INDEX artifact_records_owner ON artifact_records (tenant_id, owner_principal_id);
CREATE INDEX artifact_records_kind ON artifact_records (tenant_id, kind);
CREATE INDEX artifact_records_job_ref ON artifact_records (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;
