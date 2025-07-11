package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for membership and subscription operations
 * 
 * Core business logic for membership management.
 * Handles plans, tiers, and subscription lifecycle.
 * 
 * Implemented by Shwet Raj
 */
public interface MembershipService {

    // Initialization
    void initializeDefaultData();

    // Plan operations
    List<MembershipPlanDTO> getAllPlans();
    List<MembershipPlanDTO> getActivePlans();
    List<MembershipPlanDTO> getPlansByTier(String tierName);
    List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type);
    Optional<MembershipPlanDTO> getPlanById(Long id);

    // Tier operations
    List<MembershipTier> getAllTiers();
    Optional<MembershipTier> getTierByName(String name);

    // Subscription operations
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
    List<SubscriptionDTO> getAllSubscriptions();

    // Subscription management
    SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId);
    SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId);

    // Background processes
    void processExpiredSubscriptions();
    void processRenewals();
}