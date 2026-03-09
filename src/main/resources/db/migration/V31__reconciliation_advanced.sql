-- Phase 17: Reconciliation 2.0
-- Batch-level, three-way, and statement-import reconciliation

-- -----------------------------------------------
-- settlement_batches: per-merchant gateway batches
-- -----------------------------------------------
CREATE TABLE settlement_batches (
    id             BIGSERIAL PRIMARY KEY,
    merchant_id    BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    batch_date     DATE          NOT NULL,
    gateway_name   VARCHAR(64)   NOT NULL,
    gross_amount   DECIMAL(18,4) NOT NULL DEFAULT 0,
    fee_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
    reserve_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
    currency       VARCHAR(10)   NOT NULL DEFAULT 'INR',
    status         VARCHAR(32)   NOT NULL DEFAULT 'CREATED',
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------
-- settlement_batch_items: one row per payment in batch
-- -----------------------------------------------
CREATE TABLE settlement_batch_items (
    id             BIGSERIAL PRIMARY KEY,
    batch_id       BIGINT        NOT NULL REFERENCES settlement_batches(id),
    payment_id     BIGINT        NOT NULL REFERENCES payments(id),
    amount         DECIMAL(18,4) NOT NULL DEFAULT 0,
    fee_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
    reserve_amount DECIMAL(18,4) NOT NULL DEFAULT 0,
    net_amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------
-- external_statement_imports: gateway/bank CSV imports
-- -----------------------------------------------
CREATE TABLE external_statement_imports (
    id             BIGSERIAL PRIMARY KEY,
    merchant_id    BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    source_type    VARCHAR(32)   NOT NULL,
    statement_date DATE          NOT NULL,
    file_name      VARCHAR(255)  NOT NULL,
    status         VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    row_count      INT           NOT NULL DEFAULT 0,
    total_amount   DECIMAL(18,4) NOT NULL DEFAULT 0,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------
-- Extend recon_mismatches with lifecycle fields
-- -----------------------------------------------
ALTER TABLE recon_mismatches
    ADD COLUMN status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN owner_user_id   BIGINT      NULL REFERENCES users(id),
    ADD COLUMN resolution_note TEXT        NULL;

-- -----------------------------------------------
-- Indexes
-- -----------------------------------------------
CREATE INDEX idx_settlement_batches_merchant_date  ON settlement_batches(merchant_id, batch_date);
CREATE INDEX idx_settlement_batch_items_batch_id   ON settlement_batch_items(batch_id);
CREATE INDEX idx_statement_imports_merchant_date   ON external_statement_imports(merchant_id, statement_date);
CREATE INDEX idx_recon_mismatch_status             ON recon_mismatches(status);
