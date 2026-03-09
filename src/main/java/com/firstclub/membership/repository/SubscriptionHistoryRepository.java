package com.firstclub.membership.repository;

import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.SubscriptionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for SubscriptionHistory audit records.
 */
@Repository
public interface SubscriptionHistoryRepository extends JpaRepository<SubscriptionHistory, Long> {

    List<SubscriptionHistory> findBySubscriptionOrderByChangedAtDesc(Subscription subscription);

    /** Paginated variant — used by {@code SubscriptionHistoryService}. */
    Page<SubscriptionHistory> findBySubscriptionOrderByChangedAtDesc(Subscription subscription, Pageable pageable);
}
