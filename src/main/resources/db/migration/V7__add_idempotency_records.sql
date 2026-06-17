-- ─────────────────────────────────────────────────────────────
-- V7: Idempotency records
-- Stores the outcome of idempotent writes so client retries with the
-- same Idempotency-Key replay the original result. The unique key is
-- the concurrency guard against duplicate processing.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE idempotency_records (
    id              BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash    VARCHAR(255) NOT NULL,
    target_type     VARCHAR(50)  NOT NULL,
    target_id       BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
