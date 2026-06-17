-- ─────────────────────────────────────────────────────────────
-- V18: Audit trail of security-relevant and administrative actions.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(150) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    detail      VARCHAR(500),
    occurred_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_events_action ON audit_events(action);
