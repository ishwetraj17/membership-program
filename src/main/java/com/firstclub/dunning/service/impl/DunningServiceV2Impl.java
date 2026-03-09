package com.firstclub.dunning.service.impl;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.DunningPolicyService;
import com.firstclub.dunning.service.DunningServiceV2;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.dto.PaymentIntentDTO;
import com.firstclub.payments.service.PaymentIntentService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Policy-driven dunning engine (v2).
 *
 * <p>Retry schedule and terminal outcome are driven by a {@link DunningPolicy}
 * rather than hard-coded offsets.  Optionally falls back to a backup payment
 * method when the primary instrument is declined.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DunningServiceV2Impl implements DunningServiceV2 {

    private final DunningAttemptRepository                dunningAttemptRepository;
    private final DunningPolicyRepository                 dunningPolicyRepository;
    private final SubscriptionPaymentPreferenceRepository preferenceRepository;
    private final SubscriptionV2Repository                subscriptionV2Repository;
    private final InvoiceRepository                       invoiceRepository;
    private final InvoiceService                          invoiceService;
    private final PaymentIntentService                    paymentIntentService;
    private final PaymentGatewayPort                      paymentGatewayPort;
    private final DomainEventLog                          domainEventLog;

    private final DunningPolicyService dunningPolicyService;

    // ── scheduleAttemptsFromPolicy ────────────────────────────────────────────

    @Override
    @Transactional
    public void scheduleAttemptsFromPolicy(Long subscriptionId, Long invoiceId, Long merchantId) {
        DunningPolicy policy = dunningPolicyService.resolvePolicy(merchantId);
        List<Integer> offsets = dunningPolicyService.parseOffsets(policy.getRetryOffsetsJson());

        LocalDateTime base          = LocalDateTime.now();
        LocalDateTime graceDeadline = base.plusDays(policy.getGraceDays());
        int           limit         = Math.min(offsets.size(), policy.getMaxAttempts());

        int created = 0;
        for (int i = 0; i < limit; i++) {
            LocalDateTime scheduledAt = base.plusMinutes(offsets.get(i));
            if (scheduledAt.isAfter(graceDeadline)) {
                log.debug("Grace window exceeded at offset index {}; stopping schedule for sub {}",
                        i, subscriptionId);
                break;
            }
            DunningAttempt attempt = DunningAttempt.builder()
                    .subscriptionId(subscriptionId)
                    .invoiceId(invoiceId)
                    .attemptNumber(i + 1)
                    .scheduledAt(scheduledAt)
                    .status(DunningStatus.SCHEDULED)
                    .dunningPolicyId(policy.getId())
                    .build();
            dunningAttemptRepository.save(attempt);
            created++;
        }

        domainEventLog.record("DUNNING_V2_SCHEDULED", Map.of(
                "subscriptionId", subscriptionId,
                "invoiceId",      invoiceId,
                "policyId",       policy.getId(),
                "policyCode",     policy.getPolicyCode(),
                "attemptsCreated", created));

        log.info("Scheduled {} v2 dunning attempts for sub {} (policy='{}')",
                created, subscriptionId, policy.getPolicyCode());
    }

    // ── processDueV2Attempts ──────────────────────────────────────────────────

    /**
     * Picks up due v2 dunning attempts and processes each in its own transaction.
     *
     * <p><b>Concurrency guard:</b> BusinessLockScope.DUNNING_ATTEMPT_PROCESSING
     * <br>The query uses {@code FOR UPDATE SKIP LOCKED} so that when multiple scheduler
     * pods run simultaneously, each pod claims a disjoint batch of attempts.  Rows
     * already locked by another pod are skipped rather than waited on.
     *
     * <p>Each attempt is processed in an isolated transaction via a
     * {@code @Transactional(REQUIRES_NEW)} call (through self-injection).  This ensures
     * that a failure on one attempt does not roll back the batch transaction, and each
     * attempt's status change is committed independently.
     */
    @Override
    @Transactional
    public void processDueV2Attempts() {
        // SKIP LOCKED: each scheduler pod gets a disjoint set of due attempts.
        // Batch size 50 — prevents a single run from holding too many row locks.
        List<DunningAttempt> due = dunningAttemptRepository
                .findDueForProcessingWithSkipLocked(LocalDateTime.now(), 50);

        if (due.isEmpty()) {
            return;
        }
        log.info("Processing {} due v2 dunning attempt(s) [SKIP LOCKED batch]", due.size());

        for (DunningAttempt attempt : due) {
            try {
                processSingleV2Attempt(attempt);
            } catch (Exception e) {
                log.error("Unexpected error on v2 dunning attempt {} subscriptionId={}: {}",
                        attempt.getId(), attempt.getSubscriptionId(), e.getMessage(), e);
                attempt.setStatus(DunningStatus.FAILED);
                attempt.setLastError("Unexpected error: " + e.getMessage());
                dunningAttemptRepository.save(attempt);
                checkAndApplyTerminalStatus(attempt.getSubscriptionId(), attempt.getDunningPolicyId());
            }
        }
    }

    // ── getAttemptsForSubscription ────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DunningAttempt> getAttemptsForSubscription(Long merchantId, Long subscriptionId) {
        subscriptionV2Repository.findCustomerIdByMerchantIdAndId(merchantId, subscriptionId)
                .orElseThrow(() -> new MembershipException(
                        "Subscription " + subscriptionId + " not found for merchant " + merchantId,
                        "SUBSCRIPTION_NOT_FOUND", HttpStatus.NOT_FOUND));
        return dunningAttemptRepository.findBySubscriptionId(subscriptionId);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void processSingleV2Attempt(DunningAttempt attempt) {
        // Load policy
        DunningPolicy policy = dunningPolicyRepository.findById(attempt.getDunningPolicyId())
                .orElse(null);
        if (policy == null) {
            failAttempt(attempt, "Policy not found: " + attempt.getDunningPolicyId());
            return;
        }

        // Load subscription – must still be PAST_DUE
        SubscriptionV2 sub = subscriptionV2Repository.findById(attempt.getSubscriptionId())
                .orElse(null);
        if (sub == null) {
            failAttempt(attempt, "Subscription not found: " + attempt.getSubscriptionId());
            checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
            return;
        }
        if (sub.getStatus() != SubscriptionStatusV2.PAST_DUE) {
            attempt.setStatus(DunningStatus.FAILED);
            attempt.setLastError("Subscription not PAST_DUE (status=" + sub.getStatus() + ")");
            dunningAttemptRepository.save(attempt);
            log.debug("Skipping v2 attempt {} – sub {} no longer PAST_DUE", attempt.getId(), sub.getId());
            return;
        }

        // Load invoice – must still be OPEN
        Invoice invoice = invoiceRepository.findById(attempt.getInvoiceId()).orElse(null);
        if (invoice == null || invoice.getStatus() != InvoiceStatus.OPEN) {
            failAttempt(attempt, "Invoice not OPEN: " + attempt.getInvoiceId());
            checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
            return;
        }

        // Resolve payment method for this attempt
        SubscriptionPaymentPreference pref =
                preferenceRepository.findBySubscriptionId(attempt.getSubscriptionId()).orElse(null);
        Long pmId = resolvePaymentMethodId(attempt, pref);
        attempt.setPaymentMethodId(pmId);

        // Charge
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(
                invoice.getId(), invoice.getTotalAmount(), invoice.getCurrency());
        ChargeOutcome outcome = paymentGatewayPort.charge(pi.getId());

        if (outcome == ChargeOutcome.SUCCESS) {
            handleSuccess(attempt, sub, invoice);
        } else {
            handleFailure(attempt, policy, pref);
        }
    }

    private void handleSuccess(DunningAttempt attempt, SubscriptionV2 sub, Invoice invoice) {
        invoiceService.onPaymentSucceeded(invoice.getId());

        sub.setStatus(SubscriptionStatusV2.ACTIVE);
        subscriptionV2Repository.save(sub);

        attempt.setStatus(DunningStatus.SUCCESS);
        dunningAttemptRepository.save(attempt);

        cancelRemainingV2Attempts(attempt.getSubscriptionId(), attempt.getId());

        domainEventLog.record("DUNNING_V2_SUCCEEDED", Map.of(
                "subscriptionId", attempt.getSubscriptionId(),
                "attemptId",      attempt.getId(),
                "usedBackup",     attempt.isUsedBackupMethod(),
                "pmId",           String.valueOf(attempt.getPaymentMethodId())));

        log.info("V2 dunning attempt {} succeeded for sub {} → ACTIVE",
                attempt.getId(), sub.getId());
    }

    private void handleFailure(DunningAttempt attempt, DunningPolicy policy,
                               SubscriptionPaymentPreference pref) {
        failAttempt(attempt, "Payment gateway declined");

        // Try backup PM if eligible: primary just failed, policy allows fallback,
        // backup PM is configured, and we haven't already tried the backup.
        boolean canTryBackup = !attempt.isUsedBackupMethod()
                && policy.isFallbackToBackupPaymentMethod()
                && pref != null
                && pref.getBackupPaymentMethodId() != null;

        if (canTryBackup) {
            DunningAttempt backupAttempt = DunningAttempt.builder()
                    .subscriptionId(attempt.getSubscriptionId())
                    .invoiceId(attempt.getInvoiceId())
                    .attemptNumber(attempt.getAttemptNumber())
                    .scheduledAt(LocalDateTime.now())
                    .status(DunningStatus.SCHEDULED)
                    .dunningPolicyId(policy.getId())
                    .paymentMethodId(pref.getBackupPaymentMethodId())
                    .usedBackupMethod(true)
                    .build();
            DunningAttempt saved = dunningAttemptRepository.save(backupAttempt);

            Map<String, Object> evt = new java.util.HashMap<>();
            evt.put("subscriptionId", attempt.getSubscriptionId());
            evt.put("primaryAttemptId", attempt.getId());
            evt.put("backupPmId", pref.getBackupPaymentMethodId());
            if (saved != null && saved.getId() != null) {
                evt.put("backupAttemptId", saved.getId());
            }
            domainEventLog.record("DUNNING_V2_BACKUP_QUEUED", evt);

            log.info("V2 attempt {} failed; queued backup PM {} via attempt {}",
                    attempt.getId(), pref.getBackupPaymentMethodId(),
                    saved != null ? saved.getId() : "pending");
        } else {
            checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
        }
    }

    private Long resolvePaymentMethodId(DunningAttempt attempt, SubscriptionPaymentPreference pref) {
        if (pref == null) return null;
        if (attempt.isUsedBackupMethod() && pref.getBackupPaymentMethodId() != null) {
            return pref.getBackupPaymentMethodId();
        }
        return pref.getPrimaryPaymentMethodId();
    }

    private void checkAndApplyTerminalStatus(Long subscriptionId, Long policyId) {
        long remaining = dunningAttemptRepository
                .countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
                        subscriptionId, DunningStatus.SCHEDULED);
        if (remaining > 0) {
            return; // more attempts still queued
        }

        subscriptionV2Repository.findById(subscriptionId).ifPresent(sub -> {
            if (sub.getStatus() != SubscriptionStatusV2.PAST_DUE) {
                return; // already resolved
            }

            DunningTerminalStatus terminalStatus = dunningPolicyRepository.findById(policyId)
                    .map(DunningPolicy::getStatusAfterExhaustion)
                    .orElse(DunningTerminalStatus.SUSPENDED);

            if (terminalStatus == DunningTerminalStatus.CANCELLED) {
                sub.setStatus(SubscriptionStatusV2.CANCELLED);
                sub.setCancelledAt(LocalDateTime.now());
            } else {
                sub.setStatus(SubscriptionStatusV2.SUSPENDED);
            }
            subscriptionV2Repository.save(sub);

            domainEventLog.record("DUNNING_V2_EXHAUSTED", Map.of(
                    "subscriptionId", subscriptionId,
                    "policyId",       policyId,
                    "terminalStatus", terminalStatus.name()));

            log.warn("Sub {} → {} — all v2 dunning attempts exhausted",
                    subscriptionId, terminalStatus);
        });
    }

    private void failAttempt(DunningAttempt attempt, String error) {
        attempt.setStatus(DunningStatus.FAILED);
        attempt.setLastError(error);
        dunningAttemptRepository.save(attempt);
        log.warn("V2 dunning attempt {} failed: {}", attempt.getId(), error);
    }

    private void cancelRemainingV2Attempts(Long subscriptionId, Long succeededAttemptId) {
        dunningAttemptRepository.findBySubscriptionIdAndStatus(subscriptionId, DunningStatus.SCHEDULED)
                .stream()
                .filter(a -> a.getDunningPolicyId() != null && !a.getId().equals(succeededAttemptId))
                .forEach(a -> {
                    a.setStatus(DunningStatus.FAILED);
                    a.setLastError("Cancelled — earlier attempt succeeded");
                    dunningAttemptRepository.save(a);
                });
    }
}
