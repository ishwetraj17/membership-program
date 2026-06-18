-- ─────────────────────────────────────────────────────────────
-- V19: Configurable commerce benefit engine
--
-- benefit_rules are the typed, data-driven benefits the checkout engine
-- evaluates: percentage discounts (optionally category-scoped, optionally
-- threshold-gated) and per-fee waivers (delivery / handling / small-cart /
-- surge / rain). Business teams configure these via the admin API — no code
-- change required. Baseline rules mirroring each tier's headline discount and
-- free-delivery flag are seeded at startup for backward compatibility.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE benefit_rules (
    id                  BIGSERIAL PRIMARY KEY,
    tier_id             BIGINT        NOT NULL REFERENCES membership_tiers(id),
    benefit_type        VARCHAR(40)   NOT NULL,
    product_category    VARCHAR(30),
    min_cart_value      NUMERIC(10,2),
    discount_percentage NUMERIC(5,2),
    max_discount_amount NUMERIC(10,2),
    priority            INT           NOT NULL DEFAULT 0,
    active              BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_benefit_rules_tier_active ON benefit_rules(tier_id, active);

-- Orders now record every ancillary fee actually charged (net of waivers), so a placed
-- order is fully reconcilable. Existing rows default to zero — backward compatible.
ALTER TABLE orders ADD COLUMN handling_fee   NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN small_cart_fee NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN surge_fee      NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN rain_fee       NUMERIC(10,2) NOT NULL DEFAULT 0;
