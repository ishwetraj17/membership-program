-- Phase 4: Idempotency hardening
-- Adds status lifecycle tracking, checkpoint table, and new columns.
-- The existing composite-string PK on idempotency_keys is retained for backward compatibility.

-- ── Extend idempotency_keys ───────────────────────────────────────────────────

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS idempotency_key        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status                 VARCHAR(32)  NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN IF NOT EXISTS processing_started_at  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS completed_at           TIMESTAMP,
    ADD COLUMN IF NOT EXISTS request_id             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS correlation_id         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Backfill raw idempotency_key from composite PK (everything after the first ':').
UPDATE idempotency_keys
    SET idempotency_key = SUBSTRING(key, POSITION(':' IN key) + 1)
WHERE idempotency_key IS NULL
  AND POSITION(':' IN key) > 0;

-- Rows without a ':' (pre-merchant-scoping era) keep the whole key as-is.
UPDATE idempotency_keys
    SET idempotency_key = key
WHERE idempotency_key IS NULL;

-- Already-processed rows: sync completed_at from created_at.
UPDATE idempotency_keys
    SET completed_at = created_at
WHERE response_body IS NOT NULL;

-- Orphaned in-flight rows: downgrade to PROCESSING.
UPDATE idempotency_keys
    SET status                = 'PROCESSING',
        processing_started_at = created_at
WHERE response_body IS NULL
  AND expires_at > CURRENT_TIMESTAMP
  AND status = 'COMPLETED';

-- Index for the new canonical (merchantId, rawKey) lookup path.
CREATE INDEX IF NOT EXISTS idx_idem_merchant_key
    ON idempotency_keys (merchant_id, idempotency_key);

-- Index to support stuck-PROCESSING cleanup (no partial-index syntax for H2 compat).
CREATE INDEX IF NOT EXISTS idx_idem_status_started
    ON idempotency_keys (status, processing_started_at);

-- ── idempotency_checkpoints (new) ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS idempotency_checkpoints (
    id              BIGSERIAL     PRIMARY KEY,
    merchant_id     VARCHAR(255)  NOT NULL,
    idempotency_key VARCHAR(255)  NOT NULL,
    operation_type  VARCHAR(128)  NOT NULL,
    step_name       VARCHAR(255)  NOT NULL,
    step_status     VARCHAR(32)   NOT NULL,
    resource_type   VARCHAR(128),
    resource_id     BIGINT,
    payload_json    TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_idem_chk_merchant_key
    ON idempotency_checkpoints (merchant_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_idem_chk_operation
    ON idempotency_checkpoints (merchant_id, idempotency_key, operation_type);
