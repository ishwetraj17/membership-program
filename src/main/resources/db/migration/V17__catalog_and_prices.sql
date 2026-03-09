-- ============================================================
-- V17: Catalog domain — products, prices, price_versions
-- Phase 3: Versioned pricing model
-- ============================================================

-- ── Products ──────────────────────────────────────────────────
CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    merchant_id BIGINT       NOT NULL REFERENCES merchant_accounts(id),
    product_code VARCHAR(64)  NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    description TEXT,
    status      VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_merchant_code UNIQUE (merchant_id, product_code)
);

CREATE INDEX idx_products_merchant_status ON products(merchant_id, status);

-- ── Prices ────────────────────────────────────────────────────
CREATE TABLE prices (
    id                     BIGSERIAL PRIMARY KEY,
    merchant_id            BIGINT        NOT NULL REFERENCES merchant_accounts(id),
    product_id             BIGINT        NOT NULL REFERENCES products(id),
    price_code             VARCHAR(64)   NOT NULL,
    billing_type           VARCHAR(32)   NOT NULL,
    currency               VARCHAR(10)   NOT NULL,
    amount                 DECIMAL(18,4) NOT NULL,
    billing_interval_unit  VARCHAR(16)   NOT NULL,
    billing_interval_count INT           NOT NULL,
    trial_days             INT           NOT NULL DEFAULT 0,
    active                 BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_price_merchant_code UNIQUE (merchant_id, price_code)
);

CREATE INDEX idx_prices_product_id ON prices(product_id);

-- ── Price Versions ────────────────────────────────────────────
CREATE TABLE price_versions (
    id                              BIGSERIAL PRIMARY KEY,
    price_id                        BIGINT        NOT NULL REFERENCES prices(id),
    effective_from                  TIMESTAMP     NOT NULL,
    effective_to                    TIMESTAMP     NULL,
    amount                          DECIMAL(18,4) NOT NULL,
    currency                        VARCHAR(10)   NOT NULL,
    grandfather_existing_subscriptions BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_price_versions_price_effective ON price_versions(price_id, effective_from DESC);
