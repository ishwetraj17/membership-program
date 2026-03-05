package com.firstclub.membership.repository;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Subscription operations
 * 
 * Complex queries for subscription management and analytics.
 * 
 * Implemented by Shwet Raj
 */
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    
    /**
     * Find all subscriptions for a user, ordered by creation date
     * 
     * @param user the user
     * @return List of subscriptions
     */
    List<Subscription> findByUserOrderByCreatedAtDesc(User user);
    
    /**
     * Find subscriptions by status
     * 
     * @param status subscription status
     * @return List of subscriptions with the status
     */
    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);
    
    /**
     * Find active subscription for a user
     * 
     * Custom query to find currently active subscription.
     * A user should only have one active subscription at a time.
     * 
     * @param user the user
     * @param currentTime current timestamp for checking validity
     * @return Optional containing active subscription
     */
    @Query("SELECT s FROM Subscription s WHERE s.user = :user " +
           "AND s.status = 'ACTIVE' AND s.endDate > :currentTime")
    Optional<Subscription> findActiveSubscriptionByUser(@Param("user") User user, 
                                                        @Param("currentTime") LocalDateTime currentTime);
    
    /**
     * Find subscriptions due for renewal
     * 
     * Used by background job to process auto-renewals.
     * 
     * @param renewalDate date to check for renewals
     * @return List of subscriptions due for renewal
     */
    @Query("SELECT s FROM Subscription s WHERE s.autoRenewal = true " +
           "AND s.status = 'ACTIVE' AND s.nextBillingDate <= :renewalDate")
    List<Subscription> findSubscriptionsForRenewal(@Param("renewalDate") LocalDateTime renewalDate);
    
    /**
     * Check if user has any active subscriptions
     */
    @Query("SELECT COUNT(s) > 0 FROM Subscription s WHERE s.user = :user " +
           "AND s.status = 'ACTIVE' AND s.endDate > :currentTime")
    boolean hasActiveSubscriptions(@Param("user") User user, @Param("currentTime") LocalDateTime currentTime);

    /**
     * Paginated query for all subscriptions - used by admin endpoints
     */
    Page<Subscription> findAll(Pageable pageable);

    /**
     * Count subscriptions by status — used by health/analytics endpoints
     * to avoid loading entire table into memory.
     */
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status = :status")
    long countBySubscriptionStatus(@Param("status") Subscription.SubscriptionStatus status);

    /**
     * Count ACTIVE subscriptions grouped by tier name — for tier distribution analytics.
     * Returns List of [tierName, count] Object arrays.
     */
    @Query("SELECT s.plan.tier.name, COUNT(s) FROM Subscription s " +
           "WHERE s.status = 'ACTIVE' GROUP BY s.plan.tier.name")
    List<Object[]> countActiveByTier();

    /**
     * Count ACTIVE subscriptions grouped by plan type — for plan type distribution analytics.
     */
    @Query("SELECT s.plan.type, COUNT(s) FROM Subscription s " +
           "WHERE s.status = 'ACTIVE' GROUP BY s.plan.type")
    List<Object[]> countActiveByPlanType();

    /**
     * Sum paidAmount for all ACTIVE subscriptions — total active revenue.
     */
    @Query("SELECT COALESCE(SUM(s.paidAmount), 0) FROM Subscription s WHERE s.status = 'ACTIVE'")
    java.math.BigDecimal sumActiveRevenue();

    /**
     * Count distinct users who have at least one ACTIVE subscription.
     */
    @Query("SELECT COUNT(DISTINCT s.user.id) FROM Subscription s WHERE s.status = 'ACTIVE'")
    long countDistinctActiveUsers();

    /**
     * Bulk-expire all ACTIVE subscriptions whose end date has passed.
     * Single UPDATE statement — eliminates the N+1 loop in processExpiredSubscriptions().
     *
     * @param now current timestamp
     * @return number of subscriptions marked EXPIRED
     */
    @Modifying
    @Query("UPDATE Subscription s SET s.status = 'EXPIRED' WHERE s.status = 'ACTIVE' AND s.endDate < :now")
    int bulkExpireSubscriptions(@Param("now") LocalDateTime now);
}