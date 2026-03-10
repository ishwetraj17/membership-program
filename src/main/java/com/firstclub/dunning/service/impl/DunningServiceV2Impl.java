package com.firstclub.dunning.service.impl;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.dunning.DunningDecision;
import com.firstclub.dunning.DunningDecisionAuditService;
import com.firstclub.dunning.classification.FailureCategory;
import com.firstclub.dunning.classification.FailureCodeClassifier;
import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.entity.DunningAttempt.DunningStatus;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.port.PaymentGatewayPort;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeOutcome;
import com.firstclub.dunning.port.PaymentGatewayPort.ChargeResult;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import com.firstclub.dunning.service.DunningPolicyService;
import com.firstclub.dunning.service.DunningServiceV2;
import com.firstclub.dunning.strategy.BackupPaymentMethodSelector;
import com.firstclub.dunning.strategy.DunningStrategyService;
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
    private final DunningPolicyService                    dunningPolicyService;

    // ── Phase 16 — failure-code intelligence ─────────────────────────────────
    private final FailureCodeClassifier       failureCodeClassifier;
    private final DunningStrategyService      dunningStrategyService;
    private final BackupPaymentMethodSelector backupSelector;
    private final DunningDecisionAuditService decisionAuditService;

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

        // Charge — use chargeWithCode so the failure code is available for classification
        PaymentIntentDTO pi = paymentIntentService.createForInvoice(
                invoice.getId(), invoice.getTotalAmount(), invoice.getCurrency());
        ChargeResult chargeResult = paymentGatewayPort.chargeWithCode(pi.getId());

        // Persist the raw failure code before branching
        if (!chargeResult.isSuccess() && chargeResult.failureCode() != null) {
            attempt.setFailureCode(chargeResult.failureCode());
        }

        if (chargeResult.isSuccess()) {
            handleSuccess(attempt, sub, invoice);
        } else {
            handleFailureWithIntelligence(attempt, policy, pref, chargeResult.failureCode());
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

    /**
     * Intelligence-aware failure handler (Phase 16).
     *
     * <p>Classifies the gateway failure code, asks the strategy engine for a
     * decision, audits the decision onto the attempt, and then acts:
     * <ul>
     *   <li>{@link DunningDecision#STOP} — cancel remaining attempts, apply terminal status.</li>
     *   <li>{@link DunningDecision#RETRY_WITH_BACKUP} — queue an immediate backup-PM attempt.</li>
     *   <li>{@link DunningDecision#EXHAUSTED} — apply terminal status.</li>
     *   <li>{@link DunningDecision#RETRY} — no special action; scheduled queue proceeds.</li>
     * </ul>
     */
    private void handleFailureWithIntelligence(DunningAttempt attempt, DunningPolicy policy,
                                               SubscriptionPaymentPreference pref,
                                               String failureCode) {
        failAttempt(attempt, "Payment declined: " + failureCode);

        FailureCategory category = failureCodeClassifier.classify(failureCode);
        DunningDecision decision  = dunningStrategyService.decide(attempt, category, policy);

        boolean stoppedEarly = (decision == DunningDecision.STOP);
        String  reason       = buildDecisionReason(decision, category, failureCode);

        // Audit the decision onto the attempt row
        decisionAuditService.record(attempt.getId(), decision, reason, category, stoppedEarly);

        switch (decision) {
            case STOP -> {
                cancelRemainingV2Attempts(attempt.getSubscriptionId(), attempt.getId());
                checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
                domainEventLog.record("DUNNING_V2_STOPPED_EARLY", Map.of(
                        "subscriptionId", attempt.getSubscriptionId(),
                        "attemptId",      attempt.getId(),
                        "failureCode",    failureCode != null ? failureCode : "",
                        "category",       category.name()));
                log.warn("V2 dunning stopped early for sub {} — non-retryable code='{}' category={}",
                        attempt.getSubscriptionId(), failureCode, category);
            }
            case RETRY_WITH_BACKUP -> {
                Long backupPmId = backupSelector.findBackup(attempt.getSubscriptionId())
                        .orElse(pref != null ? pref.getBackupPaymentMethodId() : null);
                if (backupPmId != null) {
                    DunningAttempt backupAttempt = DunningAttempt.builder()
                            .subscriptionId(attempt.getSubscriptionId())
                            .invoiceId(attempt.getInvoiceId())
                            .attemptNumber(attempt.getAttemptNumber())
                            .scheduledAt(LocalDateTime.now())
                            .status(DunningStatus.SCHEDULED)
                            .dunningPolicyId(policy.getId())
                            .paymentMethodId(backupPmId)
                            .usedBackupMethod(true)
                            .build();
                    DunningAttempt saved = dunningAttemptRepository.save(backupAttempt);

                    Map<String, Object> evt = new java.util.HashMap<>();
                    evt.put("subscriptionId",  attempt.getSubscriptionId());
                    evt.put("primaryAttemptId", attempt.getId());
                    evt.put("backupPmId",       backupPmId);
                    evt.put("failureCategory",  category.name());
                    if (saved != null && saved.getId() != null) {
                        evt.put("backupAttemptId", saved.getId());
                    }
                    domainEventLog.record("DUNNING_V2_BACKUP_QUEUED", evt);
                    log.info("V2 attempt {} failed ({}); queued backup PM {} via attempt {}",
                            attempt.getId(), category, backupPmId,
                            saved != null ? saved.getId() : "pending");
                } else {
                    // Strategy said RETRY_WITH_BACKUP but no backup reachable — stop
                    checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
                }
            }
            case EXHAUSTED -> checkAndApplyTerminalStatus(attempt.getSubscriptionId(), policy.getId());
            case RETRY -> { /* scheduled queue will process the next attempt */ }
        }
    }

    private String buildDecisionReason(DunningDecision decision, FailureCategory category,
                                       String failureCode) {
        return switch (decision) {
            case STOP           -> "Non-retryable failure: code=" + failureCode + " category=" + category;
            case RETRY_WITH_BACKUP -> "Instrument structurally declined (" + category + "); switching to backup PM";
            case EXHAUSTED      -> "All scheduled dunning attempts exhausted";
            case RETRY          -> "Retryable failure (" + category + "); next scheduled attempt will proceed";
        };
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

    // ── forceRetry ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DunningAttempt forceRetry(Long merchantId, Long attemptId) {
        DunningAttempt source = dunningAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new MembershipException(
                        "Dunning attempt " + attemptId + " not found",
                        "DUNNING_ATTEMPT_NOT_FOUND", HttpStatus.NOT_FOUND));

        // Validate that the subscription belongs to this merchant
        subscriptionV2Repository.findCustomerIdByMerchantIdAndId(merchantId, source.getSubscriptionId())
                .orElseThrow(() -> new MembershipException(
                        "Subscription " + source.getSubscriptionId()
                                + " not found for merchant " + merchantId,
                        "SUBSCRIPTION_NOT_FOUND", HttpStatus.NOT_FOUND));

        if (source.getStatus() != DunningStatus.FAILED) {
            throw new MembershipException(
                    "Attempt " + attemptId + " is not in FAILED state (status=" + source.getStatus() + ")",
                    "DUNNING_ATTEMPT_NOT_FAILED", HttpStatus.CONFLICT);
        }

        DunningAttempt retry = DunningAttempt.builder()
                .subscriptionId(source.getSubscriptionId())
                .invoiceId(source.getInvoiceId())
                .attemptNumber(source.getAttemptNumber())
                .scheduledAt(LocalDateTime.now())
                .status(DunningStatus.SCHEDULED)
                .dunningPolicyId(source.getDunningPolicyId())
                .paymentMethodId(source.getPaymentMethodId())
                .usedBackupMethod(source.isUsedBackupMethod())
                .build();

        DunningAttempt saved = dunningAttemptRepository.save(retry);

        domainEventLog.record("DUNNING_V2_FORCE_RETRY", Map.of(
                "merchantId",      merchantId,
                "sourceAttemptId", attemptId,
                "newAttemptId",    saved.getId(),
                "subscriptionId",  source.getSubscriptionId()));

        log.info("Force-retry: new attempt {} created from source {} for sub {}",
                saved.getId(), attemptId, source.getSubscriptionId());
        return saved;
    }
}
