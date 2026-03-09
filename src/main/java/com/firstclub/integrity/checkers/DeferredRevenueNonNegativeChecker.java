package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Verifies that the SUBSCRIPTION_LIABILITY account has a non-negative credit
 * balance (credits &ge; debits).
 *
 * <p>SUBSCRIPTION_LIABILITY tracks cash received for services not yet delivered.
 * If debits (revenue recognized) exceed credits (payments received), we have
 * "recognized" more revenue than we ever collected — a data error.
 */
@Component
@RequiredArgsConstructor
public class DeferredRevenueNonNegativeChecker implements InvariantChecker {

    public static final String NAME = "DEFERRED_REVENUE_NON_NEGATIVE";
    private static final String ACCOUNT_NAME = "SUBSCRIPTION_LIABILITY";
    private static final String REPAIR =
            "Investigate revenue recognition schedules for invoices recognized beyond their service period. "
            + "Check for recognition entries without a corresponding subscription-liability credit.";

    private final LedgerAccountRepository accountRepository;
    private final LedgerLineRepository    lineRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        Optional<LedgerAccount> account = accountRepository.findByName(ACCOUNT_NAME);
        if (account.isEmpty()) {
            // No account seeded yet; treat as pass (no data).
            return InvariantResult.pass(NAME, getSeverity());
        }

        Long accountId = account.get().getId();
        BigDecimal dr = lineRepository.sumByAccountIdAndDirection(accountId, LineDirection.DEBIT);
        BigDecimal cr = lineRepository.sumByAccountIdAndDirection(accountId, LineDirection.CREDIT);

        // For a LIABILITY account the normal balance is CREDIT.
        // Violation: DEBIT > CREDIT meaning we've debited (recognized as revenue)
        // more than we've ever credited (received as cash).
        if (dr.compareTo(cr) <= 0) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        InvariantViolation v = InvariantViolation.builder()
                .entityType("LedgerAccount")
                .entityId(ACCOUNT_NAME)
                .description(String.format(
                        "%s has debit total %s > credit total %s — recognized more revenue than cash received",
                        ACCOUNT_NAME, dr, cr))
                .suggestedRepairAction(REPAIR)
                .build();
        return InvariantResult.fail(NAME, getSeverity(), List.of(v));
    }
}
