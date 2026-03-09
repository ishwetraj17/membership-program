-- V14: Immutable domain event log (append-only; no UPDATE or DELETE)
CREATE TABLE domain_events (
    id          BIGSERIAL     PRIMARY KEY,
    event_type  VARCHAR(64)   NOT NULL,
    payload     TEXT          NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_domain_events_type       ON domain_events (event_type);
CREATE INDEX idx_domain_events_created_at ON domain_events (created_at);
