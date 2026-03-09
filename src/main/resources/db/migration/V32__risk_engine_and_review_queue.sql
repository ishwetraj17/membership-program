-- Phase 18: Risk Engine and Review Queue

CREATE TABLE risk_rules (
    id          BIGSERIAL    PRIMARY KEY,
    merchant_id BIGINT       NULL REFERENCES merchant_accounts(id),
    rule_code   VARCHAR(64)  NOT NULL,
    rule_type   VARCHAR(64)  NOT NULL,
    config_json TEXT         NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INT          NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE risk_decisions (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_id       BIGINT       NOT NULL REFERENCES merchant_accounts(id),
    payment_intent_id BIGINT       NOT NULL REFERENCES payment_intents_v2(id),
    customer_id       BIGINT       NOT NULL REFERENCES customers(id),
    score             INT          NOT NULL,
    decision          VARCHAR(16)  NOT NULL,
    matched_rules_json TEXT        NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE manual_review_cases (
    id                BIGSERIAL    PRIMARY KEY,
    merchant_id       BIGINT       NOT NULL REFERENCES merchant_accounts(id),
    payment_intent_id BIGINT       NOT NULL REFERENCES payment_intents_v2(id),
    customer_id       BIGINT       NOT NULL REFERENCES customers(id),
    status            VARCHAR(16)  NOT NULL,
    assigned_to       BIGINT       NULL REFERENCES users(id),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_risk_rules_lookup              ON risk_rules(merchant_id, active, priority);
CREATE INDEX idx_risk_decisions_merchant_created ON risk_decisions(merchant_id, created_at);
CREATE INDEX idx_review_cases_status            ON manual_review_cases(status);
