-- ─────────────────────────────────────────────────────────────
-- V9: Configurable benefit catalog
-- Benefits become first-class entities mapped to tiers, so perks can
-- be added/attached without code changes. Rows are seeded at startup
-- from configuration (works under H2 tests too).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE benefits (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NOT NULL,
    category    VARCHAR(20)  NOT NULL
);

CREATE TABLE tier_benefits (
    id            BIGSERIAL PRIMARY KEY,
    tier_id       BIGINT      NOT NULL REFERENCES membership_tiers(id),
    benefit_id    BIGINT      NOT NULL REFERENCES benefits(id),
    benefit_value VARCHAR(50),
    CONSTRAINT uq_tier_benefit UNIQUE (tier_id, benefit_id)
);

CREATE INDEX idx_tier_benefits_tier_id ON tier_benefits(tier_id);
