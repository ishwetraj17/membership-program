-- ─────────────────────────────────────────────────────────────────────────────
-- Phase 10: Ledger Immutability
-- Adds DB-level protection against UPDATE/DELETE on ledger rows.
-- All corrections must be performed via reversal entries.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Audit / reversal columns on ledger_entries ────────────────────────────────

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS reversal_of_entry_id BIGINT NULL
        REFERENCES ledger_entries(id),
    ADD COLUMN IF NOT EXISTS posted_by_user_id    BIGINT NULL,
    ADD COLUMN IF NOT EXISTS reversal_reason      TEXT   NULL;

-- ── Trigger function: reject any UPDATE or DELETE on a ledger row ─────────────

CREATE OR REPLACE FUNCTION prevent_ledger_modification()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'Ledger records are immutable: DELETE is not allowed on table "%" (id=%). Correct via reversal entry.',
            TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'raise_exception';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION
            'Ledger records are immutable: UPDATE is not allowed on table "%" (id=%). Correct via reversal entry.',
            TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NULL; -- unreachable; satisfies RETURNS TRIGGER contract
END;
$$ LANGUAGE plpgsql;

-- ── Immutability trigger on ledger_entries ────────────────────────────────────

DROP TRIGGER IF EXISTS trg_ledger_entries_immutable ON ledger_entries;
CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();

-- ── Immutability trigger on ledger_lines ──────────────────────────────────────

DROP TRIGGER IF EXISTS trg_ledger_lines_immutable ON ledger_lines;
CREATE TRIGGER trg_ledger_lines_immutable
    BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();

-- ── Supporting index for reversal lineage lookups ─────────────────────────────

CREATE INDEX IF NOT EXISTS idx_ledger_entries_reversal_of
    ON ledger_entries(reversal_of_entry_id)
    WHERE reversal_of_entry_id IS NOT NULL;
