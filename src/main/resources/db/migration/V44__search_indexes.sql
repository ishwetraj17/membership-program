-- V44: Search indexes for unified admin search (Phase 13)
--
-- Adds B-tree indexes that enable support and ops to locate any entity by
-- invoice number, gateway transaction ID, refund reference, customer email,
-- correlation ID, and related field identifiers.  All indexes are
-- CREATE INDEX IF NOT EXISTS so re-running the script is safe.
--
-- Tenant isolation: the queries driving these indexes always include a
-- merchant_id predicate; consider extending these to partial/composite
-- indexes in the future if table sizes exceed 100 M rows.

-- ── Invoices ──────────────────────────────────────────────────────────────
-- Enables fast lookup by human-readable invoice number (e.g. INV-2024-000001)
CREATE INDEX IF NOT EXISTS idx_invoices_invoice_number
    ON invoices (invoice_number);

-- Composite index for merchant-scoped invoice-number search (most common admin pattern)
CREATE INDEX IF NOT EXISTS idx_invoices_merchant_invoice_number
    ON invoices (merchant_id, invoice_number);

-- ── Payments ──────────────────────────────────────────────────────────────
-- Enables gateway transaction ID lookup (already unique, but add merchant composite)
CREATE INDEX IF NOT EXISTS idx_payments_gateway_txn_id
    ON payments (gateway_txn_id);

CREATE INDEX IF NOT EXISTS idx_payments_merchant_gateway_txn_id
    ON payments (merchant_id, gateway_txn_id);

-- ── Refunds V2 ────────────────────────────────────────────────────────────
-- Enables lookup by refund reference (e.g. gateway-assigned refund IDs)
CREATE INDEX IF NOT EXISTS idx_refunds_v2_refund_reference
    ON refunds_v2 (refund_reference)
    WHERE refund_reference IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refunds_v2_merchant_refund_reference
    ON refunds_v2 (merchant_id, refund_reference)
    WHERE refund_reference IS NOT NULL;

-- ── Customers ─────────────────────────────────────────────────────────────
-- Enables case-insensitive email search (unique constraint exists on merchant+email,
-- but an explicit functional index on lower(email) speeds ILIKE lookups)
CREATE INDEX IF NOT EXISTS idx_customers_email_lower
    ON customers (lower(email));

CREATE INDEX IF NOT EXISTS idx_customers_merchant_email_lower
    ON customers (merchant_id, lower(email));

-- ── Domain Events ─────────────────────────────────────────────────────────
-- Enables correlation-ID-based event fan-out lookups (critical for incident tracing)
CREATE INDEX IF NOT EXISTS idx_domain_events_correlation_id
    ON domain_events (correlation_id)
    WHERE correlation_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_domain_events_merchant_correlation_id
    ON domain_events (merchant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Enables event-type filtering combined with merchant lookup
CREATE INDEX IF NOT EXISTS idx_domain_events_merchant_event_type
    ON domain_events (merchant_id, event_type);

-- ── Payment Intents V2 ────────────────────────────────────────────────────
-- Enables search by invoice ID cross-referenced to payment intents
CREATE INDEX IF NOT EXISTS idx_payment_intents_v2_invoice_id
    ON payment_intents_v2 (invoice_id)
    WHERE invoice_id IS NOT NULL;

-- Enables search by subscription ID cross-referenced to payment intents
CREATE INDEX IF NOT EXISTS idx_payment_intents_v2_subscription_id
    ON payment_intents_v2 (subscription_id)
    WHERE subscription_id IS NOT NULL;

-- ── Subscriptions V2 ──────────────────────────────────────────────────────
-- Composite index for merchant+customer subscription lookups (supports search by email → customerId → subscriptions)
CREATE INDEX IF NOT EXISTS idx_subscriptions_v2_merchant_customer
    ON subscriptions_v2 (merchant_id, customer_id);
