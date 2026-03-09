-- ============================================================================
-- V50 : Platform contracts foundation — audit_entries
-- ============================================================================
-- Purpose
-- -------
-- Introduces the hardened audit_entries table that will anchor all new
-- platform write-path events from Phase 1 onwards.
--
-- The legacy audit_logs table (V4) is left untouched so that the existing
-- application code that writes to it continues to function without change.
-- Over subsequent phases, call-sites will be migrated to audit_entries
-- and a future migration will drop audit_logs once it is no longer referenced.
--
-- Design decisions
-- ----------------
-- * All monetary amounts stored as BIGINT minor units (paise, cents, etc.)
--   consistent with the Money value object contract.
-- * request_id and correlation_id are VARCHAR(64) to accommodate UUIDs (36
--   chars) plus provider-issued trace IDs, which may be longer.
-- * actor_id is VARCHAR(120) to accommodate email addresses, JWT subjects,
--   and service account identifiers.
-- * ip_address is VARCHAR(45) to accommodate both IPv4 and IPv6 (max 39).
-- * metadata stored as JSONB for flexible, indexable structured payloads.
-- * occurred_at defaults to NOW() at the DB level so no application clock
--   drift can alter the audit trail; it is also the primary query predicate.
-- * No soft-delete. Audit rows are immutable once written.
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_entries (
    id              BIGSERIAL       NOT NULL,

    -- Tracing / request identity (populated from X-Request-Id / X-Correlation-Id headers)
    request_id      VARCHAR(64),
    correlation_id  VARCHAR(64),

    -- Tenant and actor context
    merchant_id     BIGINT,
    actor_id        VARCHAR(120),
    api_version     VARCHAR(20),

    -- What happened
    action          VARCHAR(80)     NOT NULL,
    entity_type     VARCHAR(80)     NOT NULL,
    entity_id       BIGINT,

    -- Optional monetary context (minor units; NULL when not applicable)
    amount_minor    BIGINT,
    currency_code   VARCHAR(10),

    -- Arbitrary structured payload (searchable in Postgres via @> / ->> operators)
    metadata        JSONB,

    -- Network context
    ip_address      VARCHAR(45),

    -- Immutable wall-clock timestamp (set at write time; indexed DESC for recency queries)
    occurred_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT audit_entries_pkey PRIMARY KEY (id)
);

-- ── Indexes ──────────────────────────────────────────────────────────────────

-- Most frequent query pattern: "show me all events for merchant X, newest first"
CREATE INDEX IF NOT EXISTS idx_audit_entries_merchant_time
    ON audit_entries (merchant_id, occurred_at DESC);

-- Entity-scoped queries: "show me all events for Invoice #42"
CREATE INDEX IF NOT EXISTS idx_audit_entries_entity
    ON audit_entries (entity_type, entity_id);

-- Trace look-up: reproduce everything that happened in a single request
CREATE INDEX IF NOT EXISTS idx_audit_entries_request_id
    ON audit_entries (request_id);

-- Cross-request correlation look-up
CREATE INDEX IF NOT EXISTS idx_audit_entries_correlation_id
    ON audit_entries (correlation_id);

-- Action-scoped queries: "how many SUBSCRIPTION_CANCELLED events this week?"
CREATE INDEX IF NOT EXISTS idx_audit_entries_action
    ON audit_entries (action);

-- JSONB metadata GIN index — enables @> and jsonpath queries on metadata
CREATE INDEX IF NOT EXISTS idx_audit_entries_metadata_gin
    ON audit_entries USING GIN (metadata);

-- ── Table-level documentation ─────────────────────────────────────────────────

COMMENT ON TABLE  audit_entries              IS 'Hardened, immutable audit log introduced in V50. Replaces audit_logs going forward. Rows are never updated or deleted.';
COMMENT ON COLUMN audit_entries.request_id   IS 'Value of the X-Request-Id header; identifies a single HTTP request.';
COMMENT ON COLUMN audit_entries.correlation_id IS 'Value of the X-Correlation-Id header; groups events for a logical business flow.';
COMMENT ON COLUMN audit_entries.merchant_id  IS 'Tenant scoping key; NULL for platform-admin operations.';
COMMENT ON COLUMN audit_entries.actor_id     IS 'JWT subject, email, or service-account identifier of the authenticated actor.';
COMMENT ON COLUMN audit_entries.api_version  IS 'Value of the X-API-Version header at the time of the request.';
COMMENT ON COLUMN audit_entries.action       IS 'Snake_case verb describing what happened, e.g. subscription_cancelled.';
COMMENT ON COLUMN audit_entries.entity_type  IS 'Domain entity class name, e.g. Subscription, Invoice, PaymentIntent.';
COMMENT ON COLUMN audit_entries.entity_id    IS 'Primary key of the affected entity row.';
COMMENT ON COLUMN audit_entries.amount_minor IS 'Monetary context in minor units (paise/cents). NULL when not applicable.';
COMMENT ON COLUMN audit_entries.currency_code IS 'ISO 4217 currency code corresponding to amount_minor.';
COMMENT ON COLUMN audit_entries.metadata     IS 'JSONB bag of structured key-value pairs relevant to the event.';
COMMENT ON COLUMN audit_entries.ip_address   IS 'Client IP address extracted from the request (IPv4 or IPv6).';
COMMENT ON COLUMN audit_entries.occurred_at  IS 'Wall-clock timestamp at which the event was recorded. Set by the database; never overridden by application code.';
