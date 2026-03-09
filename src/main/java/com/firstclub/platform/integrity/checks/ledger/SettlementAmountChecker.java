package com.firstclub.platform.integrity.checks.ledger;

import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.recon.entity.SettlementBatch;
import com.firstclub.recon.entity.SettlementBatchStatus;
import com.firstclub.recon.repository.SettlementBatchRepository;
import com.firstclub.platform.integrity.IntegrityCheckResult;
import com.firstclub.platform.integrity.IntegrityCheckSeverity;
import com.firstclub.platform.integrity.IntegrityChecker;
import com.firstclub.platform.integrity.IntegrityViolation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that each POSTED settlement batch has a corresponding
 * {@link LedgerEntryType#SETTLEMENT} journal entry, and that the
 * credit-side amount on that entry matches the batch's net amount.
 *
 * <p>A missing or mismatched settlement entry means cash was transferred
 * without an accounting record — a treasury integrity violation.
 */
@Component
@RequiredArgsConstructor
public class SettlementAmountChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final LedgerLineRepository ledgerLineRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "ledger.settlement_has_matching_journal";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.HIGH;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<SettlementBatch> batches;
        if (merchantId != null) {
            batches = entityManager.createQuery(
                    "SELECT sb FROM SettlementBatch sb WHERE sb.merchantId = :mid "
                    + "AND sb.status = :status AND sb.createdAt >= :since",
                    SettlementBatch.class)
                    .setParameter("mid", merchantId)
                    .setParameter("status", SettlementBatchStatus.POSTED)
                    .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                    .getResultList();
        } else {
            batches = entityManager.createQuery(
                    "SELECT sb FROM SettlementBatch sb WHERE sb.status = :status "
                    + "AND sb.createdAt >= :since",
                    SettlementBatch.class)
                    .setParameter("status", SettlementBatchStatus.POSTED)
                    .setParameter("since", LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                    .setMaxResults(500)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (SettlementBatch batch : batches) {
            // Look for a SETTLEMENT entry referencing this batch
            List<LedgerEntry> settlementEntries = entityManager.createQuery(
                    "SELECT e FROM LedgerEntry e WHERE e.entryType = :type "
                    + "AND e.referenceId = :refId",
                    LedgerEntry.class)
                    .setParameter("type", LedgerEntryType.SETTLEMENT)
                    .setParameter("refId", batch.getId())
                    .getResultList();

            if (settlementEntries.isEmpty()) {
                violations.add(IntegrityViolation.builder()
                        .entityType("SETTLEMENT_BATCH")
                        .entityId(batch.getId())
                        .details("POSTED settlement batch has no SETTLEMENT ledger entry")
                        .preview("merchantId=" + batch.getMerchantId()
                                + ", batchDate=" + batch.getBatchDate()
                                + ", netAmount=" + batch.getNetAmount())
                        .build());
            } else {
                // Verify the credit amount matches batch net amount
                for (LedgerEntry entry : settlementEntries) {
                    List<LedgerLine> lines = ledgerLineRepository.findByEntryId(entry.getId());
                    BigDecimal creditSum = lines.stream()
                            .filter(l -> LineDirection.CREDIT.equals(l.getDirection()))
                            .map(LedgerLine::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    if (batch.getNetAmount() != null
                            && creditSum.compareTo(batch.getNetAmount()) != 0) {
                        violations.add(IntegrityViolation.builder()
                                .entityType("SETTLEMENT_BATCH")
                                .entityId(batch.getId())
                                .details("Settlement ledger credit=" + creditSum
                                        + " ≠ batch netAmount=" + batch.getNetAmount())
                                .preview("ledgerEntryId=" + entry.getId()
                                        + ", batchDate=" + batch.getBatchDate())
                                .build());
                    }
                }
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
                        ? "All " + batches.size() + " POSTED settlement batches have matching ledger entries"
                        : violations.size() + " settlement batches missing or mismatched journal entries")
                .suggestedRepairKey(passed ? null : "ledger.post_missing_settlement_entry")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
