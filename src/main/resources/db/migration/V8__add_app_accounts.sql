-- ─────────────────────────────────────────────────────────────
-- V8: Authentication accounts (login credentials + role)
-- Separate from the membership users table — identity/auth is its
-- own concern. Passwords are stored as BCrypt hashes only.
-- ─────────────────────────────────────────────────────────────

CREATE TABLE app_accounts (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
