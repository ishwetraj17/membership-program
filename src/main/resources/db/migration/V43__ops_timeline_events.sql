-- V43__ops_timeline_events.sql
-- Phase 12: Unified Timeline Model
--
-- Append-only event timeline for support / ops reads.
-- Every significant domain event is projected into this table so that support
-- engineers can ask "what happened to this subscription / invoice / customer?"
-- without joining half a dozen transactional tables.
--
-- DEDUP STRATEGY (documented):
--   Each row carries the source DomainEvent id in source_event_id.
--   A unique partial index prevents the same event from creating a duplicate
--   row for the same (source_event_id, entity_type, entity_id) triple.
--   Replay therefore skips already-written rows at the DB level.  Manual
--   timeline entries (e.g. from repair actions) leave source_event_id NULL and
--   are never covered by the dedup constraint.
--
-- ORDERING:
--   Queries order by (event_time DESC, id DESC) to give newest-first output
--   with a stable secondary sort on the surrogate PK.

CREATE TABLE IF NOT EXISTS ops_timeline_events (
    id                   BIGSERIAL    PRIMARY KEY,

    -- Tenant
    merchant_id          BIGINT       NOT NULL,

    -- Entity this row describes
    entity_type          VARCHAR(64)  NOT NULL,  -- CUSTOMER, SUBSCRIPTION, INVOICE, PAYMENT_INTENT, REFUND, DISPUTE
    entity_id            BIGINT       NOT NULL,

    -- What happened
    event_type           VARCHAR(64)  NOT NULL,
    event_time           TIMESTAMP    NOT NULL,
    title                VARCHAR(255) NOT NULL,
    summary              TEXT,

    -- Optional cross-link to a related entity (e.g. the invoice that was paid)
    related_entity_type  VARCHAR(64),
    related_entity_id    BIGINT,

    -- Distributed tracing context (copied from DomainEvent)
    correlation_id       VARCHAR(128),
    causation_id         VARCHAR(128),

    -- Compact snapshot of the originating event payload (first 500 chars)
    payload_preview_json TEXT,

    -- Source domain event ID — used for dedup; NULL for manually created rows
    source_event_id      BIGINT,

    created_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ── Dedup constraint ─────────────────────────────────────────────────────────
-- One timeline row per (source_event, entity_type, entity_id).
-- NULL source_event_id is excluded so manual rows are never blocked.
CREATE UNIQUE INDEX IF NOT EXISTS idx_timeline_dedup
    ON ops_timeline_events (source_event_id, entity_type, entity_id)
    WHERE source_event_id IS NOT NULL;

-- ── Primary query path: full history for a specific entity ────────────────────
CREATE INDEX IF NOT EXISTS idx_timeline_entity
    ON ops_timeline_events (entity_type, entity_id, event_time DESC);

-- ── Correlation trace: all events sharing a correlation id ────────────────────
CREATE INDEX IF NOT EXISTS idx_timeline_correlation
    ON ops_timeline_events (merchant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- ── Event-type filter (admin dashboards) ─────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_timeline_event_type
    ON ops_timeline_events (merchant_id, event_type, event_time DESC);
