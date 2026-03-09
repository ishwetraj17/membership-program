-- V40: Repair Actions Audit Trail
-- Stores an immutable record of every repair action execution for compliance
-- and operational accountability. Once written, rows are never updated.

CREATE TABLE IF NOT EXISTS repair_actions_audit (
    id                    BIGSERIAL    PRIMARY KEY,
    repair_key            VARCHAR(120) NOT NULL,
    target_type           VARCHAR(80)  NOT NULL,
    target_id             VARCHAR(255),
    actor_user_id         BIGINT,
    before_snapshot_json  TEXT,
    after_snapshot_json   TEXT,
    reason                VARCHAR(500),
    status                VARCHAR(20)  NOT NULL DEFAULT 'EXECUTED',
    dry_run               BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_repair_audit_repair_key   ON repair_actions_audit (repair_key);
CREATE INDEX idx_repair_audit_target       ON repair_actions_audit (target_type, target_id);
CREATE INDEX idx_repair_audit_actor        ON repair_actions_audit (actor_user_id);
CREATE INDEX idx_repair_audit_created_at   ON repair_actions_audit (created_at DESC);
