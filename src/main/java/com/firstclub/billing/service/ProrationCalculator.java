package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.dto.ProratedPreviewResponse;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.entity.MembershipPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes proration lines when a user switches plans mid-cycle.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Credit = (remainingDays / totalDaysInPeriod) × currentPlanPrice  — stored as a
 *       negative {@link InvoiceLineType#PRORATION} line.</li>
 *   <li>New plan full charge — stored as a positive {@link InvoiceLineType#PLAN_CHARGE} line.</li>
 * </ol>
 *
 * <p>The pure {@link #compute compute()} method accepts primitive values so unit tests
 * can exercise the maths without a Spring context.
 */
@Service
@RequiredArgsConstructor
public class ProrationCalculator {

    private final SubscriptionRepository subscriptionRepository;
    private final MembershipPlanRepository planRepository;

    // -------------------------------------------------------------------------
    // Pure computation — no DB access; directly unit-testable
    // -------------------------------------------------------------------------

    /**
     * @param currentPlanPrice price of the existing plan for its full period
     * @param totalDays        total calendar days in the current billing period (must be > 0)
     * @param remainingDays    days left in the current period (clamped to [0, totalDays])
     * @param newPlanPrice     full-period price of the target plan
     * @return ordered list: [PRORATION credit (if any), PLAN_CHARGE]
     */
    public List<InvoiceLineDTO> compute(
            BigDecimal currentPlanPrice,
            long totalDays,
            long remainingDays,
            BigDecimal newPlanPrice) {

        List<InvoiceLineDTO> lines = new ArrayList<>();

        long effectiveRemaining = Math.max(0, Math.min(remainingDays, totalDays));

        if (totalDays > 0 && effectiveRemaining > 0) {
            BigDecimal credit = currentPlanPrice
                    .multiply(BigDecimal.valueOf(effectiveRemaining))
                    .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP)
                    .negate();  // negative = credit back to customer

            lines.add(InvoiceLineDTO.builder()
                    .lineType(InvoiceLineType.PRORATION)
                    .description("Credit for " + effectiveRemaining + " unused day(s) on current plan")
                    .amount(credit)
                    .build());
        }

        lines.add(InvoiceLineDTO.builder()
                .lineType(InvoiceLineType.PLAN_CHARGE)
                .description("New plan charge")
                .amount(newPlanPrice)
                .build());

        return lines;
    }

    // -------------------------------------------------------------------------
    // DB-backed preview
    // -------------------------------------------------------------------------

    /**
     * Loads the subscription and target plan from the database, then delegates to
     * {@link #compute} to build the proration preview.
     *
     * @param subscriptionId ID of the active subscription being changed
     * @param newPlanId      ID of the plan the customer wants to switch to
     */
    @Transactional(readOnly = true)
    public ProratedPreviewResponse preview(Long subscriptionId, Long newPlanId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription not found: " + subscriptionId, "SUBSCRIPTION_NOT_FOUND"));

        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> new MembershipException(
                        "Plan not found: " + newPlanId, "PLAN_NOT_FOUND"));

        MembershipPlan currentPlan = sub.getPlan();

        LocalDateTime now = LocalDateTime.now();
        long totalDays     = ChronoUnit.DAYS.between(sub.getStartDate(), sub.getEndDate());
        long remainingDays = Math.max(0, ChronoUnit.DAYS.between(now, sub.getEndDate()));

        List<InvoiceLineDTO> lines = compute(
                currentPlan.getPrice(), totalDays, remainingDays, newPlan.getPrice());

        BigDecimal total     = lines.stream()
                .map(InvoiceLineDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amountDue = total.max(BigDecimal.ZERO);

        return ProratedPreviewResponse.builder()
                .currentPlanId(currentPlan.getId())
                .newPlanId(newPlanId)
                .lines(lines)
                .total(total)
                .amountDue(amountDue)
                .build();
    }
}
