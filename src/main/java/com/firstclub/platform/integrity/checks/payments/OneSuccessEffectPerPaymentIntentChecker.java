package com.firstclub.platform.integrity.checks.payments;

import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Verifies that each SUCCEEDED payment intent has exactly one
 * {@link LedgerEntryType#PAYMENT_CAPTURED} ledger entry posted against it.
 *
 * <p>More than one PAYMENT_CAPTURED entry indicates a double-charge;
 * zero entries indicates revenue was captured without a corresponding
 * accounting record.
 */
@Component
@RequiredArgsConstructor
public class OneSuccessEffectPerPaymentIntentChecker implements IntegrityChecker {

    private static final int LOOK_BACK_DAYS = 90;
    private static final int PREVIEW_CAP = 50;

    private final LedgerEntryRepository ledgerEntryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public String getInvariantKey() {
        return "payments.one_success_effect_per_payment_intent";
    }

    @Override
    public IntegrityCheckSeverity getSeverity() {
        return IntegrityCheckSeverity.CRITICAL;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public IntegrityCheckResult run(@Nullable Long merchantId) {
        List<PaymentIntentV2> succeededIntents;
        if (merchantId != null) {
            succeededIntents = entityManager.createQuery(
                    "SELECT pi FROM PaymentIntentV2 pi WHERE pi.merchant.id = :mid AND pi.status = :status",
                    PaymentIntentV2.class)
                    .setParameter("mid", merchantId)
                    .setParameter("status", PaymentIntentStatusV2.SUCCEEDED)
                    .getResultList();
        } else {
            succeededIntents = entityManager.createQuery(
                    "SELECT pi FROM PaymentIntentV2 pi WHERE pi.status = :status AND pi.createdAt >= :since",
                    PaymentIntentV2.class)
                    .setParameter("status", PaymentIntentStatusV2.SUCCEEDED)
                    .setParameter("since", java.time.LocalDateTime.now().minusDays(LOOK_BACK_DAYS))
                    .setMaxResults(2000)
                    .getResultList();
        }

        List<IntegrityViolation> violations = new ArrayList<>();

        for (var intent : succeededIntents) {
            var entries = ledgerEntryRepository
                    .findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, intent.getId());
            long captureCount = entries.stream()
                    .filter(e -> LedgerEntryType.PAYMENT_CAPTURED.equals(e.getEntryType()))
                    .count();

            if (captureCount != 1) {
                violations.add(IntegrityViolation.builder()
                        .entityType("PAYMENT_INTENT")
                        .entityId(intent.getId())
                        .details("SUCCEEDED intent has " + captureCount
                                + " PAYMENT_CAPTURED ledger entries (expected exactly 1)")
                        .preview("amount=" + intent.getAmount()
                                + ", currency=" + intent.getCurrency())
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
                        ? "All " + succeededIntents.size() + " SUCCEEDED intents have exactly 1 capture entry"
                        : violations.size() + " SUCCEEDED intents have ≠1 PAYMENT_CAPTURED ledger entries")
                .suggestedRepairKey(passed ? null : "ledger.post_missing_capture_entry")
                .checkedAt(LocalDateTime.now())
                .build();
    }
}
