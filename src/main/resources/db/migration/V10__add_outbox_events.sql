-- ─────────────────────────────────────────────────────────────
-- V10: Transactional outbox
-- Domain events are written here in the same transaction as the
-- business change; a relay publishes PENDING rows to the broker and
-- marks them DISPATCHED (reliable, at-least-once delivery).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP    NOT NULL,
    dispatched_at  TIMESTAMP
);

-- Relay polls pending rows oldest-first.
CREATE INDEX idx_outbox_pending ON outbox_events(created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
