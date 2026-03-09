-- =============================================================================
-- V22 — Billing Engine Upgrade: invoice sequences, discounts, discount
--        redemptions, and invoice total breakdown columns
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. invoice_sequences  (per-merchant sequential invoice numbering)
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_sequences (
    merchant_id    BIGINT        PRIMARY KEY REFERENCES merchant_accounts(id),
    current_number BIGINT        NOT NULL DEFAULT 0,
    prefix         VARCHAR(32)   NOT NULL,
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- 2. discounts
-- ---------------------------------------------------------------------------
CREATE TABLE discounts (
    id                 BIGSERIAL     PRIMARY KEY,
    merchant_id        BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    code               VARCHAR(64)   NOT NULL,
    discount_type      VARCHAR(16)   NOT NULL,   -- FIXED | PERCENTAGE
    value              DECIMAL(18,4) NOT NULL,
    currency           VARCHAR(10)   NULL,        -- required for FIXED discounts
    max_redemptions    INT           NULL,        -- NULL = unlimited
    per_customer_limit INT           NULL,        -- NULL = unlimited per customer
    valid_from         TIMESTAMP     NOT NULL,
    valid_to           TIMESTAMP     NOT NULL,
    status             VARCHAR(32)   NOT NULL,    -- ACTIVE | INACTIVE | EXPIRED
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_discount_merchant_code UNIQUE (merchant_id, code)
);

-- ---------------------------------------------------------------------------
-- 3. discount_redemptions
-- ---------------------------------------------------------------------------
CREATE TABLE discount_redemptions (
    id           BIGSERIAL  PRIMARY KEY,
    discount_id  BIGINT     NOT NULL REFERENCES discounts(id),
    customer_id  BIGINT     NOT NULL REFERENCES customers(id),
    invoice_id   BIGINT     NOT NULL REFERENCES invoices(id),
    redeemed_at  TIMESTAMP  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_discount_redemptions_discount_customer ON discount_redemptions(discount_id, customer_id);

-- ---------------------------------------------------------------------------
-- 4. Alter invoices — add merchant scope + invoice number + total breakdown
-- ---------------------------------------------------------------------------
ALTER TABLE invoices
    ADD COLUMN merchant_id    BIGINT        NULL REFERENCES merchant_accounts(id),
    ADD COLUMN invoice_number VARCHAR(64)   NULL,
    ADD COLUMN subtotal       DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN discount_total DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN credit_total   DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN tax_total      DECIMAL(18,4) NOT NULL DEFAULT 0,
    ADD COLUMN grand_total    DECIMAL(18,4) NOT NULL DEFAULT 0;

-- Partial unique index: only enforce uniqueness when both merchant_id and
-- invoice_number are non-null (existing legacy invoices have neither).
CREATE UNIQUE INDEX idx_invoices_merchant_invoice_number
    ON invoices(merchant_id, invoice_number)
    WHERE merchant_id IS NOT NULL AND invoice_number IS NOT NULL;
