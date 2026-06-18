package com.firstclub.membership.service.impl;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionEvent;
import com.firstclub.membership.event.OutboxEventService;
import com.firstclub.membership.event.SubscriptionDomainEvent;
import com.firstclub.membership.repository.SubscriptionEventRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Applies a single trial outcome (conversion or expiry) in its own transaction.
 *
 * Like {@link SubscriptionRenewalProcessor}, this is a separate bean so REQUIRES_NEW is honoured via
 * the proxy. For a conversion the charge happens OUTSIDE this transaction (reference passed in), so a
 * successful charge is never committed-without / rolled-back-after the conversion write.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrialConversionProcessor {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionEventRepository eventRepository;
    private final OutboxEventService outboxEventService;
    private final Clock clock;

    /** Converts a trial to a paid subscription after a successful first-period charge. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void convert(Subscription trial, String paymentReference) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime newEnd = now.plusMonths(trial.getPlan().getDurationInMonths());
        trial.setTrial(false);
        trial.setTrialConverted(true);
        trial.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        trial.setStartDate(now);
        trial.setEndDate(newEnd);
        trial.setNextBillingDate(newEnd);
        trial.setPaidAmount(trial.getPlan().getPrice());
        subscriptionRepository.save(trial);
        record(trial, SubscriptionEvent.EventType.TRIAL_CONVERTED, trial.getPaidAmount(), paymentReference, now);
    }

    /** Expires a trial that did not convert (auto-renewal off, or the conversion charge failed). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expire(Subscription trial) {
        LocalDateTime now = LocalDateTime.now(clock);
        trial.setStatus(Subscription.SubscriptionStatus.EXPIRED);
        subscriptionRepository.save(trial);
        record(trial, SubscriptionEvent.EventType.TRIAL_EXPIRED, BigDecimal.ZERO, null, now);
    }

    private void record(Subscription s, SubscriptionEvent.EventType type, BigDecimal amount,
                        String paymentReference, LocalDateTime occurredAt) {
        eventRepository.save(SubscriptionEvent.builder()
                .subscriptionId(s.getId()).userId(s.getUser().getId())
                .eventType(type).amount(amount)
                .planId(s.getPlan().getId()).tierName(s.getPlan().getTier().getName())
                .paymentReference(paymentReference).occurredAt(occurredAt)
                .build());
        outboxEventService.publish(SubscriptionEvent.AGGREGATE, s.getId(), type.name(),
                new SubscriptionDomainEvent(type.name(), s.getId(), s.getUser().getId(),
                        s.getPlan().getId(), s.getPlan().getTier().getName(), amount, occurredAt));
    }
}
