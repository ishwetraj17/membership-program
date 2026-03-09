package com.firstclub.ledger.guard;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.membership.exception.MembershipException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Phase 10 — Service-layer immutability guard for ledger entities.
 *
 * <p>Sits in front of every ledger persistence call and ensures that only
 * brand-new (never-persisted) rows are written.  This is the application-level
 * complement to the DB trigger installed by migration V56.
 *
 * <h3>Two-layer protection model</h3>
 * <ol>
 *   <li>This guard rejects mutations before they reach Hibernate.</li>
 *   <li>The {@code trg_ledger_entries_immutable} / {@code trg_ledger_lines_immutable}
 *       DB triggers reject any UPDATE or DELETE that slips through via native SQL.</li>
 * </ol>
 *
 * <p>All columns on {@link LedgerEntry} and {@link LedgerLine} also carry
 * {@code updatable = false} so Hibernate never generates UPDATE SQL for them.
 */
@Component
public class ImmutableLedgerGuard {

    private static final String IMMUTABLE_CODE = "LEDGER_IMMUTABLE";

    /**
     * Asserts that {@code entry} has not yet been persisted (i.e. has no database id).
     *
     * @throws MembershipException (409) if the entry already has an assigned id
     */
    public void assertNewEntry(LedgerEntry entry) {
        if (entry.getId() != null) {
            throw new MembershipException(
                    "Ledger entry " + entry.getId() + " is immutable and cannot be modified. "
                    + "Create a REVERSAL entry to correct it.",
                    IMMUTABLE_CODE,
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Asserts that {@code line} has not yet been persisted.
     *
     * @throws MembershipException (409) if the line already has an assigned id
     */
    public void assertNewLine(LedgerLine line) {
        if (line.getId() != null) {
            throw new MembershipException(
                    "Ledger line " + line.getId() + " is immutable and cannot be modified. "
                    + "Create a REVERSAL entry to correct it.",
                    IMMUTABLE_CODE,
                    HttpStatus.CONFLICT);
        }
    }
}
