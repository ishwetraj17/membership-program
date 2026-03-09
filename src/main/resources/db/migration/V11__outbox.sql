-- =============================================================================
-- V11 — Transactional Outbox
--
-- outbox_events holds domain events written atomically alongside business
-- changes.  A background poller reads NEW rows, dispatches in-process
-- handlers, and marks rows DONE (or FAILED after max retries → DLQ).
-- =============================================================================

CREATE TABLE outbox_events (
    id              BIGSERIAL       PRIMARY KEY,
    event_type      VARCHAR(64)     NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'NEW',
    attempts        INT             NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP       NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    last_error      TEXT            NULL,

    CONSTRAINT ck_outbox_status CHECK (status IN ('NEW', 'PROCESSING', 'DONE', 'FAILED'))
);

-- index used by the poller: WHERE status = 'NEW' AND next_attempt_at <= NOW()
CREATE INDEX idx_outbox_poll ON outbox_events (status, next_attempt_at)
    WHERE status = 'NEW';

-- useful for debugging / monitoring by event type
CREATE INDEX idx_outbox_event_type ON outbox_events (event_type);
