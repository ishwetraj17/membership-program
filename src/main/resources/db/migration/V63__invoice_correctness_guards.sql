-- ─────────────────────────────────────────────────────────────────────────────
-- Phase 17 : Invoice correctness guards, credit carry-forward, billing guards
-- ─────────────────────────────────────────────────────────────────────────────
-- NOTE: The spec requested V48 but V48 is already taken by
--       V48__webhook_delivery_hardening.sql so we use V63 (next free slot).

-- ── invoices ─────────────────────────────────────────────────────────────────
-- Tracks the actual credit (in minor currency units / paise) applied at the
-- point of billing, independent of the sum of CREDIT_APPLIED lines.
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS effective_credit_applied_minor BIGINT NOT NULL DEFAULT 0;

-- Links a rebuilt / corrected invoice back to its original source invoice.
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS source_invoice_id BIGINT NULL;

-- Audit metadata stamped when the rebuild-totals action is executed.
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS rebuilt_at TIMESTAMP NULL;

ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS rebuilt_by VARCHAR(255) NULL;

-- ── credit_notes ─────────────────────────────────────────────────────────────
-- Merchant-facing customer id for cross-merchant credit resolution.
-- user_id remains the primary FK; customer_id is a denormalised lookup helper.
ALTER TABLE credit_notes
    ADD COLUMN IF NOT EXISTS customer_id BIGINT NULL;

-- Available balance in minor currency units (paise) — kept in sync whenever the
-- credit note is consumed or carry-forward notes are created.
ALTER TABLE credit_notes
    ADD COLUMN IF NOT EXISTS available_amount_minor BIGINT NOT NULL DEFAULT 0;

-- Tracks which invoice triggered creation of this credit note
-- (e.g. a carry-forward note created as overflow from invoice processing).
ALTER TABLE credit_notes
    ADD COLUMN IF NOT EXISTS source_invoice_id BIGINT NULL;

-- Optional expiry: credit notes not used by this timestamp cannot be applied.
ALTER TABLE credit_notes
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NULL;

-- Partial index to quickly find non-expired credit notes when applying.
CREATE INDEX IF NOT EXISTS idx_credit_notes_user_expires
    ON credit_notes (user_id, expires_at)
    WHERE expires_at IS NULL OR expires_at > NOW();
