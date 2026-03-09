-- Phase 20: Production Hardening — Feature Flags and Scheduler Coordination

CREATE TABLE feature_flags (
    flag_key    VARCHAR(128) PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    scope       VARCHAR(32)  NOT NULL DEFAULT 'GLOBAL',
    merchant_id BIGINT       NULL REFERENCES merchant_accounts(id),
    config_json TEXT         NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE job_locks (
    job_name     VARCHAR(128) PRIMARY KEY,
    locked_until TIMESTAMP    NULL,
    locked_by    VARCHAR(128) NULL,
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feature_flags_scope_merchant ON feature_flags(scope, merchant_id);
CREATE INDEX idx_job_locks_locked_until        ON job_locks(locked_until);
