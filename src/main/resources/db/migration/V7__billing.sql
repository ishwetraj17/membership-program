-- =============================================================================
-- V7 — Billing module: invoices, invoice_lines, credit_notes
-- Note: user requested V4 but V4-V6 are already occupied.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. invoices
-- ---------------------------------------------------------------------------
CREATE TABLE invoices (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    subscription_id BIGINT          NULL     REFERENCES subscriptions(id),
    status          VARCHAR(32)     NOT NULL,   -- DRAFT | OPEN | PAID | VOID | UNCOLLECTIBLE
    currency        VARCHAR(10)     NOT NULL DEFAULT 'INR',
    total_amount    DECIMAL(10,2)   NOT NULL,
    due_date        TIMESTAMP       NOT NULL,
    period_start    TIMESTAMP       NULL,
    period_end      TIMESTAMP       NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoices_user_id         ON invoices(user_id);
CREATE INDEX idx_invoices_subscription_id ON invoices(subscription_id);
CREATE INDEX idx_invoices_status          ON invoices(status);

-- ---------------------------------------------------------------------------
-- 2. invoice_lines
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_lines (
    id          BIGSERIAL       PRIMARY KEY,
    invoice_id  BIGINT          NOT NULL REFERENCES invoices(id),
    line_type   VARCHAR(32)     NOT NULL, -- PLAN_CHARGE | PRORATION | TAX | DISCOUNT | CREDIT_APPLIED
    description VARCHAR(255)    NOT NULL,
    amount      DECIMAL(10,2)   NOT NULL
);

CREATE INDEX idx_invoice_lines_invoice_id ON invoice_lines(invoice_id);

-- ---------------------------------------------------------------------------
-- 3. credit_notes  (customer wallet / balance credits)
-- ---------------------------------------------------------------------------
CREATE TABLE credit_notes (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES users(id),
    currency    VARCHAR(10)     NOT NULL DEFAULT 'INR',
    amount      DECIMAL(10,2)   NOT NULL,
    reason      VARCHAR(255)    NOT NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    used_amount DECIMAL(10,2)   NOT NULL DEFAULT 0
);

CREATE INDEX idx_credit_notes_user_id ON credit_notes(user_id);
