package com.firstclub.ledger.reversal;

import com.firstclub.ledger.entity.LedgerEntry;

/**
 * Phase 10 — Reversal-only correction model for the double-entry ledger.
 *
 * <p>A reversal entry mirrors every line of the original entry with flipped
 * debit/credit sides, so the original + reversal pair nets to zero across all
 * affected accounts.  The original entry is left <em>completely unchanged</em>;
 * it remains part of the permanent audit trail.
 *
 * <h3>Duplicate reversal policy</h3>
 * Each entry may be reversed <strong>at most once</strong>.  If the reversal
 * itself contained an error, reverse the reversal entry to produce a
 * re-reversal.  This ensures every correction is traceable in the ledger.
 */
public interface LedgerReversalService {

    /**
     * Creates a new REVERSAL entry that mirrors all lines of the given entry
     * with flipped sides.
     *
     * @param originalEntryId  id of the entry to reverse
     * @param reason           mandatory explanation (non-blank)
     * @param postedByUserId   optional id of the operator initiating the reversal
     * @return the newly persisted reversal {@link LedgerEntry}
     * @throws com.firstclub.membership.exception.MembershipException (404) if the original entry does not exist
     * @throws com.firstclub.membership.exception.MembershipException (422) if reason is blank
     * @throws com.firstclub.membership.exception.MembershipException (409) if a reversal already exists for this entry
     * @throws com.firstclub.membership.exception.MembershipException (422) if trying to reverse a REVERSAL entry directly
     */
    LedgerEntry reverse(Long originalEntryId, String reason, Long postedByUserId);
}
