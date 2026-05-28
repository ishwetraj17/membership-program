package com.firstclub.membership.service.impl;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Processes a single auto-renewal in its own transaction.
 *
 * Must be a separate Spring bean so that @Transactional(REQUIRES_NEW) is
 * honoured via the proxy — self-invocation on SubscriptionServiceImpl would
 * bypass the proxy and share the caller's transaction.
 *
 * One failed renewal rolls back only its own REQUIRES_NEW transaction; all
 * other renewals in the batch are unaffected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionRenewalProcessor {

    private final SubscriptionRepository subscriptionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewSingle(Subscription subscription) {
        try {
            LocalDateTime newStart = subscription.getEndDate();
            LocalDateTime newEnd = newStart.plusMonths(subscription.getPlan().getDurationInMonths());
            subscription.setStartDate(newStart);
            subscription.setEndDate(newEnd);
            subscription.setNextBillingDate(newEnd);
            subscription.setPaidAmount(subscription.getPlan().getPrice());
            subscriptionRepository.save(subscription);
            return true;
        } catch (Exception e) {
            log.error("Auto-renewal failed for subscription {} — skipped", subscription.getId(), e);
            return false;
        }
    }
}
