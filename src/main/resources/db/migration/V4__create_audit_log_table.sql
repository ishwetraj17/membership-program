-- V4: Create audit_logs table
-- General-purpose audit / event log that complements the domain-specific
-- subscription_history table.  Stores authentication events, admin actions,
-- and subscription lifecycle notifications delivered via Spring ApplicationEvent.

CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGSERIAL    PRIMARY KEY,
    action      VARCHAR(60)  NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   BIGINT,
    user_id     BIGINT,
    description VARCHAR(500),
    metadata    VARCHAR(2000),
    request_id  VARCHAR(64),
    occurred_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id     ON audit_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity      ON audit_logs (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred_at ON audit_logs (occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_action      ON audit_logs (action);
