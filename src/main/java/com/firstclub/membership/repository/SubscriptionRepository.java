package com.firstclub.membership.repository;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("SELECT s FROM Subscription s WHERE s.status = 'ACTIVE' AND s.endDate < :now")
    List<Subscription> findExpiredActiveSubscriptions(@Param("now") LocalDateTime now);

    /**
     * Single UPDATE statement — avoids loading expired rows into memory.
     * Bypasses @Version intentionally: the scheduler is the sole writer for expiry.
     */
    @Modifying
    @Query("UPDATE Subscription s SET s.status = 'EXPIRED' WHERE s.status = 'ACTIVE' AND s.endDate < :now")
    int bulkExpireSubscriptions(@Param("now") LocalDateTime now);

    // ─── DB-level aggregates for health / analytics — avoids loading all rows ──

    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status = 'ACTIVE'")
    long countActiveSubscriptions();

    @Query("SELECT COUNT(DISTINCT s.user.id) FROM Subscription s WHERE s.status = 'ACTIVE'")
    long countUsersWithActiveSubscriptions();

    @Query("SELECT p.tier.name, COUNT(s) FROM Subscription s JOIN s.plan p WHERE s.status = 'ACTIVE' GROUP BY p.tier.name")
    List<Object[]> countActiveGroupedByTier();
}
