package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;

import java.util.List;
import java.util.Optional;

public interface MembershipService {

    // ─── Plan queries ─────────────────────────────────────────
    List<MembershipPlanDTO> getAllPlans();
    List<MembershipPlanDTO> getActivePlans();
    List<MembershipPlanDTO> getPlansByTier(String tierName);
    List<MembershipPlanDTO> getPlansByTierId(Long tierId);
    List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type);
    Optional<MembershipPlanDTO> getPlanById(Long id);

    // ─── Tier queries — returns TierDTO, not the JPA entity ──
    List<TierDTO> getAllTiers();
    Optional<TierDTO> getTierByName(String name);
    Optional<TierDTO> getTierById(Long id);

    // ─── Subscription lifecycle ───────────────────────────────
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId);
    SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId);

    // ─── Subscription queries ─────────────────────────────────
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
    List<SubscriptionDTO> getAllSubscriptions();

    // ─── Background jobs ──────────────────────────────────────
    void processExpiredSubscriptions();
    void processRenewals();
}
