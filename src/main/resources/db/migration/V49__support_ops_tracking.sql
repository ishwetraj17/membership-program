-- =============================================================================
-- V49: Support & Ops Case Tracking
-- Adds tables for internal ops support-case management linked to any entity
-- (customer, subscription, invoice, payment, refund, dispute, recon_mismatch).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- support_cases: one row per investigation / incident / ops case
-- ---------------------------------------------------------------------------
CREATE TABLE support_cases (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         BIGINT        NOT NULL,
    linked_entity_type  VARCHAR(64)   NOT NULL,
    linked_entity_id    BIGINT        NOT NULL,
    title               VARCHAR(255)  NOT NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'OPEN',
    priority            VARCHAR(32)   NOT NULL DEFAULT 'MEDIUM',
    owner_user_id       BIGINT        NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- tenant reads and linked-entity cross-links
CREATE INDEX idx_sc_merchant_id
    ON support_cases (merchant_id);

CREATE INDEX idx_sc_entity
    ON support_cases (linked_entity_type, linked_entity_id);

CREATE INDEX idx_sc_merchant_status
    ON support_cases (merchant_id, status);

CREATE INDEX idx_sc_owner_user
    ON support_cases (owner_user_id)
    WHERE owner_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- support_notes: immutable notes / audit trail entries per case
-- ---------------------------------------------------------------------------
CREATE TABLE support_notes (
    id              BIGSERIAL PRIMARY KEY,
    case_id         BIGINT        NOT NULL REFERENCES support_cases (id),
    note_text       TEXT          NOT NULL,
    author_user_id  BIGINT        NOT NULL,
    visibility      VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL_ONLY',
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sn_case_id
    ON support_notes (case_id);

CREATE INDEX idx_sn_author
    ON support_notes (author_user_id);
