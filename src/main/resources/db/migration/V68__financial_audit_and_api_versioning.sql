-- ============================================================================
-- V68 : Financial audit hardening + merchant API version pinning
-- ============================================================================
--
-- NOTE: The task specification requested this as "V53" but V53 is already
--       occupied by V53__scheduler_execution_tracking.sql.  V68 is the next
--       available slot after V67.
--
-- Section 1 — Extend audit_entries (originally created in V50)
--   Adds the compliance-grade columns needed by FinancialAuditAspect:
--     operation_type  – machine-readable constant that drove the mutation
--     performed_by    – actor string (user, service, job)
--     success         – whether the operation completed without error
--     failure_reason  – trimmed exception message / error code on failure
--
-- Section 2 — Create merchant_api_versions
--   Stores the API version each merchant has explicitly pinned so that calls
--   without an X-API-Version header are served on the pinned contract rather
--   than the platform DEFAULT.
-- ============================================================================

-- ============================================================================
-- SECTION 1 — audit_entries column additions
-- ============================================================================

-- operation_type: machine-readable constant (e.g. SUBSCRIPTION_CREATE,
--   PAYMENT_CONFIRM). Complements the free-text 'action' column already
--   present from V50. VARCHAR(80) matches the existing action column length.
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS operation_type   VARCHAR(80);

-- performed_by: identity string for the actor; duplicates actor_id for
--   queries that filter by issuer without knowing whether the actor is a user
--   (numeric id) or a service key (string). Allows free-form source name.
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS performed_by     VARCHAR(120);

-- success: was the operation committed successfully?
--   DEFAULT TRUE preserves backward compatibility for rows written before V68.
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS success          BOOLEAN NOT NULL DEFAULT TRUE;

-- failure_reason: short description of why the operation failed. NULL when
--   success = TRUE.  TEXT allows arbitrary length exception messages.
ALTER TABLE audit_entries
    ADD COLUMN IF NOT EXISTS failure_reason   TEXT;

-- Index: support queries for failed financial operations per merchant
CREATE INDEX IF NOT EXISTS idx_audit_entries_failed
    ON audit_entries (merchant_id, occurred_at DESC)
    WHERE success = FALSE;

-- Index: support queries by operation type
CREATE INDEX IF NOT EXISTS idx_audit_entries_operation_type
    ON audit_entries (operation_type, occurred_at DESC)
    WHERE operation_type IS NOT NULL;

COMMENT ON COLUMN audit_entries.operation_type  IS 'Machine-readable constant identifying the financial mutation (added V68).';
COMMENT ON COLUMN audit_entries.performed_by    IS 'Actor identity string; may be a user id, service name, or job name (added V68).';
COMMENT ON COLUMN audit_entries.success         IS 'TRUE when the operation committed; FALSE when it was rolled back or threw (added V68).';
COMMENT ON COLUMN audit_entries.failure_reason  IS 'Short reason string when success = FALSE; NULL otherwise (added V68).';

-- ============================================================================
-- SECTION 2 — merchant_api_versions
-- ============================================================================

-- Stores the API version pinned by a merchant via
--   PUT /merchants/{id}/api-version
--
-- Version precedence (highest wins):
--   1. X-API-Version request header  (per-request override)
--   2. pinned_version from this table (merchant-level default)
--   3. ApiVersion.DEFAULT             (platform-level fallback)

CREATE TABLE IF NOT EXISTS merchant_api_versions (
    id               BIGSERIAL       NOT NULL,

    merchant_id      BIGINT          NOT NULL,
    pinned_version   VARCHAR(20)     NOT NULL,

    -- When did the pin take effect?  Set to NOW() if not supplied.
    effective_from   DATE            NOT NULL DEFAULT CURRENT_DATE,

    -- Audit timestamps
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT merchant_api_versions_pkey PRIMARY KEY (id),

    -- One active pin per merchant (upsert target)
    CONSTRAINT merchant_api_versions_merchant_unique UNIQUE (merchant_id)
);

-- Index on merchant_id (covered by the UNIQUE constraint; explicit for clarity)
CREATE INDEX IF NOT EXISTS idx_merchant_api_versions_merchant
    ON merchant_api_versions (merchant_id);

COMMENT ON TABLE  merchant_api_versions                      IS 'Stores the API version pinned by each merchant. Rows are upserted — each merchant has at most one active pin.';
COMMENT ON COLUMN merchant_api_versions.merchant_id          IS 'FK to merchant_accounts.id (not enforced here to avoid cross-schema pain in dev).';
COMMENT ON COLUMN merchant_api_versions.pinned_version       IS 'Date-formatted API version string, e.g. 2025-01-01.';
COMMENT ON COLUMN merchant_api_versions.effective_from       IS 'Date from which the pin is active.  Historical pins can be reconstructed via audit_entries.';
