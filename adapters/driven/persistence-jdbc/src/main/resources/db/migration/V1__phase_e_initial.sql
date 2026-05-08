-- Phase E2 initial migration for the d-migrate Server-State-DB.
-- Plan: docs/planning/done/ImpPlan-0.9.6-E2.md § 4 (V1__phase_e_initial.sql).
--
-- Targets PostgreSQL >= 14 (technical minimum; PG 16+ recommended for new
-- deployments — see plan § 3.1). Uses JSONB, TIMESTAMPTZ, and partial
-- indexes; do NOT port verbatim to other dialects without dialect-specific
-- review.
--
-- The Server-State-DB is isolated from the migration-target databases the
-- d-migrate driver-* adapters operate on (plan § 3.2 + § 3.6). It belongs
-- exclusively to the Phase-E control plane.

-- ============================================================
-- 4.1 IdempotencyStore — regular path
-- ============================================================

CREATE TABLE idempotency_reservations (
    tenant_id           TEXT        NOT NULL,
    caller_id           TEXT        NOT NULL,
    tool_name           TEXT        NOT NULL,
    idempotency_key     TEXT        NOT NULL,
    state               TEXT        NOT NULL,                 -- PENDING|AWAITING_APPROVAL|COMMITTED|DENIED|FAILED
    claimed             BOOLEAN     NOT NULL DEFAULT FALSE,   -- claimApproved winner marker (plan § 6.4)
    payload_fingerprint TEXT        NOT NULL,
    result_ref          TEXT,
    challenge           JSONB,                                -- ApprovalChallenge serialized (plan § 6.3)
    reason              TEXT,
    expires_at          TIMESTAMPTZ NOT NULL,                 -- lease for non-terminal, outcome expiry for terminal
    retention_until     TIMESTAMPTZ NOT NULL,                 -- terminal-state retention; equals expires_at once terminal
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, caller_id, tool_name, idempotency_key)
);

CREATE INDEX idempotency_expiry ON idempotency_reservations (retention_until);

-- ============================================================
-- 4.1.bis IdempotencyStore — InitResume path (Phase-C upload-init)
-- ============================================================
-- Separate table from idempotency_reservations because InitResumeScope
-- (clientRequestId) and IdempotencyScope (idempotencyKey) are distinct
-- identity tuples. Polymorph in one table would be fragile (plan § 4.1.bis).

CREATE TABLE init_resume_reservations (
    tenant_id           TEXT        NOT NULL,
    caller_id           TEXT        NOT NULL,
    tool_name           TEXT        NOT NULL,
    client_request_id   TEXT        NOT NULL,
    session_id          TEXT        NOT NULL,
    payload_fingerprint TEXT        NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, caller_id, tool_name, client_request_id)
);

CREATE INDEX init_resume_expiry ON init_resume_reservations (expires_at);

-- ============================================================
-- 4.2 JobStore
-- ============================================================

CREATE TABLE jobs (
    tenant_id        TEXT        NOT NULL,
    job_id           TEXT        NOT NULL,
    status           TEXT        NOT NULL,                  -- QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED
    managed_job      JSONB       NOT NULL,                  -- ManagedJob serialized
    cancel_requested BOOLEAN     NOT NULL DEFAULT FALSE,
    cancel_source    TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, job_id)
);

CREATE INDEX jobs_expiry ON jobs (expires_at);
CREATE INDEX jobs_status ON jobs (tenant_id, status);

-- ============================================================
-- 4.3 QuotaReservationOwner
-- ============================================================

CREATE TABLE quota_reservation_owners (
    owner_id         TEXT        NOT NULL PRIMARY KEY,
    reservation      JSONB       NOT NULL,                  -- QuotaReservation serialized
    state            TEXT        NOT NULL,                  -- PENDING|COMMITTED|RELEASED|REFUNDED
    lease_expires_at TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- Partial index for the sweeper hot path: only PENDING owners need
-- expiry scanning. Plan § 4.3.
CREATE INDEX quota_owners_expiry
    ON quota_reservation_owners (lease_expires_at)
    WHERE state = 'PENDING';

-- ============================================================
-- 4.4 QuotaCounter
-- ============================================================
-- Limits live in QuotaConfig (passed in at reserve time), NOT in this
-- table — limit changes do not require DDL (plan § 4.4 + § 6.8).

CREATE TABLE quota_counters (
    quota_key  TEXT        NOT NULL PRIMARY KEY,            -- serialized QuotaKey
    used       BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
