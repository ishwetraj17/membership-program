-- ─────────────────────────────────────────────────────────────
-- V4: Optimistic-locking version column for users
-- Prevents concurrent PATCH /users/{id} requests from
-- silently overwriting each other (read-modify-write race).
-- ─────────────────────────────────────────────────────────────

ALTER TABLE users
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
