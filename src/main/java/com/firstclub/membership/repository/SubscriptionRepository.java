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

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByUserOrderByCreatedAtDesc(User user);

    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    @Query("SELECT s FROM Subscription s WHERE s.user = :user " +
           "AND s.status = 'ACTIVE' AND s.endDate > :now")
    Optional<Subscription> findActiveSubscriptionByUser(@Param("user") User user,
                                                        @Param("now") LocalDateTime now);

    @Query("SELECT s FROM Subscription s WHERE s.autoRenewal = true " +
           "AND s.status = 'ACTIVE' AND s.nextBillingDate <= :renewalDate")
    List<Subscription> findSubscriptionsForRenewal(@Param("renewalDate") LocalDateTime renewalDate);

    @Query("SELECT COUNT(s) > 0 FROM Subscription s WHERE s.user = :user " +
           "AND s.status = 'ACTIVE' AND s.endDate > :now")
    boolean hasActiveSubscriptions(@Param("user") User user, @Param("now") LocalDateTime now);

    /**
     * Fetches active subscriptions that have passed their end date in a single
     * indexed query — replaces the previous full-table scan + in-memory filter.
     */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.endDate < :now")
    List<Subscription> findExpiredActiveSubscriptions(@Param("now") LocalDateTime now);
}
