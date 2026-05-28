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

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    // Finding 6: JOIN FETCH user/plan/tier to prevent N+1 on list-to-DTO mapping
    @Query("SELECT s FROM Subscription s JOIN FETCH s.user JOIN FETCH s.plan p JOIN FETCH p.tier " +
           "WHERE s.user = :user ORDER BY s.createdAt DESC")
    List<Subscription> findByUserOrderByCreatedAtDesc(@Param("user") User user);

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
     * Single UPDATE statement — avoids loading expired rows into memory.
     * Bypasses @Version intentionally: the scheduler is the sole writer for expiry.
     */
    @Modifying
    @Query("UPDATE Subscription s SET s.status = 'EXPIRED' WHERE s.status = 'ACTIVE' AND s.endDate < :now")
    int bulkExpireSubscriptions(@Param("now") LocalDateTime now);

    // ─── DB-level aggregates for health / analytics — avoids loading all rows ──

    // Finding 3: derived query uses the enum — no hardcoded string literal
    long countByStatus(Subscription.SubscriptionStatus status);

    // Finding 6: paginated fetch with JOIN FETCH on ManyToOne associations (no collection fetch,
    // so pagination stays DB-side). Separate countQuery required when value uses JOIN FETCH.
    @Query(value = "SELECT s FROM Subscription s JOIN FETCH s.user JOIN FETCH s.plan p JOIN FETCH p.tier",
           countQuery = "SELECT COUNT(s) FROM Subscription s")
    Page<Subscription> findAllWithAssociations(Pageable pageable);

    @Query("SELECT COUNT(DISTINCT s.user.id) FROM Subscription s WHERE s.status = 'ACTIVE'")
    long countUsersWithActiveSubscriptions();

    @Query("SELECT p.tier.name, COUNT(s) FROM Subscription s JOIN s.plan p WHERE s.status = 'ACTIVE' GROUP BY p.tier.name")
    List<Object[]> countActiveGroupedByTier();
}
