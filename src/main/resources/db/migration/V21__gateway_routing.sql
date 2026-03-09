-- Phase 7: Gateway Routing and Failover Logic
-- Tables: gateway_route_rules, gateway_health

CREATE TABLE gateway_route_rules (
    id                  BIGSERIAL PRIMARY KEY,
    merchant_id         BIGINT      NULL REFERENCES merchant_accounts(id),
    priority            INT         NOT NULL,
    payment_method_type VARCHAR(32) NOT NULL,
    currency            VARCHAR(10) NOT NULL,
    country_code        VARCHAR(8)  NULL,
    retry_number        INT         NOT NULL DEFAULT 1,
    preferred_gateway   VARCHAR(64) NOT NULL,
    fallback_gateway    VARCHAR(64) NULL,
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE TABLE gateway_health (
    gateway_name            VARCHAR(64) PRIMARY KEY,
    status                  VARCHAR(16) NOT NULL,
    last_checked_at         TIMESTAMP   NOT NULL,
    rolling_success_rate    DECIMAL(8,4) NOT NULL,
    rolling_p95_latency_ms  BIGINT      NOT NULL
);

-- Seed default health for simulated gateways
INSERT INTO gateway_health (gateway_name, status, last_checked_at, rolling_success_rate, rolling_p95_latency_ms)
VALUES
    ('razorpay', 'HEALTHY', NOW(), 99.50, 120),
    ('stripe',   'HEALTHY', NOW(), 99.80, 95),
    ('payu',     'HEALTHY', NOW(), 98.20, 180);

CREATE INDEX idx_gateway_route_lookup   ON gateway_route_rules(merchant_id, payment_method_type, currency, retry_number, active);
CREATE INDEX idx_gateway_route_priority ON gateway_route_rules(priority);
