-- =============================================================================
-- V23 — Tax Engine: merchant tax profiles, customer tax profiles
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. tax_profiles  (one GST profile per merchant)
-- ---------------------------------------------------------------------------
CREATE TABLE tax_profiles (
    id                      BIGSERIAL     PRIMARY KEY,
    merchant_id             BIGINT        NOT NULL UNIQUE REFERENCES merchant_accounts(id),
    gstin                   VARCHAR(32)   NOT NULL,
    legal_state_code        VARCHAR(8)    NOT NULL,
    registered_business_name VARCHAR(255) NOT NULL,
    tax_mode                VARCHAR(16)   NOT NULL,  -- B2B | B2C
    created_at              TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- 2. customer_tax_profiles  (one GST profile per customer)
-- ---------------------------------------------------------------------------
CREATE TABLE customer_tax_profiles (
    id           BIGSERIAL   PRIMARY KEY,
    customer_id  BIGINT      NOT NULL UNIQUE REFERENCES customers(id),
    gstin        VARCHAR(32) NULL,
    state_code   VARCHAR(8)  NOT NULL,
    entity_type  VARCHAR(16) NOT NULL,  -- INDIVIDUAL | BUSINESS
    tax_exempt   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
