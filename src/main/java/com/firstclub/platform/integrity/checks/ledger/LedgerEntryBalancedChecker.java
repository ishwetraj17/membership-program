package com.firstclub.platform.integrity.checks.ledger;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies the fundamental double-entry accounting invariant:
 * for every ledger entry, {@code SUM(DEBIT lines) == SUM(CREDIT lines)}.
 *
 * <p>Any imbalanced entry represents a critical accounting error.
 */
@Component
@RequiredArgsConstructor
public class LedgerEntryBalancedChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final LedgerLineRepository ledgerLineRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "ledger.entry_balanced";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<LedgerEntry> entries = entityManager
                .createQuery("SELECT e FROM LedgerEntry e WHERE e.createdAt >= :since", LedgerEntry.class)
                .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                .setMaxResults(5000)
                .getResultList();

        List<IntegrityViolation> violations = new ArrayList<>();

        for (LedgerEntry entry : entries) {
            List<LedgerLine> lines = ledgerLineRepository.findByEntryId(entry.getId());

            BigDecimal debitSum = lines.stream()
                    .filter(l -> LineDirection.DEBIT.equals(l.getDirection()))
                    .map(LedgerLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal creditSum = lines.stream()
                    .filter(l -> LineDirection.CREDIT.equals(l.getDirection()))
                    .map(LedgerLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (debitSum.compareTo(creditSum) != 0) {
                violations.add(IntegrityViolation.builder()
                        .entityType("LEDGER_ENTRY")
                        .entityId(entry.getId())
                        .details("Entry not balanced: debitSum=" + debitSum
                                + " creditSum=" + creditSum
                                + " (delta=" + debitSum.subtract(creditSum) + ")")
                        .preview("entryType=" + entry.getEntryType()
                                + ", referenceType=" + entry.getReferenceType()
                                + ", referenceId=" + entry.getReferenceId())
                        .build());
            }
        }

        boolean passed = violations.isEmpty();
        return IntegrityCheckResult.builder()
                .invariantKey(getInvariantKey())
                .severity(getSeverity())
                .passed(passed)
                .violationCount(violations.size())
                .violations(violations.stream().limit(PREVIEW_CAP).collect(Collectors.toList()))
                .details(passed
                        ? "All " + entries.size() + " ledger entries are balanced"
                        : violations.size() + " ledger entries are unbalanced (DEBIT ≠ CREDIT)")
                .suggestedRepairKey(passed ? null : "ledger.post_reversing_correcting_entry")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
