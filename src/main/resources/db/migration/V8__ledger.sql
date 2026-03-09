-- =============================================================
-- V8 — Double-entry ledger + refunds
-- =============================================================

-- ledger_accounts: chart-of-accounts registry
CREATE TABLE ledger_accounts (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,
    account_type VARCHAR(16)  NOT NULL,
    currency     VARCHAR(10)  NOT NULL DEFAULT 'INR',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ledger_account_name UNIQUE (name),
    CONSTRAINT ck_ledger_account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE'))
);

-- ledger_entries: journal entries (one per business event)
CREATE TABLE ledger_entries (
    id             BIGSERIAL   PRIMARY KEY,
    entry_type     VARCHAR(64) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id   BIGINT      NOT NULL,
    currency       VARCHAR(10) NOT NULL DEFAULT 'INR',
    created_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    metadata       TEXT        NULL,
    CONSTRAINT ck_ledger_entry_type CHECK (
        entry_type IN ('PAYMENT_CAPTURED', 'REFUND_ISSUED', 'REVENUE_RECOGNIZED', 'SETTLEMENT')
    ),
    CONSTRAINT ck_ledger_ref_type CHECK (
        reference_type IN ('INVOICE', 'PAYMENT', 'REFUND', 'SUBSCRIPTION', 'SETTLEMENT_BATCH')
    )
);

CREATE INDEX idx_ledger_entries_ref ON ledger_entries (reference_type, reference_id);

-- ledger_lines: individual debit/credit legs of a journal entry
CREATE TABLE ledger_lines (
    id         BIGSERIAL     PRIMARY KEY,
    entry_id   BIGINT        NOT NULL REFERENCES ledger_entries (id),
    account_id BIGINT        NOT NULL REFERENCES ledger_accounts (id),
    direction  VARCHAR(6)    NOT NULL,
    amount     DECIMAL(10,2) NOT NULL,
    CONSTRAINT ck_ledger_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_line_amount    CHECK (amount > 0)
);

CREATE INDEX idx_ledger_lines_entry_id   ON ledger_lines (entry_id);
CREATE INDEX idx_ledger_lines_account_id ON ledger_lines (account_id);

-- refunds: records of money returned to the customer
CREATE TABLE refunds (
    id         BIGSERIAL     PRIMARY KEY,
    payment_id BIGINT        NOT NULL REFERENCES payments (id),
    amount     DECIMAL(10,2) NOT NULL,
    currency   VARCHAR(10)   NOT NULL DEFAULT 'INR',
    reason     TEXT          NULL,
    status     VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    created_at TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_refund_amount CHECK (amount > 0)
);

CREATE INDEX idx_refunds_payment_id ON refunds (payment_id);
