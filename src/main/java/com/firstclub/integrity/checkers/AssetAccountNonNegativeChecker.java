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
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that no ASSET ledger account has a negative net balance
 * (i.e. total debits &lt; total credits).
 *
 * <p>For a normal ASSET account, debits increase the balance and credits
 * decrease it.  A net-credit position means the account is overstated on the
 * liabilities side, which is physically impossible in a correctly-posted
 * double-entry ledger.
 */
@Component
@RequiredArgsConstructor
public class AssetAccountNonNegativeChecker implements InvariantChecker {

    public static final String NAME = "ASSET_ACCOUNT_NON_NEGATIVE";
    private static final String REPAIR =
            "Investigate all credit entries posted to the affected ASSET account. Common causes: "
            + "a refund was posted to the receivables account instead of the refund payable account, "
            + "or a reversal entry was applied twice. Identify the erroneous double-credit and post "
            + "a correcting debit journal entry with the original reference ID.";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerLineRepository    ledgerLineRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        List<LedgerAccount> assetAccounts =
                ledgerAccountRepository.findByAccountType(LedgerAccount.AccountType.ASSET);

        List<InvariantViolation> violations = new ArrayList<>();
        for (LedgerAccount account : assetAccounts) {
            BigDecimal totalDebits  = ledgerLineRepository
                    .sumByAccountIdAndDirection(account.getId(), LineDirection.DEBIT);
            BigDecimal totalCredits = ledgerLineRepository
                    .sumByAccountIdAndDirection(account.getId(), LineDirection.CREDIT);

            if (totalDebits.compareTo(totalCredits) < 0) {
                violations.add(InvariantViolation.builder()
                        .entityType("LedgerAccount")
                        .entityId(String.valueOf(account.getId()))
                        .description(String.format(
                                "ASSET account %d (%s) has negative net balance: debits=%s credits=%s",
                                account.getId(), account.getName(), totalDebits, totalCredits))
                        .suggestedRepairAction(REPAIR)
                        .build());
            }
        }

        return violations.isEmpty()
                ? InvariantResult.pass(NAME, getSeverity())
                : InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
