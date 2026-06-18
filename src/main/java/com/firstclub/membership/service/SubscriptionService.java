package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SubscriptionService {

    // ─── Lifecycle ────────────────────────────────────────────
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request, String idempotencyKey);
    SubscriptionDTO startTrial(TrialRequest request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId);
    SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId);

    // ─── Queries ──────────────────────────────────────────────
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    Page<SubscriptionDTO> getUserSubscriptions(Long userId, Pageable pageable);
    Page<SubscriptionDTO> getAllSubscriptions(Pageable pageable);
    boolean subscriptionBelongsToUser(Long subscriptionId, Long userId);
    List<SubscriptionEventDTO> getSubscriptionEvents(Long subscriptionId);

    // ─── Background jobs ──────────────────────────────────────
    void processExpiredSubscriptions();
    void processRenewals();
    void processTrialConversions();

    // ─── Aggregates (DB-level, O(1) queries) ──────────────────
    Map<String, Object> getActiveStats();
    Map<String, Object> getAnalyticsStats();
}
