-- Issue #87: die Indizes `diffs` und `profiles` (`diff_list`, `profile_list`) in der State-DB, wie
-- `schema_index_entries` aus V2 (JSONB-Spalte als Quelle der Wahrheit, extrahierte Filter-/Sortierspalten).
--
-- DDL mit `schema migrate` aus db/schema/server-state-schema.yaml erzeugt (ADR 0051).

CREATE TABLE diff_index_entries (
    tenant_id  TEXT        NOT NULL,
    diff_id    TEXT        NOT NULL,
    entry      JSONB       NOT NULL,  -- DiffIndexEntry serialized
    job_ref    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, diff_id)
);

CREATE INDEX diff_index_expiry ON diff_index_entries (expires_at);
CREATE INDEX diff_index_job_ref ON diff_index_entries (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;

CREATE TABLE profile_index_entries (
    tenant_id  TEXT        NOT NULL,
    profile_id TEXT        NOT NULL,
    entry      JSONB       NOT NULL,  -- ProfileIndexEntry serialized
    job_ref    TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, profile_id)
);

CREATE INDEX profile_index_expiry ON profile_index_entries (expires_at);
CREATE INDEX profile_index_job_ref ON profile_index_entries (tenant_id, job_ref)
    WHERE job_ref IS NOT NULL;
