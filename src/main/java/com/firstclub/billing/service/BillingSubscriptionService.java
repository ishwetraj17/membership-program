package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.dto.SubscriptionV2Response;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.*;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Orchestrates the v2 subscription creation flow:
 *
 * <ol>
 *   <li>Create {@link Subscription} with status {@code PENDING}.</li>
 *   <li>Create an OPEN {@link com.firstclub.billing.entity.Invoice} for the first period.</li>
 *   <li>Apply any available credit-note balance.</li>
 *   <li>If {@code amountDue > 0}: create a {@link com.firstclub.payments.entity.PaymentIntent}
 *       and return the {@code clientSecret} for front-end confirmation.</li>
 *   <li>If {@code amountDue == 0}: immediately mark the invoice PAID and the subscription ACTIVE.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingSubscriptionService {

    private final SubscriptionRepository            subscriptionRepository;
    private final SubscriptionHistoryRepository     historyRepository;
    private final MembershipPlanRepository          planRepository;
    private final UserRepository                    userRepository;
    private final InvoiceService                    invoiceService;
    private final PaymentIntentService              paymentIntentService;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a subscription, invoice, and (if needed) a PaymentIntent atomically.
     *
     * @param request contains {@code userId}, {@code planId}, and {@code autoRenewal}
     * @return enriched response with subscription, invoice, and payment details
     */
    @Transactional
    public SubscriptionV2Response createSubscriptionV2(SubscriptionRequestDTO request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new MembershipException(
                        "User not found: " + request.getUserId(), "USER_NOT_FOUND"));

        MembershipPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> new MembershipException(
                        "Plan not found: " + request.getPlanId(), "PLAN_NOT_FOUND"));

        if (!Boolean.TRUE.equals(plan.getIsActive())) {
            throw new MembershipException("Plan is not active: " + request.getPlanId(), "INACTIVE_PLAN");
        }

        // 1. Create subscription with PENDING status
        LocalDateTime now     = LocalDateTime.now();
        LocalDateTime endDate = now.plusMonths(plan.getDurationInMonths());

        Subscription subscription = Subscription.builder()
                .user(user)
                .plan(plan)
                .status(Subscription.SubscriptionStatus.PENDING)
                .startDate(now)
                .endDate(endDate)
                .nextBillingDate(endDate)
                .nextRenewalAt(endDate)          // managed-renewal engine picks this up when ACTIVE
                .paidAmount(plan.getPrice())
                .autoRenewal(Boolean.TRUE.equals(request.getAutoRenewal()))
                .build();
        subscription = subscriptionRepository.save(subscription);

        SubscriptionHistory history = SubscriptionHistory.builder()
                .subscription(subscription)
                .eventType(SubscriptionHistory.EventType.CREATED)
                .newPlanId(plan.getId())
                .oldStatus(null)
                .newStatus(Subscription.SubscriptionStatus.PENDING)
                .reason("V2 subscription creation — awaiting payment")
                .changedByUserId(user.getId())
                .build();
        historyRepository.save(history);

        log.info("Created PENDING subscription {} for user {} plan {}", subscription.getId(), user.getId(), plan.getId());

        // 2. Create OPEN invoice for the first period
        InvoiceDTO invoice = invoiceService.createInvoiceForSubscription(
                user.getId(), subscription.getId(), plan.getId(), now, endDate);

        BigDecimal amountDue = invoice.getTotalAmount();

        // 3a. Credits covered the full amount — activate immediately
        if (amountDue.compareTo(BigDecimal.ZERO) <= 0) {
            invoiceService.onPaymentSucceeded(invoice.getId());
            log.info("Invoice {} fully covered by credits — subscription {} activated immediately",
                    invoice.getId(), subscription.getId());
            // Reload to get updated status
            Subscription activated = subscriptionRepository.findById(subscription.getId()).orElseThrow();
            return SubscriptionV2Response.builder()
                    .subscriptionId(subscription.getId())
                    .invoiceId(invoice.getId())
                    .paymentIntentId(null)
                    .clientSecret(null)
                    .amountDue(BigDecimal.ZERO)
                    .currency("INR")
                    .status(activated.getStatus())
                    .build();
        }

        // 3b. Create a PaymentIntent for the outstanding amount
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(invoice.getId(), amountDue, "INR");

        log.info("Created PaymentIntent {} for invoice {} (amount={})", pi.getId(), invoice.getId(), amountDue);

        return SubscriptionV2Response.builder()
                .subscriptionId(subscription.getId())
                .invoiceId(invoice.getId())
                .paymentIntentId(pi.getId())
                .clientSecret(pi.getClientSecret())
                .amountDue(amountDue)
                .currency("INR")
                .status(Subscription.SubscriptionStatus.PENDING)
                .build();
    }
}
