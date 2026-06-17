-- ─────────────────────────────────────────────────────────────
-- V17: Orders (checkout) + link coupon redemptions to an order.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    subtotal        DECIMAL(10,2) NOT NULL,
    member_discount DECIMAL(10,2) NOT NULL,
    coupon_code     VARCHAR(50),
    coupon_discount DECIMAL(10,2) NOT NULL DEFAULT 0,
    delivery_fee    DECIMAL(10,2) NOT NULL,
    total           DECIMAL(10,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    placed_at       TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_user_id ON orders(user_id);

ALTER TABLE coupon_redemptions
    ADD COLUMN order_id BIGINT REFERENCES orders(id);
