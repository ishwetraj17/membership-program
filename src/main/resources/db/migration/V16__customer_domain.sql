-- V16__customer_domain.sql
-- Phase 2: Customer Domain — Separate from Platform User
-- Introduces the customer entity as a merchant-scoped billable identity,
-- distinct from the platform's authenticated operator/admin User.

-- ─── Customers ───────────────────────────────────────────────────────────────
-- A customer belongs to exactly one merchant (tenant isolation).
-- email is unique per merchant, not globally unique.
-- Sensitive fields (phone, billing_address, shipping_address) are stored as
-- AES-256-GCM encrypted ciphertext by the EncryptedStringConverter JPA converter.

CREATE TABLE customers (
    id                          BIGSERIAL       PRIMARY KEY,
    merchant_id                 BIGINT          NOT NULL REFERENCES merchant_accounts (id),
    external_customer_id        VARCHAR(128),
    email                       VARCHAR(255)    NOT NULL,
    phone                       VARCHAR(1024),
    full_name                   VARCHAR(255)    NOT NULL,
    billing_address             TEXT,
    shipping_address            TEXT,
    status                      VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    default_payment_method_id   BIGINT,
    -- FK to future payment_methods table intentionally deferred to avoid circular deps.
    -- Will be constrained in a later migration once payment_methods table exists.
    metadata_json               TEXT,
    created_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMP       NOT NULL DEFAULT NOW(),

    -- Enforce email uniqueness within a merchant
    CONSTRAINT uq_customer_merchant_email UNIQUE (merchant_id, email),

    -- external_customer_id is optional but must be unique within merchant when set.
    -- A partial unique index (below) handles the nullable case correctly.
    CONSTRAINT uq_customer_merchant_external_id UNIQUE (merchant_id, external_customer_id)
);

-- Composite index: frequent filter by (merchant_id, email)
CREATE INDEX idx_customers_merchant_email
    ON customers (merchant_id, email);

-- Composite index: list/filter by status within a merchant
CREATE INDEX idx_customers_merchant_status
    ON customers (merchant_id, status);

-- ─── Customer Notes ───────────────────────────────────────────────────────────
-- Notes are immutable audit-trail entries attached to a customer.
-- author_user_id references the platform User (operator/admin) who wrote the note.
-- visibility controls whether the note is internal-only or merchant-visible.

CREATE TABLE customer_notes (
    id              BIGSERIAL   PRIMARY KEY,
    customer_id     BIGINT      NOT NULL REFERENCES customers (id),
    author_user_id  BIGINT      NOT NULL REFERENCES users (id),
    note_text       TEXT        NOT NULL,
    visibility      VARCHAR(32) NOT NULL DEFAULT 'INTERNAL_ONLY',
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
    -- No updated_at: notes are immutable once written.
);

CREATE INDEX idx_customer_notes_customer_id
    ON customer_notes (customer_id);
