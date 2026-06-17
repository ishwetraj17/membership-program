-- ─────────────────────────────────────────────────────────────
-- V16: Redeemable coupons + redemption ledger (usage limits).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE coupons (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50)   NOT NULL UNIQUE,
    description     VARCHAR(255)  NOT NULL,
    discount_type   VARCHAR(20)   NOT NULL,
    discount_value  DECIMAL(10,2) NOT NULL,
    max_redemptions INTEGER,
    per_user_limit  INTEGER,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE coupon_redemptions (
    id              BIGSERIAL PRIMARY KEY,
    coupon_id       BIGINT        NOT NULL REFERENCES coupons(id),
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    discount_amount DECIMAL(10,2) NOT NULL,
    redeemed_at     TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_coupon_redemptions_coupon ON coupon_redemptions(coupon_id);
CREATE INDEX idx_coupon_redemptions_coupon_user ON coupon_redemptions(coupon_id, user_id);
