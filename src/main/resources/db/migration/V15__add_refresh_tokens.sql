-- ─────────────────────────────────────────────────────────────
-- V15: Server-side refresh tokens (revocable; powers logout).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    id                 BIGSERIAL PRIMARY KEY,
    token              VARCHAR(255) NOT NULL UNIQUE,
    username           VARCHAR(100) NOT NULL,
    membership_user_id BIGINT,
    expires_at         TIMESTAMP    NOT NULL,
    revoked            BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_username ON refresh_tokens(username);
