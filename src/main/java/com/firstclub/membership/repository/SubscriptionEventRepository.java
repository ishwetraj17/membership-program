package com.firstclub.membership.repository;

import com.firstclub.membership.entity.SubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

    List<SubscriptionEvent> findBySubscriptionIdOrderByOccurredAtAsc(Long subscriptionId);

    List<SubscriptionEvent> findByUserIdOrderByOccurredAtDesc(Long userId);

    /** Lifetime net revenue: charging events minus refunds (stored as negative REFUNDED amounts). */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM SubscriptionEvent e " +
           "WHERE e.eventType IN ('CREATED', 'RENEWED', 'UPGRADED', 'REFUNDED')")
    BigDecimal sumLifetimeRevenue();

    /** The most recent charging event with a payment reference — used to refund against it. */
    java.util.Optional<com.firstclub.membership.entity.SubscriptionEvent>
        findFirstBySubscriptionIdAndPaymentReferenceIsNotNullOrderByOccurredAtDesc(Long subscriptionId);
}
