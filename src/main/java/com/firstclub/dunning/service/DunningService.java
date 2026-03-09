package com.firstclub.dunning.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.Subscription.SubscriptionStatus;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Processes dunning attempts scheduled by {@link RenewalService} when a renewal
 * payment fails.
 *
 * <p>The retry schedule is: +1 h, +6 h, +24 h, +3 d.  On the final failure the
 * subscription is moved to {@code SUSPENDED}.  Any successful retry
 * reactivates the subscription, marks the invoice {@code PAID}, and cancels
 * remaining scheduled attempts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DunningService {

    private final DunningAttemptRepository dunningAttemptRepository;
    private final SubscriptionRepository   subscriptionRepository;
    private final InvoiceRepository        invoiceRepository;
    private final InvoiceService           invoiceService;
    private final PaymentIntentService     paymentIntentService;
    private final PaymentGatewayPort       paymentGatewayPort;

    // -------------------------------------------------------------------------
    // Batch processor — called by DunningScheduler
    // -------------------------------------------------------------------------

    @Transactional
    public void processDueAttempts() {
        List<DunningAttempt> due = dunningAttemptRepository
                .findByStatusAndScheduledAtLessThanEqual(DunningStatus.SCHEDULED, LocalDateTime.now());

        if (due.isEmpty()) {
            return;
        }
        log.info("Processing {} due dunning attempt(s)", due.size());

        for (DunningAttempt attempt : due) {
            try {
                processSingleAttempt(attempt);
            } catch (Exception e) {
                log.error("Unexpected error on dunning attempt {}: {}", attempt.getId(), e.getMessage(), e);
                attempt.setStatus(DunningStatus.FAILED);
                attempt.setLastError("Unexpected error: " + e.getMessage());
                dunningAttemptRepository.save(attempt);
                checkAndSuspendIfExhausted(attempt.getSubscriptionId());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cancellation helper — call when a subscription is manually cancelled
    // -------------------------------------------------------------------------

    @Transactional
    public void cancelPendingAttempts(Long subscriptionId) {
        List<DunningAttempt> scheduled = dunningAttemptRepository
                .findBySubscriptionIdAndStatus(subscriptionId, DunningStatus.SCHEDULED);
        scheduled.forEach(a -> {
            a.setStatus(DunningStatus.FAILED);
            a.setLastError("Cancelled — subscription was manually cancelled");
        });
        dunningAttemptRepository.saveAll(scheduled);
        log.info("Cancelled {} pending dunning attempts for sub {}", scheduled.size(), subscriptionId);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void processSingleAttempt(DunningAttempt attempt) {
        // Guard: subscription must still be PAST_DUE
        Subscription sub = subscriptionRepository.findById(attempt.getSubscriptionId()).orElse(null);
        if (sub == null) {
            failAttempt(attempt, "Subscription not found: " + attempt.getSubscriptionId());
            checkAndSuspendIfExhausted(attempt.getSubscriptionId());
            return;
        }
        if (sub.getStatus() != SubscriptionStatus.PAST_DUE) {
            // Already resolved by another attempt or manually
            attempt.setStatus(DunningStatus.FAILED);
            attempt.setLastError("Subscription not in PAST_DUE (status=" + sub.getStatus() + ")");
            dunningAttemptRepository.save(attempt);
            log.debug("Skipping dunning attempt {} — sub {} no longer PAST_DUE", attempt.getId(), sub.getId());
            return;
        }

        // Guard: invoice must still be OPEN
        Invoice invoice = invoiceRepository.findById(attempt.getInvoiceId()).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.OPEN) {
            failAttempt(attempt, "Invoice not OPEN: " + attempt.getInvoiceId());
            checkAndSuspendIfExhausted(sub);
            return;
        }

        // Attempt payment via a fresh PaymentIntent
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(
                invoice.getId(), invoice.getTotalAmount(), invoice.getCurrency());

        ChargeOutcome outcome = paymentGatewayPort.charge(pi.getId());

        if (outcome == ChargeOutcome.SUCCESS) {
            invoiceService.onPaymentSucceeded(invoice.getId());

            // Refresh after activateSubscription() ran inside onPaymentSucceeded
            Subscription fresh = subscriptionRepository.findById(sub.getId()).orElseThrow();
            fresh.setGraceUntil(null);
            if (invoice.getPeriodEnd() != null) {
                fresh.setEndDate(invoice.getPeriodEnd());
                fresh.setNextRenewalAt(invoice.getPeriodEnd());
                fresh.setNextBillingDate(invoice.getPeriodEnd());
            }
            subscriptionRepository.save(fresh);

            attempt.setStatus(DunningStatus.SUCCESS);
            dunningAttemptRepository.save(attempt);

            // Cancel remaining attempts for this subscription
            cancelRemainingAttempts(sub.getId(), attempt.getId());

            log.info("Dunning attempt {} succeeded for sub {} → ACTIVE", attempt.getId(), sub.getId());
        } else {
            failAttempt(attempt, "Payment gateway declined");
            checkAndSuspendIfExhausted(sub);
        }
    }

    private void failAttempt(DunningAttempt attempt, String error) {
        attempt.setStatus(DunningStatus.FAILED);
        attempt.setLastError(error);
        dunningAttemptRepository.save(attempt);
        log.warn("Dunning attempt {} failed: {}", attempt.getId(), error);
    }

    private void checkAndSuspendIfExhausted(Subscription sub) {
        long remaining = dunningAttemptRepository
                .countBySubscriptionIdAndStatus(sub.getId(), DunningStatus.SCHEDULED);
        if (remaining == 0) {
            sub.setStatus(SubscriptionStatus.SUSPENDED);
            sub.setGraceUntil(null);
            subscriptionRepository.save(sub);
            log.warn("Sub {} → SUSPENDED — all dunning attempts exhausted", sub.getId());
        }
    }

    private void checkAndSuspendIfExhausted(Long subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(this::checkAndSuspendIfExhausted);
    }

    private void cancelRemainingAttempts(Long subscriptionId, Long succeededAttemptId) {
        List<DunningAttempt> remaining = dunningAttemptRepository
                .findBySubscriptionIdAndStatus(subscriptionId, DunningStatus.SCHEDULED);
        remaining.stream()
                .filter(a -> !a.getId().equals(succeededAttemptId))
                .forEach(a -> {
                    a.setStatus(DunningStatus.FAILED);
                    a.setLastError("Cancelled — earlier attempt succeeded");
                    dunningAttemptRepository.save(a);
                });
    }
}
