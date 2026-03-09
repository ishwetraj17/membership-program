package com.firstclub.membership.service;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.Subscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for membership subscription operations and system analytics.
 *
 * Plan and tier catalogue operations have been delegated to PlanService and TierService.
 * Data initialisation is an internal boot-time concern and is no longer part of this contract.
 *
 * Implemented by Shwet Raj
 */
public interface MembershipService {

    // Subscription operations
    SubscriptionDTO createSubscription(SubscriptionRequestDTO request);
    SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO);
    SubscriptionDTO cancelSubscription(Long subscriptionId, String reason);
    SubscriptionDTO renewSubscription(Long subscriptionId);
    SubscriptionDTO getSubscriptionById(Long subscriptionId);
    Optional<SubscriptionDTO> getActiveSubscription(Long userId);
    /** @deprecated Use {@link #getUserSubscriptionsPaged(Long, Pageable)} for large datasets. */
    @Deprecated(since = "1.0", forRemoval = true)
    List<SubscriptionDTO> getUserSubscriptions(Long userId);
    Page<SubscriptionDTO> getUserSubscriptionsPaged(Long userId, Pageable pageable);
    Page<SubscriptionDTO> getAllSubscriptionsPaged(Pageable pageable);

    /**
     * Admin-facing filtered listing. Either filter may be {@code null} to disable it.
     *
     * @param status   optional status filter
     * @param userId   optional user-id filter
     * @param pageable pagination and sort
     * @return matching subscriptions
     */
    Page<SubscriptionDTO> getAllSubscriptionsFiltered(
            Subscription.SubscriptionStatus status, Long userId, Pageable pageable);

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