package com.firstclub.dunning.service;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.entity.SubscriptionHistory;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the renewal lifecycle for auto-renewing subscriptions.
 *
 * <p>Called by {@link RenewalScheduler} for subscriptions whose
 * {@code next_renewal_at} timestamp has elapsed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalService {

    private final SubscriptionRepository       subscriptionRepository;
    private final SubscriptionHistoryRepository historyRepository;
    private final InvoiceService               invoiceService;
    private final PaymentIntentService         paymentIntentService;
    private final DunningAttemptRepository     dunningAttemptRepository;
    private final PaymentGatewayPort           paymentGatewayPort;

    /** Retry offsets from the moment the renewal charge fails. */
    static final List<Duration> DUNNING_OFFSETS = List.of(
            Duration.ofHours(1),
            Duration.ofHours(6),
            Duration.ofHours(24),
            Duration.ofDays(3)
    );

    // -------------------------------------------------------------------------
    // Query — used by the scheduler
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Subscription> findDueForRenewal() {
        return subscriptionRepository.findDueForRenewal(LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Core renewal logic
    // -------------------------------------------------------------------------

    @Transactional
    public void processRenewal(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription not found: " + subscriptionId,
                        "SUBSCRIPTION_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            log.warn("Skipping renewal for sub {} — not ACTIVE ({})", subscriptionId, sub.getStatus());
            return;
        }

        // --- cancel-at-period-end path ---
        if (Boolean.TRUE.equals(sub.getCancelAtPeriodEnd())) {
            cancelAtPeriodEnd(sub);
            return;
        }

        // --- create the next-period invoice ---
        LocalDateTime periodStart = sub.getEndDate();
        LocalDateTime periodEnd   = periodStart.plusMonths(sub.getPlan().getDurationInMonths());

        InvoiceDTO invoice = invoiceService.createInvoiceForSubscription(
                sub.getUser().getId(), sub.getId(), sub.getPlan().getId(),
                periodStart, periodEnd);

        log.info("Renewal invoice {} created for sub {} — amount={} period=[{},{}]",
                invoice.getId(), sub.getId(), invoice.getTotalAmount(), periodStart, periodEnd);

        // --- credits covered full amount — no payment needed ---
        if (invoice.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            invoiceService.onPaymentSucceeded(invoice.getId());
            advanceSubscriptionPeriod(sub.getId(), periodEnd);
            log.info("Renewal via full credit for sub {} → period extended to {}", sub.getId(), periodEnd);
            return;
        }

        // --- create PI and attempt charge ---
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(
                invoice.getId(), invoice.getTotalAmount(), invoice.getCurrency());

        ChargeOutcome outcome = paymentGatewayPort.charge(pi.getId());

        if (outcome == ChargeOutcome.SUCCESS) {
            invoiceService.onPaymentSucceeded(invoice.getId());
            advanceSubscriptionPeriod(sub.getId(), periodEnd);
            recordHistory(sub, SubscriptionHistory.EventType.RENEWED, SubscriptionStatus.ACTIVE,
                    "Auto-renewal succeeded — period extended to " + periodEnd);
            log.info("Renewal succeeded for sub {} → period extended to {}", sub.getId(), periodEnd);
        } else {
            // Mark PAST_DUE and schedule dunning
            sub.setStatus(SubscriptionStatus.PAST_DUE);
            sub.setGraceUntil(LocalDateTime.now().plusDays(7));
            subscriptionRepository.save(sub);
            scheduleDunning(sub.getId(), invoice.getId(), LocalDateTime.now());
            log.warn("Renewal charge failed for sub {} → PAST_DUE, {} dunning attempts scheduled",
                    sub.getId(), DUNNING_OFFSETS.size());
        }
    }

    // -------------------------------------------------------------------------
    // Cancel-at-period-end
    // -------------------------------------------------------------------------

    @Transactional
    public void setCancelAtPeriodEnd(Long subscriptionId, boolean value) {
        Subscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription not found: " + subscriptionId, "SUBSCRIPTION_NOT_FOUND"));
        sub.setCancelAtPeriodEnd(value);
        subscriptionRepository.save(sub);
        log.info("Sub {} cancelAtPeriodEnd={}", subscriptionId, value);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void cancelAtPeriodEnd(Subscription sub) {
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(LocalDateTime.now());
        sub.setCancellationReason("cancel_at_period_end");
        sub.setCancelAtPeriodEnd(false);
        subscriptionRepository.save(sub);
        recordHistory(sub, SubscriptionHistory.EventType.CANCELLED, SubscriptionStatus.CANCELLED,
                "Cancel-at-period-end flag was set");
        log.info("Sub {} cancelled at period end", sub.getId());
    }

    private void advanceSubscriptionPeriod(Long subscriptionId, LocalDateTime periodEnd) {
        Subscription fresh = subscriptionRepository.findById(subscriptionId).orElseThrow();
        fresh.setEndDate(periodEnd);
        fresh.setNextRenewalAt(periodEnd);
        fresh.setNextBillingDate(periodEnd);
        fresh.setGraceUntil(null);
        subscriptionRepository.save(fresh);
    }

    private void scheduleDunning(Long subscriptionId, Long invoiceId, LocalDateTime failedAt) {
        List<DunningAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < DUNNING_OFFSETS.size(); i++) {
            attempts.add(DunningAttempt.builder()
                    .subscriptionId(subscriptionId)
                    .invoiceId(invoiceId)
                    .attemptNumber(i + 1)
                    .scheduledAt(failedAt.plus(DUNNING_OFFSETS.get(i)))
                    .status(DunningAttempt.DunningStatus.SCHEDULED)
                    .build());
        }
        dunningAttemptRepository.saveAll(attempts);
    }

    private void recordHistory(Subscription sub,
                               SubscriptionHistory.EventType eventType,
                               SubscriptionStatus newStatus,
                               String reason) {
        historyRepository.save(SubscriptionHistory.builder()
                .subscription(sub)
                .eventType(eventType)
                .oldStatus(sub.getStatus())
                .newStatus(newStatus)
                .newPlanId(sub.getPlan() != null ? sub.getPlan().getId() : null)
                .reason(reason)
                .build());
    }
}
