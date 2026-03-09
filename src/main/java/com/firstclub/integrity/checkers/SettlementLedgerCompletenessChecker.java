package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that every {@code SETTLEMENT} ledger entry has a non-null
 * {@code referenceId} pointing to a settlement batch.
 *
 * <p>A settlement entry with no reference means the batch row was either
 * never created or was deleted after the ledger was written.  Such entries
 * cannot be reconciled with the payout statement from the payment gateway.
 */
@Component
@RequiredArgsConstructor
public class SettlementLedgerCompletenessChecker implements InvariantChecker {

    public static final String NAME = "SETTLEMENT_LEDGER_COMPLETENESS";
    private static final String REPAIR =
            "For each un-referenced SETTLEMENT entry: look up the gateway payout ID in the entry's "
            + "createdAt timestamp window and recreate the missing SettlementBatch row, then set "
            + "referenceType=SETTLEMENT_BATCH / referenceId on the ledger entry.";

    private final LedgerEntryRepository ledgerEntryRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.MEDIUM; }

    @Override
    public InvariantResult check() {
        List<LedgerEntry> settlements = ledgerEntryRepository.findByEntryType(LedgerEntryType.SETTLEMENT);

        List<InvariantViolation> violations = new ArrayList<>();
        for (LedgerEntry entry : settlements) {
            if (entry.getReferenceId() == null) {
                violations.add(InvariantViolation.builder()
                        .entityType("LedgerEntry")
                        .entityId(String.valueOf(entry.getId()))
                        .description(String.format(
                                "SETTLEMENT ledger entry %d has null referenceId (expected a SettlementBatch ID)",
                                entry.getId()))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
