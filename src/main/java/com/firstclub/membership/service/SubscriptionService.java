package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface SubscriptionService {

    // ─── Lifecycle ────────────────────────────────────────────
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId);
    SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId);

    // ─── Queries ──────────────────────────────────────────────
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
    Page<SubscriptionDTO> getAllSubscriptions(Pageable pageable);

    // ─── Background jobs ──────────────────────────────────────
    void processExpiredSubscriptions();
    void processRenewals();
}
