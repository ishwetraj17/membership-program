-- Phase 3: Add merchant-scoped idempotency columns and widen the key column.
--
-- Background:
--   V5 created the idempotency_keys table with `key` VARCHAR(80) as the sole
--   primary key, giving no tenant isolation.  Phase 3 introduces:
--     1. merchant_id  — the authenticated principal (tenant identifier).
--     2. endpoint_signature — "{METHOD}:{url-template}" for conflict detection.
--     3. content_type — preserved for faithful response replay.
--   The key column is widened to 255 chars so internal composite keys of the
--   form "{merchantId}:{rawKey}" fit within the primary-key constraint.
--
-- Data safety:
--   All new columns are nullable; existing rows are unaffected and continue to
--   work through the old single-key lookup path during any rolling deployment.

ALTER TABLE idempotency_keys
    ALTER COLUMN key TYPE VARCHAR(255);

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS merchant_id        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS endpoint_signature VARCHAR(255),
    ADD COLUMN IF NOT EXISTS content_type       VARCHAR(128);

-- Support the admin debug endpoint: look up all keys for a merchant.
CREATE INDEX IF NOT EXISTS idx_idem_merchant_id
    ON idempotency_keys (merchant_id);
