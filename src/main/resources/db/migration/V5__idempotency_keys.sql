-- Idempotency keys table
-- Stores the recorded request hash and response for each idempotency key so
-- that safe-to-retry clients receive an identical response on duplicate calls.

CREATE TABLE idempotency_keys (
    key           VARCHAR(80)  PRIMARY KEY,
    request_hash  VARCHAR(128) NOT NULL,
    response_body TEXT,
    status_code   INT,
    owner         VARCHAR(255),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP    NOT NULL
);

-- Fast expiry-based cleanup scan
CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);
