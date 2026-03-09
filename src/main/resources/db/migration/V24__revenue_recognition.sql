-- Phase 10: Revenue Recognition Engine
-- Stores the daily recognition schedule for recurring subscription invoices.
-- DR SUBSCRIPTION_LIABILITY / CR REVENUE_SUBSCRIPTIONS (INCOME)

CREATE TABLE revenue_recognition_schedules (
    id                 BIGSERIAL PRIMARY KEY,
    merchant_id        BIGINT NOT NULL REFERENCES merchant_accounts(id),
    subscription_id    BIGINT NOT NULL REFERENCES subscriptions_v2(id),
    invoice_id         BIGINT NOT NULL REFERENCES invoices(id),
    recognition_date   DATE NOT NULL,
    amount             DECIMAL(18,4) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'INR',
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ledger_entry_id    BIGINT NULL REFERENCES ledger_entries(id),
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Efficient polling: find PENDING rows due on/before a given date
CREATE INDEX idx_rev_recognition_due         ON revenue_recognition_schedules(recognition_date, status);
-- Lookup all schedules for a subscription
CREATE INDEX idx_rev_recognition_subscription ON revenue_recognition_schedules(subscription_id);
-- Lookup all schedules for an invoice
CREATE INDEX idx_rev_recognition_invoice      ON revenue_recognition_schedules(invoice_id);
