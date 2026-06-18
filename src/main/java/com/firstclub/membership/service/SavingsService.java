package com.firstclub.membership.service;

import com.firstclub.membership.dto.SavingsSummaryDTO;
import com.firstclub.membership.service.benefit.BenefitEvaluation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Records and reports realised member savings via an append-only, auditable ledger.
 */
public interface SavingsService {

    /** Record the savings realised by a placed order: discounts, waived fees and coupon. */
    void recordOrderSavings(Long userId, Long orderId, BenefitEvaluation evaluation,
                            BigDecimal couponDiscount, LocalDateTime occurredAt);

    /** Record the savings from an introductory offer applied to a subscription's first period. */
    void recordIntroSavings(Long userId, Long subscriptionId, BigDecimal amount, LocalDateTime occurredAt);

    SavingsSummaryDTO getUserSavings(Long userId);
}
