package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.repository.LedgerLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every {@link LedgerLine} references an existing {@link com.firstclub.ledger.entity.LedgerEntry}.
 *
 * <p>Orphan lines can arise from a partial delete of a ledger entry, or from a
 * foreign-key constraint that was disabled during a data migration.  They inflate
 * account balances without being traceable to any accounting event.
 */
@Component
@RequiredArgsConstructor
public class NoOrphanLedgerLineChecker implements InvariantChecker {

    public static final String NAME = "NO_ORPHAN_LEDGER_LINE";
    private static final String REPAIR =
            "For each orphan LedgerLine: verify whether the parent LedgerEntry existed and was "
            + "deleted (recover from backup or event log) or was never created (re-run the posting "
            + "service for the original event). After restoring the parent entry, re-enable FK "
            + "constraint to prevent future orphans.";

    private final LedgerLineRepository ledgerLineRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<LedgerLine> orphans = ledgerLineRepository.findOrphans();

        if (orphans.isEmpty()) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        List<InvariantViolation> violations = new ArrayList<>();
        for (LedgerLine line : orphans) {
            violations.add(InvariantViolation.builder()
                    .entityType("LedgerLine")
                    .entityId(String.valueOf(line.getId()))
                    .description(String.format(
                            "LedgerLine %d references entryId=%d which does not exist in ledger_entries",
                            line.getId(), line.getEntryId()))
                    .suggestedRepairAction(REPAIR)
                    .build());
        }

        return InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
