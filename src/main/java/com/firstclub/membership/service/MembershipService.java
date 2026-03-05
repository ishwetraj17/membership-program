package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for membership subscription operations and system analytics.
 *
 * Plan and tier catalogue operations have been delegated to PlanService and TierService.
 *
 * Implemented by Shwet Raj
 */
public interface MembershipService {

    // Initialization
    void initializeDefaultData();

    // Subscription operations
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO getSubscriptionById(Long subscriptionId);
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
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