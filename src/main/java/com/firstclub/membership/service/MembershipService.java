package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.MembershipPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
    List<MembershipPlanDTO> getPlansByTierId(Long tierId);
    List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type);
    Optional<MembershipPlanDTO> getPlanById(Long id);

    // Tier operations — return DTOs, not entities
    List<MembershipTierDTO> getAllTiers();
    Optional<MembershipTierDTO> getTierByName(String name);
    Optional<MembershipTierDTO> getTierById(Long id);

    // Subscription operations
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO getSubscriptionById(Long subscriptionId);
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
    List<SubscriptionDTO> getAllSubscriptions();
    Page<SubscriptionDTO> getAllSubscriptionsPaged(Pageable pageable);

    // Subscription lifecycle
    SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId);
    SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId);

    // Read-only upgrade preview (no state change)
    UpgradePreviewDTO getUpgradePreview(Long subscriptionId, Long newPlanId);

    // Aggregate analytics methods — no full table scans
    SystemHealthDTO getSystemHealth();
    AnalyticsDTO getAnalytics();

    // Background processes
    void processExpiredSubscriptions();
    void processRenewals();
}