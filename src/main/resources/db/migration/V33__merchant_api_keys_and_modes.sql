-- Phase 19: Merchant API Keys, Access Scopes, and Sandbox/Live Modes

CREATE TABLE merchant_api_keys (
    id           BIGSERIAL    PRIMARY KEY,
    merchant_id  BIGINT       NOT NULL REFERENCES merchant_accounts(id),
    key_prefix   VARCHAR(32)  NOT NULL,
    key_hash     VARCHAR(255) NOT NULL,
    mode         VARCHAR(16)  NOT NULL,
    scopes_json  TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    last_used_at TIMESTAMP    NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE merchant_modes (
    merchant_id      BIGINT      PRIMARY KEY REFERENCES merchant_accounts(id),
    sandbox_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    live_enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    default_mode     VARCHAR(16) NOT NULL,
    updated_at       TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchant_api_keys_merchant_status ON merchant_api_keys(merchant_id, status);
CREATE INDEX idx_merchant_api_keys_prefix          ON merchant_api_keys(key_prefix);
