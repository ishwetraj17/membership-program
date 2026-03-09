-- Phase 5: Payment Methods and Customer Billing Instruments
-- Creates payment_methods and payment_method_mandates tables.
-- Uses only tokenized/provider-reference data — raw card PAN is never stored.

CREATE TABLE payment_methods (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     BIGINT NOT NULL REFERENCES merchant_accounts(id),
    customer_id     BIGINT NOT NULL REFERENCES customers(id),
    method_type     VARCHAR(32) NOT NULL,
    provider_token  VARCHAR(255) NOT NULL,
    fingerprint     VARCHAR(255) NULL,
    last4           VARCHAR(8) NULL,
    brand           VARCHAR(64) NULL,
    provider        VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_method_mandates (
    id                  BIGSERIAL PRIMARY KEY,
    payment_method_id   BIGINT NOT NULL REFERENCES payment_methods(id),
    mandate_reference   VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    max_amount          DECIMAL(18,4) NOT NULL,
    currency            VARCHAR(10) NOT NULL,
    approved_at         TIMESTAMP NULL,
    revoked_at          TIMESTAMP NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_payment_methods_merchant_customer
    ON payment_methods(merchant_id, customer_id, status);

CREATE INDEX idx_payment_methods_customer_default
    ON payment_methods(customer_id, is_default);

-- Unique: one (provider, provider_token) pair globally — provider tokens are
-- opaque gateway references and must never appear twice on the platform.
CREATE UNIQUE INDEX uq_payment_methods_provider_token
    ON payment_methods(provider, provider_token);

CREATE INDEX idx_payment_method_mandates_pm_id
    ON payment_method_mandates(payment_method_id);
