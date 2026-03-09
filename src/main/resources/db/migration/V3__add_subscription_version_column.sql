-- V3__add_subscription_version_column.sql
-- Adds optimistic-locking version column that JPA @Version requires on the
-- subscriptions table.  Without this column, Hibernate schema validation fails
-- (ddl-auto=validate) in the local and prod profiles.

ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
