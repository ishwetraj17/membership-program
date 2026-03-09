-- V12__risk.sql
-- Risk control tables: risk event log and IP blocklist

CREATE TABLE risk_events (
    id         BIGSERIAL    PRIMARY KEY,
    type       VARCHAR(64)  NOT NULL,
    severity   VARCHAR(16)  NOT NULL,
    user_id    BIGINT       NULL,
    ip         VARCHAR(64)  NOT NULL,
    device_id  VARCHAR(255) NULL,
    metadata   TEXT         NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_risk_events_user_created ON risk_events (user_id, created_at);
CREATE INDEX idx_risk_events_ip           ON risk_events (ip);
CREATE INDEX idx_risk_events_type         ON risk_events (type);

CREATE TABLE ip_blocklist (
    ip         VARCHAR(64)  PRIMARY KEY,
    reason     VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
