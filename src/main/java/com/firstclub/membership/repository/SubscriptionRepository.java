package com.firstclub.membership.repository;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
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
}