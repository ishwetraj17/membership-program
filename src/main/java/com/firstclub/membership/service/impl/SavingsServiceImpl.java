package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.SavingsSummaryDTO;
import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.ProductCategory;
import com.firstclub.membership.entity.SavingsLedgerEntry;
import com.firstclub.membership.entity.SavingsType;
import com.firstclub.membership.repository.SavingsLedgerRepository;
import com.firstclub.membership.service.SavingsService;
import com.firstclub.membership.service.benefit.BenefitEvaluation;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes one ledger row per realised saving and aggregates the ledger for the savings tracker.
 * Each row is attributed to a savings type and (for category discounts) a product category, and
 * tied to its source order/subscription — so every reported total is auditable.
 */
@Service
@RequiredArgsConstructor
public class SavingsServiceImpl implements SavingsService {

    private static final String SOURCE_ORDER = "ORDER";
    private static final String SOURCE_SUBSCRIPTION = "SUBSCRIPTION";

    private final SavingsLedgerRepository ledgerRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Override
    @Transactional
    public void recordOrderSavings(Long userId, Long orderId, BenefitEvaluation evaluation,
                                   BigDecimal couponDiscount, LocalDateTime occurredAt) {
        // Member discounts, split into whole-cart (MEMBERSHIP) vs category-targeted (CATEGORY).
        for (BenefitEvaluation.DiscountLine line : evaluation.getDiscountLines()) {
            if (line.amount() == null || line.amount().signum() <= 0) continue;
            SavingsType type = line.categoryTargeted() ? SavingsType.CATEGORY_DISCOUNT : SavingsType.MEMBERSHIP_DISCOUNT;
            record(userId, type, line.category(), line.amount(), SOURCE_ORDER, orderId, occurredAt);
        }
        // Waived fees → the original (now saved) fee amount.
        for (FeeType fee : evaluation.getWaivedFees()) {
            BigDecimal saved = evaluation.getOriginalFees().getOrDefault(fee, BigDecimal.ZERO);
            if (saved.signum() > 0) {
                record(userId, SavingsType.forFee(fee), null, saved, SOURCE_ORDER, orderId, occurredAt);
            }
        }
        // Coupon.
        if (couponDiscount != null && couponDiscount.signum() > 0) {
            record(userId, SavingsType.COUPON, null, couponDiscount, SOURCE_ORDER, orderId, occurredAt);
        }
    }

    @Override
    @Transactional
    public void recordIntroSavings(Long userId, Long subscriptionId, BigDecimal amount, LocalDateTime occurredAt) {
        if (amount == null || amount.signum() <= 0) return;
        record(userId, SavingsType.INTRO_OFFER, null, amount, SOURCE_SUBSCRIPTION, subscriptionId, occurredAt);
    }

    private void record(Long userId, SavingsType type, ProductCategory category, BigDecimal amount,
                        String sourceType, Long sourceId, LocalDateTime occurredAt) {
        ledgerRepository.save(SavingsLedgerEntry.builder()
                .userId(userId)
                .savingsType(type)
                .productCategory(category)
                .amount(amount)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .occurredAt(occurredAt)
                .build());
        meterRegistry.counter("membership.savings.recorded", "type", type.name()).increment(amount.doubleValue());
    }

    @Override
    @Transactional(readOnly = true)
    public SavingsSummaryDTO getUserSavings(Long userId) {
        LocalDateTime monthStart = LocalDateTime.now(clock).withDayOfMonth(1)
                .toLocalDate().atStartOfDay();

        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (Object[] row : ledgerRepository.savingsByType(userId)) {
            byType.put(((SavingsType) row[0]).name(), (BigDecimal) row[1]);
        }
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (Object[] row : ledgerRepository.savingsByCategory(userId)) {
            byCategory.put(((ProductCategory) row[0]).name(), (BigDecimal) row[1]);
        }

        return SavingsSummaryDTO.builder()
                .userId(userId)
                .lifetimeSavings(ledgerRepository.lifetimeSavings(userId))
                .monthlySavings(ledgerRepository.savingsSince(userId, monthStart))
                .byBenefitType(byType)
                .byCategory(byCategory)
                .build();
    }
}
