-- ============================================================
-- Phase 9 – Payment Capacity Constraints
-- ============================================================
-- Adds parallel BIGINT "minor-unit" columns alongside the existing
-- NUMERIC(18,4) amount columns so that DB-level CHECK constraints can use
-- exact integer arithmetic (no NUMERIC overflow edge-cases).
--
-- Minor-unit convention: amount_minor = round(amount * 10000)
-- e.g. ₹100.0000 → 1_000_000 minor units
--
-- The application layer (PaymentCapacityInvariantService) is responsible
-- for keeping minor-unit columns in sync on every save.  The DB constraints
-- act as the last line of defence against application bugs or direct SQL.
-- ============================================================

-- ── Minor-unit columns ────────────────────────────────────────────────────────

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS captured_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refunded_amount_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS disputed_amount_minor BIGINT NOT NULL DEFAULT 0;

-- ── Non-negative amounts constraint ──────────────────────────────────────────
-- All three minor-unit fields must be >= 0 at all times.

ALTER TABLE payments
    ADD CONSTRAINT chk_payment_amounts_non_negative
        CHECK (
            captured_amount_minor  >= 0 AND
            refunded_amount_minor  >= 0 AND
            disputed_amount_minor  >= 0
        );

-- ── Capacity invariant constraint ─────────────────────────────────────────────
-- refunded + disputed can never exceed what was actually captured.
-- This constraint fires when:
--  • a bug attempts to write more refunded_amount_minor than captured_amount_minor
--  • a direct-SQL update (migration, repair script, or manual DBA change) violates the invariant
--  • two concurrent transactions race past the application-level guard (belt + suspenders)

ALTER TABLE payments
    ADD CONSTRAINT chk_payment_capacity
        CHECK (
            captured_amount_minor >= refunded_amount_minor + disputed_amount_minor
        );
