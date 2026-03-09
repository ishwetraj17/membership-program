package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Verifies that the sum of all DEBIT lines equals the sum of all CREDIT lines
 * across the entire ledger (the fundamental double-entry accounting equation).
 *
 * <p>Severity: {@link InvariantSeverity#CRITICAL} — any non-zero imbalance is a
 * financial data inconsistency that must be investigated immediately.
 */
@Component
@RequiredArgsConstructor
public class BalanceSheetEquationChecker implements InvariantChecker {

    public static final String NAME = "BALANCE_SHEET_EQUATION";
    private static final String REPAIR =
            "Run a forensic diff across ledger_entries and ledger_lines. "
            + "Identify entries that are missing a balancing line and post a correcting reversal entry.";

    private final LedgerLineRepository ledgerLineRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.CRITICAL; }

    @Override
    public InvariantResult check() {
        BigDecimal totalDebit  = ledgerLineRepository.sumByDirection(LineDirection.DEBIT);
        BigDecimal totalCredit = ledgerLineRepository.sumByDirection(LineDirection.CREDIT);

        if (totalDebit.compareTo(totalCredit) == 0) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        InvariantViolation v = InvariantViolation.builder()
                .entityType("Ledger")
                .entityId("system")
                .description(String.format(
                        "Total DEBIT %s ≠ total CREDIT %s (imbalance: %s)",
                        totalDebit, totalCredit, totalDebit.subtract(totalCredit)))
                .suggestedRepairAction(REPAIR)
                .build();
        return InvariantResult.fail(NAME, getSeverity(), List.of(v));
    }
}
