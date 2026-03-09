package com.firstclub.subscription.repository;

import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for {@link SubscriptionV2}.
 *
 * <p>All methods include {@code merchantId} to enforce tenant isolation.
 */
@Repository
public interface SubscriptionV2Repository extends JpaRepository<SubscriptionV2, Long> {

    /** Tenant-scoped lookup by primary key. */
    Optional<SubscriptionV2> findByMerchantIdAndId(Long merchantId, Long subscriptionId);

    /** All subscriptions for a specific customer within a merchant. */
    List<SubscriptionV2> findByMerchantIdAndCustomerId(Long merchantId, Long customerId);

    /** Paginated subscriptions filtered by status. */
    Page<SubscriptionV2> findByMerchantIdAndStatus(Long merchantId, SubscriptionStatusV2 status, Pageable pageable);

    /** All subscriptions for a merchant (paginated). */
    Page<SubscriptionV2> findByMerchantId(Long merchantId, Pageable pageable);

    /**
     * Returns subscriptions due for billing (next_billing_at before the given threshold).
     * Used by the billing engine in later phases.
     */
    List<SubscriptionV2> findByMerchantIdAndNextBillingAtBefore(Long merchantId, LocalDateTime threshold);

    /**
     * Checks whether a non-terminal subscription already exists for the given
     * customer + product combination.  Used to enforce the one-active-subscription
     * constraint.
     */
    boolean existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn(
            Long merchantId, Long customerId, Long productId, Set<SubscriptionStatusV2> statuses);

    /**
     * Returns the customer ID for the given merchant-scoped subscription without
     * loading the full entity graph (avoids N+1 in dunning/preference services).
     */
    @Query("SELECT s.customer.id FROM SubscriptionV2 s WHERE s.merchant.id = :merchantId AND s.id = :id")
    Optional<Long> findCustomerIdByMerchantIdAndId(@Param("merchantId") Long merchantId,
                                                   @Param("id") Long id);
}
