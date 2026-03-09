package com.firstclub.membership.event;

import com.firstclub.membership.entity.AuditLog;
import com.firstclub.membership.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Handles subscription lifecycle events published by {@code MembershipServiceImpl}
 * and writes the corresponding entries to the {@code audit_logs} table.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>{@link TransactionPhase#AFTER_COMMIT} — the audit is written only after
 *       the originating business transaction has committed successfully.  No event
 *       is fired for rolled-back operations.
 *   <li>{@code REQUIRES_NEW} — each handler runs in an independent transaction.
 *       An audit-write failure is therefore isolated and can never roll back an
 *       already-committed subscription change.
 *   <li>Failures inside {@link AuditLogService#record} itself are silently swallowed
 *       at the service level, providing a second line of defence.
 * </ul>
 *
 * Implemented by Shwet Raj
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionEventListener {

    private final AuditLogService auditLogService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionCreated(SubscriptionCreatedEvent event) {
        log.debug("Audit: CREATED subscription={} user={}", event.getSubscriptionId(), event.getUserId());
        auditLogService.record(
                AuditLog.AuditAction.SUBSCRIPTION_CREATED,
                "Subscription",
                event.getSubscriptionId(),
                event.getUserId(),
                "Subscription created",
                "{\"planId\":" + event.getPlanId() + "}"
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionCancelled(SubscriptionCancelledEvent event) {
        log.debug("Audit: CANCELLED subscription={} user={}", event.getSubscriptionId(), event.getUserId());
        String metadata = event.getReason() != null
                ? "{\"reason\":\"" + event.getReason().replace("\"", "\\\"") + "\"}"
                : null;
        auditLogService.record(
                AuditLog.AuditAction.SUBSCRIPTION_CANCELLED,
                "Subscription",
                event.getSubscriptionId(),
                event.getUserId(),
                "Subscription cancelled",
                metadata
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionRenewed(SubscriptionRenewedEvent event) {
        log.debug("Audit: RENEWED subscription={} user={}", event.getSubscriptionId(), event.getUserId());
        auditLogService.record(
                AuditLog.AuditAction.SUBSCRIPTION_RENEWED,
                "Subscription",
                event.getSubscriptionId(),
                event.getUserId(),
                "Subscription renewed",
                "{\"planId\":" + event.getPlanId() + "}"
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionUpgraded(SubscriptionUpgradedEvent event) {
        log.debug("Audit: UPGRADED subscription={} user={}", event.getSubscriptionId(), event.getUserId());
        auditLogService.record(
                AuditLog.AuditAction.SUBSCRIPTION_UPGRADED,
                "Subscription",
                event.getSubscriptionId(),
                event.getUserId(),
                "Subscription upgraded",
                "{\"fromPlanId\":" + event.getFromPlanId() + ",\"toPlanId\":" + event.getToPlanId() + "}"
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionDowngraded(SubscriptionDowngradedEvent event) {
        log.debug("Audit: DOWNGRADED subscription={} user={}", event.getSubscriptionId(), event.getUserId());
        auditLogService.record(
                AuditLog.AuditAction.SUBSCRIPTION_DOWNGRADED,
                "Subscription",
                event.getSubscriptionId(),
                event.getUserId(),
                "Subscription downgraded",
                "{\"fromPlanId\":" + event.getFromPlanId() + ",\"toPlanId\":" + event.getToPlanId() + "}"
        );
    }
}
