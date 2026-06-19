package com.firstclub.membership.service.impl;

import com.firstclub.membership.config.MembershipConfig;
import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.entity.IdempotencyRecord;
import com.firstclub.membership.entity.SubscriptionEvent;
import com.firstclub.membership.event.OutboxEventService;
import com.firstclub.membership.event.SubscriptionDomainEvent;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.IdempotencyRecordRepository;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SavingsLedgerRepository;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.ChargeRequest;
import com.firstclub.membership.service.IntroductoryOfferService;
import com.firstclub.membership.service.PaymentGateway;
import com.firstclub.membership.service.RefundRequest;
import com.firstclub.membership.service.SavingsService;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.TierEvaluationService;
import com.firstclub.membership.service.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final MembershipPlanRepository planRepository;
    private final UserService userService;
    private final SubscriptionRenewalProcessor renewalProcessor;
    private final SubscriptionEventRepository eventRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final OutboxEventService outboxEventService;
    private final TierEvaluationService tierEvaluationService;
    private final PaymentGateway paymentGateway;
    private final MembershipConfig membershipConfig;
    private final PlatformTransactionManager txManager;
    private final SavingsService savingsService;
    private final IntroductoryOfferService introductoryOfferService;
    private final TrialConversionProcessor trialConversionProcessor;
    private final SavingsLedgerRepository savingsLedgerRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * Append an immutable billing/audit event AND a transactional-outbox domain event for a
     * subscription change — both in the caller's transaction, so they commit atomically with it.
     */
    private void recordEvent(Subscription s, SubscriptionEvent.EventType type, BigDecimal amount, String paymentReference) {
        BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
        LocalDateTime occurredAt = now();
        eventRepository.save(SubscriptionEvent.builder()
                .subscriptionId(s.getId())
                .userId(s.getUser().getId())
                .eventType(type)
                .amount(value)
                .planId(s.getPlan().getId())
                .tierName(s.getPlan().getTier().getName())
                .paymentReference(paymentReference)
                .occurredAt(occurredAt)
                .build());
        outboxEventService.publish(SubscriptionEvent.AGGREGATE, s.getId(), type.name(),
                new SubscriptionDomainEvent(type.name(), s.getId(), s.getUser().getId(),
                        s.getPlan().getId(), s.getPlan().getTier().getName(), value, occurredAt));
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public SubscriptionDTO createSubscription(SubscriptionRequestDTO request) {
        return createSubscription(request, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDTO createSubscription(SubscriptionRequestDTO request, String idempotencyKey) {
        boolean idempotent = idempotencyKey != null && !idempotencyKey.isBlank();
        if (idempotent) {
            Optional<IdempotencyRecord> prior = idempotencyRepository.findByIdempotencyKey(idempotencyKey);
            if (prior.isPresent()) {
                IdempotencyRecord rec = prior.get();
                if (!rec.getRequestHash().equals(requestHash(request))) {
                    throw new MembershipException(
                            "Idempotency-Key already used with a different request",
                            "IDEMPOTENCY_KEY_CONFLICT", HttpStatus.CONFLICT);
                }
                log.info("Replaying idempotent subscription create — key={}", idempotencyKey);
                return inTx(() -> convertToDTO(findById(rec.getTargetId())));
            }
        }

        log.info("Creating subscription — userId={} planId={}", request.getUserId(), request.getPlanId());

        // Payment saga: reserve a PENDING subscription (committed) → charge OUTSIDE any DB
        // transaction → activate on success, or compensate (cancel + refund) on failure. This
        // avoids holding a DB transaction across the payment call and the "charged but not
        // recorded" hazard of charging inline.
        Subscription pending = inTx(() -> createPending(request, idempotencyKey, idempotent));
        Long pendingId = pending.getId();

        PaymentGateway.PaymentResult payment;
        try {
            payment = charge(pending.getUser().getId(), pending.getPaidAmount(),
                    "CREATE " + pending.getPlan().getName(), "charge:create:" + pendingId);
        } catch (RuntimeException e) {
            finalizeFailed(pendingId, "Payment error", null);
            throw new MembershipException("Payment failed — please retry", "PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED);
        }
        if (!payment.success()) {
            finalizeFailed(pendingId, "Payment declined", null);
            throw new MembershipException("Payment declined", "PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED);
        }

        try {
            return inTx(() -> activate(pendingId, payment.reference()));
        } catch (RuntimeException e) {
            // e.g. the partial unique index rejected a concurrent activation — compensate + refund.
            finalizeFailed(pendingId, "Activation failed", payment.reference());
            throw e;
        }
    }

    /** Phase 1 — validate and persist a PENDING subscription (its own transaction). */
    private Subscription createPending(SubscriptionRequestDTO request, String idempotencyKey, boolean idempotent) {
        User user = userService.findUserEntityById(request.getUserId());
        MembershipPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> MembershipException.planNotFound(request.getPlanId()));
        if (!plan.getIsActive()) {
            throw MembershipException.inactivePlan(plan.getId());
        }
        LocalDateTime now = now();
        if (subscriptionRepository.findActiveSubscriptionByUser(user, now).isPresent()) {
            throw MembershipException.userAlreadySubscribed(user.getId());
        }
        if (membershipConfig.isEnforceTierEligibility()
                && !tierEvaluationService.isEligibleForTier(user.getId(), plan.getTier().getName())) {
            throw new MembershipException(
                    "User " + user.getId() + " is not eligible for the " + plan.getTier().getName() + " tier",
                    "TIER_NOT_ELIGIBLE", HttpStatus.FORBIDDEN);
        }
        // Introductory pricing (if supplied) discounts the first billing period only; the saga
        // charges this amount, and the savings (full − first-period) are recorded at activation.
        BigDecimal firstPeriodPrice = plan.getPrice();
        if (request.getIntroOfferCode() != null && !request.getIntroOfferCode().isBlank()) {
            IntroductoryOffer offer = introductoryOfferService.resolve(request.getIntroOfferCode(), plan.getId());
            firstPeriodPrice = offer.firstPeriodPrice(plan.getPrice());
            meterRegistry.counter("membership.intro.applied", "type", offer.getOfferType().name()).increment();
        }

        LocalDateTime endDate = now.plusMonths(plan.getDurationInMonths());
        Subscription pending = subscriptionRepository.save(Subscription.builder()
                .user(user).plan(plan)
                .status(Subscription.SubscriptionStatus.PENDING)
                .startDate(now).endDate(endDate).nextBillingDate(endDate)
                .paidAmount(firstPeriodPrice)
                .autoRenewal(request.getAutoRenewal())
                .build());
        if (idempotent) {
            idempotencyRepository.save(IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestHash(requestHash(request))
                    .targetType("SUBSCRIPTION")
                    .targetId(pending.getId())
                    .build());
        }
        return pending;
    }

    /** Phase 3a — activate after a successful charge (its own transaction). */
    private SubscriptionDTO activate(Long pendingId, String paymentReference) {
        Subscription sub = findById(pendingId);
        sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        Subscription active = subscriptionRepository.save(sub);
        recordEvent(active, SubscriptionEvent.EventType.CREATED, active.getPaidAmount(), paymentReference);
        // First-period savings from any introductory offer (full plan price − amount charged).
        BigDecimal introSavings = active.getPlan().getPrice().subtract(active.getPaidAmount());
        if (introSavings.signum() > 0) {
            savingsService.recordIntroSavings(active.getUser().getId(), active.getId(), introSavings, now());
        }
        log.info("Subscription created — id={}", active.getId());
        return convertToDTO(active);
    }

    /** Phase 3b — compensation: mark the pending subscription failed and refund if already charged. */
    private void finalizeFailed(Long pendingId, String reason, String paymentReference) {
        inTx(() -> {
            Subscription sub = findById(pendingId);
            if (paymentReference != null) {
                refund(paymentReference, sub.getPaidAmount(), "refund:" + paymentReference);
            }
            sub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
            sub.setCancelledAt(now());
            sub.setCancellationReason(reason);
            sub.setAutoRenewal(false);
            subscriptionRepository.save(sub);
            return null;
        });
        log.warn("Subscription {} not activated — {}", pendingId, reason);
    }

    /** Runs work in its own (REQUIRES_NEW) transaction, so the orchestration can sit outside any tx. */
    private <T> T inTx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private String requestHash(SubscriptionRequestDTO request) {
        return request.getUserId() + ":" + request.getPlanId() + ":" + request.getAutoRenewal()
                + ":" + request.getIntroOfferCode();
    }

    // ─── Trials ─────────────────────────────────────────────────────────────

    private static final java.util.Set<Integer> ALLOWED_TRIAL_DAYS = java.util.Set.of(7, 14, 30);

    @Override
    public SubscriptionDTO startTrial(TrialRequest request) {
        if (request.getTrialDays() == null || !ALLOWED_TRIAL_DAYS.contains(request.getTrialDays())) {
            throw new MembershipException("Trial length must be 7, 14 or 30 days", "INVALID_TRIAL_LENGTH");
        }
        log.info("Starting {}-day trial — userId={} planId={}",
                request.getTrialDays(), request.getUserId(), request.getPlanId());

        User user = userService.findUserEntityById(request.getUserId());
        MembershipPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> MembershipException.planNotFound(request.getPlanId()));
        if (!plan.getIsActive()) {
            throw MembershipException.inactivePlan(plan.getId());
        }
        LocalDateTime now = now();
        if (subscriptionRepository.findActiveSubscriptionByUser(user, now).isPresent()) {
            throw MembershipException.userAlreadySubscribed(user.getId());
        }

        // A trial is an ACTIVE (so it grants benefits), unpaid subscription whose end date is the
        // trial end. No charge now — the conversion job bills (or expires) it at the trial end.
        LocalDateTime trialEnd = now.plusDays(request.getTrialDays());
        Subscription trial = subscriptionRepository.save(Subscription.builder()
                .user(user).plan(plan)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(now).endDate(trialEnd).nextBillingDate(trialEnd)
                .paidAmount(BigDecimal.ZERO)
                .autoRenewal(request.getAutoRenewal())
                .trial(true).trialEndDate(trialEnd).trialConverted(false)
                .build());
        recordEvent(trial, SubscriptionEvent.EventType.TRIAL_STARTED, BigDecimal.ZERO, null);
        meterRegistry.counter("membership.trial.started", "days", String.valueOf(request.getTrialDays())).increment();
        log.info("Trial started — id={} endsAt={}", trial.getId(), trialEnd);
        return convertToDTO(trial);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processTrialConversions() {
        // Same shape as processRenewals: load detached trials, charge OUTSIDE the per-trial tx,
        // then convert (or expire) in a REQUIRES_NEW tx so one failure never affects the batch.
        List<Subscription> due = subscriptionRepository.findTrialsDue(now());
        if (due.isEmpty()) return;

        int converted = 0, expired = 0;
        for (Subscription trial : due) {
            // Isolate each trial: an unexpected failure (e.g. an optimistic-lock clash with a
            // concurrent cancel) must never abort the rest of the batch.
            try {
                if (!Boolean.TRUE.equals(trial.getAutoRenewal())) {
                    trialConversionProcessor.expire(trial);
                    meterRegistry.counter("membership.trial.expired", "reason", "no_auto_renew").increment();
                    expired++;
                    continue;
                }
                String reference;
                try {
                    reference = charge(trial.getUser().getId(), trial.getPlan().getPrice(),
                            "TRIAL_CONVERSION " + trial.getPlan().getName(),
                            "charge:trial:" + trial.getId() + ":" + trial.getTrialEndDate()).reference();
                } catch (Exception e) {
                    trialConversionProcessor.expire(trial);
                    meterRegistry.counter("membership.trial.expired", "reason", "charge_failed").increment();
                    expired++;
                    log.warn("Trial {} conversion charge failed — expired", trial.getId(), e);
                    continue;
                }
                try {
                    trialConversionProcessor.convert(trial, reference);
                    meterRegistry.counter("membership.trial.converted").increment();
                    converted++;
                } catch (Exception e) {
                    refund(reference, trial.getPlan().getPrice(), "refund:" + reference);
                    log.error("Trial {} conversion apply failed — charge refunded", trial.getId(), e);
                }
            } catch (Exception e) {
                log.error("Trial {} processing failed — skipped", trial.getId(), e);
            }
        }
        if (converted + expired > 0) log.info("Trials processed — converted={} expired={}", converted, expired);
    }

    @Override
    public SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO) {
        log.info("Updating subscription {}", subscriptionId);

        Subscription subscription = findById(subscriptionId);
        boolean changed = false;

        if (updateDTO.getAutoRenewal() != null) {
            subscription.setAutoRenewal(updateDTO.getAutoRenewal());
            changed = true;
        }

        // Plan changes are NOT handled here: tier/duration direction, pro-ration and date
        // anchoring differ between upgrade and downgrade. Routing both through one generic
        // setter produced inconsistent results, so plan changes go through the dedicated
        // upgrade / downgrade endpoints only.

        boolean cancelledViaUpdate = false;
        if (updateDTO.getStatus() != null && updateDTO.getStatus() != subscription.getStatus()) {
            if (!isValidTransition(subscription.getStatus(), updateDTO.getStatus())) {
                throw new MembershipException(
                        "Invalid status transition from " + subscription.getStatus() + " to " + updateDTO.getStatus(),
                        "INVALID_STATUS_TRANSITION");
            }
            subscription.setStatus(updateDTO.getStatus());
            if (updateDTO.getStatus() == Subscription.SubscriptionStatus.CANCELLED) {
                subscription.setCancelledAt(now());
                subscription.setCancellationReason(
                        updateDTO.getReason() != null ? updateDTO.getReason() : "Updated via API");
                subscription.setAutoRenewal(false);
                cancelledViaUpdate = true;
            }
            changed = true;
        }

        if (!changed) {
            return convertToDTO(subscription);
        }
        Subscription saved = subscriptionRepository.save(subscription);
        // Cancelling through the generic update must leave the same audit/outbox trail as the
        // dedicated cancel endpoint.
        if (cancelledViaUpdate) {
            recordEvent(saved, SubscriptionEvent.EventType.CANCELLED, BigDecimal.ZERO, null);
        }
        return convertToDTO(saved);
    }

    @Override
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) {
        log.info("Cancelling subscription {} — reason: {}", subscriptionId, reason);

        Subscription subscription = findById(subscriptionId);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw MembershipException.invalidSubscriptionStatus("cancel");
        }

        BigDecimal refund = membershipConfig.isRefundOnCancel()
                ? proratedRefund(subscription) : BigDecimal.ZERO;

        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenewal(false);

        Subscription cancelled = subscriptionRepository.save(subscription);
        recordEvent(cancelled, SubscriptionEvent.EventType.CANCELLED, BigDecimal.ZERO, null);

        if (refund.signum() > 0) {
            String originalReference = eventRepository
                    .findFirstBySubscriptionIdAndPaymentReferenceIsNotNullOrderByOccurredAtDesc(cancelled.getId())
                    .map(SubscriptionEvent::getPaymentReference)
                    .orElse(null);
            String refundKey = originalReference != null
                    ? "refund:" + originalReference
                    : "refund:sub:" + cancelled.getId();
            String refundReference = refund(originalReference, refund, refundKey).reference();
            // Stored negative so lifetime revenue nets out the refund.
            recordEvent(cancelled, SubscriptionEvent.EventType.REFUNDED, refund.negate(), refundReference);
            log.info("Refunded {} on cancellation of subscription {}", refund, cancelled.getId());
        }
        return convertToDTO(cancelled);
    }

    /** Unused (pro-rated) portion of the current period's paid amount. */
    private BigDecimal proratedRefund(Subscription s) {
        long totalDays = ChronoUnit.DAYS.between(s.getStartDate(), s.getEndDate());
        long remainingDays = ChronoUnit.DAYS.between(now(), s.getEndDate());
        if (totalDays <= 0 || remainingDays <= 0) return BigDecimal.ZERO;
        return s.getPaidAmount()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDTO renewSubscription(Long subscriptionId) {
        log.info("Renewing subscription {}", subscriptionId);

        // Same saga shape as create: validate (read) → charge OUTSIDE the tx → apply / refund.
        ChargeContext ctx = inTx(() -> {
            Subscription s = findById(subscriptionId);
            requireNotTrial(s);
            if (s.getStatus() != Subscription.SubscriptionStatus.EXPIRED) {
                throw new MembershipException("Only expired subscriptions can be renewed", "INVALID_SUBSCRIPTION_STATUS");
            }
            return new ChargeContext(s.getUser().getId(), s.getPlan().getPrice(), s.getPlan().getName(),
                    "charge:renew:" + subscriptionId + ":" + s.getEndDate());
        });

        String ref = chargeOrThrow(ctx, "RENEWED ");
        try {
            return inTx(() -> applyRenewal(subscriptionId, ref));
        } catch (RuntimeException e) {
            refundIfCharged(ref, ctx.amount());
            throw e;
        }
    }

    private SubscriptionDTO applyRenewal(Long subscriptionId, String paymentReference) {
        Subscription s = findById(subscriptionId);
        if (s.getStatus() != Subscription.SubscriptionStatus.EXPIRED) {
            throw new MembershipException("Only expired subscriptions can be renewed", "INVALID_SUBSCRIPTION_STATUS");
        }
        LocalDateTime now = now();
        LocalDateTime newEnd = now.plusMonths(s.getPlan().getDurationInMonths());
        s.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        s.setStartDate(now);
        s.setEndDate(newEnd);
        s.setNextBillingDate(newEnd);
        s.setPaidAmount(s.getPlan().getPrice());
        Subscription renewed = subscriptionRepository.save(s);
        recordEvent(renewed, SubscriptionEvent.EventType.RENEWED, renewed.getPaidAmount(), paymentReference);
        return convertToDTO(renewed);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Upgrading subscription {} to plan {}", subscriptionId, newPlanId);

        UpgradeContext ctx = inTx(() -> validateUpgrade(subscriptionId, newPlanId));

        String ref = chargeOrThrow(new ChargeContext(ctx.userId(), ctx.charge(), ctx.newPlanName(),
                ctx.idempotencyKey()), "UPGRADE ");
        try {
            return inTx(() -> applyUpgrade(subscriptionId, newPlanId, ctx.charge(), ref));
        } catch (RuntimeException e) {
            refundIfCharged(ref, ctx.charge());
            throw e;
        }
    }

    /**
     * Trials must only ever progress through the trial-conversion job. Allowing a manual
     * renew/upgrade/downgrade on a trial would charge here and leave {@code trial=true}, so the
     * conversion job would then charge again — guard against that double-billing class.
     */
    private void requireNotTrial(Subscription s) {
        if (Boolean.TRUE.equals(s.getTrial())) {
            throw new MembershipException(
                    "A trial cannot be renewed, upgraded or downgraded — it converts automatically; "
                            + "subscribe to a paid plan instead",
                    "TRIAL_NOT_MODIFIABLE");
        }
    }

    /** Validates an upgrade and computes the pro-rated charge — no mutation. */
    private UpgradeContext validateUpgrade(Long subscriptionId, Long newPlanId) {
        Subscription subscription = findById(subscriptionId);
        requireNotTrial(subscription);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot upgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }
        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));
        MembershipPlan currentPlan = subscription.getPlan();

        boolean higherTier = newPlan.getTier().getLevel() > currentPlan.getTier().getLevel();
        boolean longerDuration = newPlan.getTier().getLevel().equals(currentPlan.getTier().getLevel())
                && newPlan.getDurationInMonths() > currentPlan.getDurationInMonths();
        if (!higherTier && !longerDuration) {
            throw MembershipException.invalidPlanTransition(currentPlan.getName(), newPlan.getName());
        }
        BigDecimal charge = calculateUpgradeCharge(subscription, currentPlan, newPlan, now());
        // Idempotency key must be stable across retries of THIS upgrade yet unique per upgrade event.
        // The subscription's current startDate is reset on every upgrade/downgrade, so re-upgrading to
        // the same plan later yields a different key (a genuinely new charge), while a retry before the
        // upgrade is applied reuses it (the PSP dedupes — no double charge).
        String idempotencyKey = "charge:upgrade:" + subscriptionId + ":" + newPlanId
                + ":" + subscription.getStartDate();
        return new UpgradeContext(subscription.getUser().getId(), charge, newPlan.getName(), idempotencyKey);
    }

    /** Applies the validated upgrade after a successful charge. */
    private SubscriptionDTO applyUpgrade(Long subscriptionId, Long newPlanId, BigDecimal charge, String paymentReference) {
        Subscription subscription = findById(subscriptionId);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot upgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }
        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));
        LocalDateTime now = now();
        // New plan starts a fresh full term from now; anchoring endDate to now never shortens it.
        subscription.setPlan(newPlan);
        subscription.setStartDate(now);
        LocalDateTime newEnd = now.plusMonths(newPlan.getDurationInMonths());
        subscription.setEndDate(newEnd);
        subscription.setNextBillingDate(newEnd);
        subscription.setPaidAmount(subscription.getPaidAmount().add(charge));
        Subscription upgraded = subscriptionRepository.save(subscription);
        recordEvent(upgraded, SubscriptionEvent.EventType.UPGRADED, charge, paymentReference);
        log.info("Upgraded subscription {} — pro-rated charge: {}", subscriptionId, charge);
        return convertToDTO(upgraded);
    }

    /** Charges the member outside any transaction (skipping a zero charge); returns the reference or null. */
    private String chargeOrThrow(ChargeContext ctx, String descriptionPrefix) {
        if (ctx.amount() == null || ctx.amount().signum() <= 0) {
            return null;
        }
        PaymentGateway.PaymentResult payment;
        try {
            payment = charge(ctx.userId(), ctx.amount(), descriptionPrefix + ctx.planName(), ctx.idempotencyKey());
        } catch (RuntimeException e) {
            throw new MembershipException("Payment failed — please retry", "PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED);
        }
        if (!payment.success()) {
            throw new MembershipException("Payment declined", "PAYMENT_FAILED", HttpStatus.PAYMENT_REQUIRED);
        }
        return payment.reference();
    }

    private void refundIfCharged(String reference, BigDecimal amount) {
        if (reference != null) {
            refund(reference, amount, "refund:" + reference);
        }
    }

    // ─── PSP request construction ─────────────────────────────────────────────
    // Every charge/refund carries a DETERMINISTIC idempotency key derived from business identity
    // (operation + subscription + billing period). The same logical charge always produces the same
    // key, so a retry — at the application level or inside the resilience layer — is deduplicated by
    // the PSP and can never double-charge. The correlation id stitches our logs to the provider's.

    private static final String CURRENCY = "INR";

    private PaymentGateway.PaymentResult charge(Long userId, BigDecimal amount, String description,
                                                String idempotencyKey) {
        return paymentGateway.charge(ChargeRequest.builder()
                .idempotencyKey(idempotencyKey)
                .correlationId(correlationId())
                .customerReference(String.valueOf(userId))
                .amount(amount)
                .currency(CURRENCY)
                .description(description)
                .build());
    }

    private PaymentGateway.PaymentResult refund(String originalReference, BigDecimal amount,
                                                String idempotencyKey) {
        return paymentGateway.refund(RefundRequest.builder()
                .idempotencyKey(idempotencyKey)
                .correlationId(correlationId())
                .originalReference(originalReference)
                .amount(amount)
                .build());
    }

    /** Trace correlation id from the request MDC (set by CorrelationIdFilter), or a fresh id for background jobs. */
    private String correlationId() {
        String id = org.slf4j.MDC.get("requestId");
        return id != null ? id : java.util.UUID.randomUUID().toString();
    }

    private record ChargeContext(Long userId, BigDecimal amount, String planName, String idempotencyKey) {}
    private record UpgradeContext(Long userId, BigDecimal charge, String newPlanName, String idempotencyKey) {}

    @Override
    public SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Downgrading subscription {} to plan {}", subscriptionId, newPlanId);

        Subscription subscription = findById(subscriptionId);
        requireNotTrial(subscription);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot downgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }

        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));

        if (newPlan.getTier().getLevel() >= subscription.getPlan().getTier().getLevel()) {
            throw MembershipException.invalidPlanTransition(subscription.getPlan().getName(), newPlan.getName());
        }

        // Takes effect immediately: the lower-tier plan starts now for its full duration.
        // No refund is issued for the unused portion of the higher tier (deliberate policy).
        subscription.setPlan(newPlan);
        LocalDateTime now = now();
        subscription.setStartDate(now);
        LocalDateTime newEnd = now.plusMonths(newPlan.getDurationInMonths());
        subscription.setEndDate(newEnd);
        subscription.setNextBillingDate(newEnd);
        Subscription downgraded = subscriptionRepository.save(subscription);
        recordEvent(downgraded, SubscriptionEvent.EventType.DOWNGRADED, BigDecimal.ZERO, null);
        return convertToDTO(downgraded);
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDTO> getActiveSubscription(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findActiveSubscriptionByUser(user, now())
                .map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getUserSubscriptions(Long userId, Pageable pageable) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findByUser(user, pageable).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getAllSubscriptions(Pageable pageable) {
        return subscriptionRepository.findAllWithAssociations(pageable).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean subscriptionBelongsToUser(Long subscriptionId, Long userId) {
        return subscriptionRepository.existsByIdAndUserId(subscriptionId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionEventDTO> getSubscriptionEvents(Long subscriptionId) {
        if (!subscriptionRepository.existsById(subscriptionId)) {
            throw MembershipException.subscriptionNotFound(subscriptionId);
        }
        return eventRepository.findBySubscriptionIdOrderByOccurredAtAsc(subscriptionId).stream()
                .map(e -> SubscriptionEventDTO.builder()
                        .id(e.getId())
                        .subscriptionId(e.getSubscriptionId())
                        .userId(e.getUserId())
                        .eventType(e.getEventType())
                        .amount(e.getAmount())
                        .planId(e.getPlanId())
                        .tierName(e.getTierName())
                        .paymentReference(e.getPaymentReference())
                        .occurredAt(e.getOccurredAt())
                        .build())
                .toList();
    }

    // ─── Background jobs ──────────────────────────────────────────────────────

    @Override
    public void processExpiredSubscriptions() {
        LocalDateTime now = now();
        Pageable batch = org.springframework.data.domain.PageRequest.of(0, EXPIRY_BATCH_SIZE);
        int total = 0;
        List<Subscription> expiring;
        // Process in bounded batches so the job never loads an unbounded set into memory. Each
        // batch is bulk-expired by id (version bumped) and gets an EXPIRED event per row.
        do {
            expiring = subscriptionRepository.findExpiredActive(now, batch);
            if (expiring.isEmpty()) break;
            expiring.forEach(s -> recordEvent(s, SubscriptionEvent.EventType.EXPIRED, BigDecimal.ZERO, null));
            subscriptionRepository.expireByIds(expiring.stream().map(Subscription::getId).toList());
            total += expiring.size();
        } while (expiring.size() == EXPIRY_BATCH_SIZE);
        if (total > 0) log.info("Expired {} subscription(s).", total);
    }

    private static final int EXPIRY_BATCH_SIZE = 200;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processRenewals() {
        // NOT_SUPPORTED: entities are loaded without an outer transaction so they become
        // detached after the query. Each renewSingle call (REQUIRES_NEW) merges and saves
        // the entity in its own transaction — one failure never rolls back the rest.
        List<Subscription> due = subscriptionRepository.findSubscriptionsForRenewal(now().plusDays(1));
        if (due.isEmpty()) return;

        int count = 0;
        for (Subscription subscription : due) {
            // Charge OUTSIDE the per-renewal transaction (plan/user are fetched on the detached row),
            // then apply in its own REQUIRES_NEW tx; refund if applying fails after a successful charge.
            String reference;
            try {
                reference = charge(subscription.getUser().getId(), subscription.getPlan().getPrice(),
                        "RENEWED " + subscription.getPlan().getName(),
                        "charge:autorenew:" + subscription.getId() + ":" + subscription.getNextBillingDate()).reference();
            } catch (Exception e) {
                log.error("Auto-renewal charge failed for subscription {} — skipped", subscription.getId(), e);
                continue;
            }
            try {
                renewalProcessor.applyRenewal(subscription, reference);
                count++;
            } catch (Exception e) {
                refund(reference, subscription.getPlan().getPrice(), "refund:" + reference);
                log.error("Auto-renewal apply failed for subscription {} — charge refunded", subscription.getId(), e);
            }
        }
        if (count > 0) log.info("Renewed {} subscription(s).", count);
    }

    // ─── Aggregates ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAnalyticsStats() {
        long activeCount = subscriptionRepository.countByStatus(Subscription.SubscriptionStatus.ACTIVE);
        long totalCount = subscriptionRepository.count();

        // Current active recurring revenue (the active rows' current-period paidAmount).
        BigDecimal activeRevenue = subscriptionRepository.sumActivePaidAmount();
        if (activeRevenue == null) activeRevenue = BigDecimal.ZERO;
        // Lifetime billed revenue, from the immutable event log (survives cancel/expiry/renewal).
        BigDecimal lifetimeRevenue = eventRepository.sumLifetimeRevenue();
        if (lifetimeRevenue == null) lifetimeRevenue = BigDecimal.ZERO;

        BigDecimal avgRevenue = activeCount == 0 ? BigDecimal.ZERO
                : activeRevenue.divide(BigDecimal.valueOf(activeCount), 2, RoundingMode.HALF_UP);

        Map<String, Long> tierDist = subscriptionRepository.countActiveGroupedByTier().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        Map<String, Long> planTypeDist = subscriptionRepository.countActiveGroupedByPlanType().stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1]));

        Map<String, Object> stats = new HashMap<>();
        stats.put("activeRecurringRevenue", activeRevenue);
        stats.put("lifetimeRevenue", lifetimeRevenue);
        stats.put("averageRevenuePerUser", avgRevenue);
        stats.put("activeSubscriptions", activeCount);
        stats.put("totalSubscriptions", totalCount);
        stats.put("tierDistribution", tierDist);
        stats.put("planTypeDistribution", planTypeDist);
        stats.putAll(retentionMetrics());
        return stats;
    }

    /** Acquisition/retention KPIs: active members, trial conversion, average savings per member. */
    private Map<String, Object> retentionMetrics() {
        long activeMembers = subscriptionRepository.countUsersWithActiveSubscriptions();
        long activeTrials = subscriptionRepository.countActiveTrials();
        long trialsStarted = subscriptionRepository.countTrialsStarted();
        long trialsConverted = subscriptionRepository.countTrialsConverted();
        BigDecimal conversionRate = trialsStarted == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trialsConverted)
                    .divide(BigDecimal.valueOf(trialsStarted), 4, RoundingMode.HALF_UP);

        BigDecimal totalSavings = savingsLedgerRepository.totalSavings();
        if (totalSavings == null) totalSavings = BigDecimal.ZERO;
        long savingMembers = savingsLedgerRepository.distinctMembersWithSavings();
        BigDecimal avgSavings = savingMembers == 0 ? BigDecimal.ZERO
                : totalSavings.divide(BigDecimal.valueOf(savingMembers), 2, RoundingMode.HALF_UP);

        Map<String, Object> retention = new HashMap<>();
        retention.put("activeMembers", activeMembers);
        retention.put("activeTrials", activeTrials);
        retention.put("trialsStarted", trialsStarted);
        retention.put("trialsConverted", trialsConverted);
        retention.put("trialConversionRate", conversionRate);
        retention.put("totalMemberSavings", totalSavings);
        retention.put("averageSavingsPerMember", avgSavings);
        return retention;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveStats() {
        long activeCount = subscriptionRepository.countByStatus(Subscription.SubscriptionStatus.ACTIVE);
        long userCount = subscriptionRepository.countUsersWithActiveSubscriptions();
        Map<String, Long> tierDist = subscriptionRepository.countActiveGroupedByTier().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        return Map.of("activeSubscriptions", activeCount, "uniqueUsers", userCount, "tierDistribution", tierDist);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Subscription findById(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(id));
    }

    private SubscriptionDTO convertToDTO(Subscription s) {
        // Derive isActive/daysRemaining from the injected (UTC) clock so they agree with the
        // UTC-stored timestamps, rather than the entity's system-zone LocalDateTime.now().
        LocalDateTime now = now();
        boolean active = s.getStatus() == Subscription.SubscriptionStatus.ACTIVE && now.isBefore(s.getEndDate());
        boolean expired = s.getStatus() == Subscription.SubscriptionStatus.EXPIRED || now.isAfter(s.getEndDate());
        long daysRemaining = expired ? 0 : ChronoUnit.DAYS.between(now, s.getEndDate());
        return SubscriptionDTO.builder()
                .id(s.getId())
                .userId(s.getUser().getId())
                .userName(s.getUser().getName())
                .userEmail(s.getUser().getEmail())
                .planId(s.getPlan().getId())
                .planName(s.getPlan().getName())
                .planType(s.getPlan().getType().name())
                .tier(s.getPlan().getTier().getName())
                .tierId(s.getPlan().getTier().getId())
                .tierLevel(s.getPlan().getTier().getLevel())
                .paidAmount(s.getPaidAmount())
                .status(s.getStatus())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .nextBillingDate(s.getNextBillingDate())
                .autoRenewal(s.getAutoRenewal())
                .daysRemaining(daysRemaining)
                .isActive(active)
                .trial(s.getTrial())
                .trialEndDate(s.getTrialEndDate())
                .trialConverted(s.getTrialConverted())
                .cancelledAt(s.getCancelledAt())
                .cancellationReason(s.getCancellationReason())
                .discountPercentage(s.getPlan().getTier().getDiscountPercentage())
                .freeDelivery(s.getPlan().getTier().getFreeDelivery())
                .exclusiveDeals(s.getPlan().getTier().getExclusiveDeals())
                .earlyAccess(s.getPlan().getTier().getEarlyAccess())
                .prioritySupport(s.getPlan().getTier().getPrioritySupport())
                .maxCouponsPerMonth(s.getPlan().getTier().getMaxCouponsPerMonth())
                .deliveryDays(s.getPlan().getTier().getDeliveryDays())
                .additionalBenefits(s.getPlan().getTier().getAdditionalBenefits())
                .build();
    }

    /**
     * Charge for upgrading to {@code newPlan}, which starts a fresh full term from now.
     *
     * The user is billed the new plan's full price, less a credit for the unused portion of
     * the current period (remaining days ÷ total days × current price). Clamped at zero so an
     * unusually large credit (e.g. switching to a much shorter billing cycle) never produces a
     * negative charge — the system does not refund on upgrade.
     */
    private BigDecimal calculateUpgradeCharge(Subscription subscription, MembershipPlan currentPlan,
                                              MembershipPlan newPlan, LocalDateTime now) {
        long totalDays = ChronoUnit.DAYS.between(subscription.getStartDate(), subscription.getEndDate());
        long remainingDays = ChronoUnit.DAYS.between(now, subscription.getEndDate());

        BigDecimal unusedValue = BigDecimal.ZERO;
        if (remainingDays > 0 && totalDays > 0) {
            unusedValue = currentPlan.getPrice()
                    .multiply(BigDecimal.valueOf(remainingDays))
                    .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);
        }

        return newPlan.getPrice().subtract(unusedValue).max(BigDecimal.ZERO);
    }

    private boolean isValidTransition(Subscription.SubscriptionStatus from, Subscription.SubscriptionStatus to) {
        return switch (from) {
            case ACTIVE    -> to == Subscription.SubscriptionStatus.CANCELLED
                           || to == Subscription.SubscriptionStatus.SUSPENDED
                           || to == Subscription.SubscriptionStatus.EXPIRED;
            case PENDING   -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case SUSPENDED -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case EXPIRED   -> false; // Reactivation requires renewSubscription — not generic update
            case CANCELLED -> false;
        };
    }
}
