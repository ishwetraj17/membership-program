-- ─────────────────────────────────────────────────────────────
-- V12: Link auth accounts to a membership user.
-- Enables per-user ownership checks: a USER may only act on their
-- own membership resources; ADMIN is unrestricted. NULL = no link
-- (e.g. the admin account).
-- ─────────────────────────────────────────────────────────────

ALTER TABLE app_accounts
    ADD COLUMN membership_user_id BIGINT REFERENCES users(id);
