package com.firstclub.reporting.ops.repository;

import com.firstclub.reporting.ops.entity.SubscriptionStatusProjection;
import com.firstclub.reporting.ops.entity.SubscriptionStatusProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubscriptionStatusProjectionRepository
        extends JpaRepository<SubscriptionStatusProjection, SubscriptionStatusProjectionId> {

    Optional<SubscriptionStatusProjection> findByMerchantIdAndSubscriptionId(Long merchantId, Long subscriptionId);

    Page<SubscriptionStatusProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Page<SubscriptionStatusProjection> findByMerchantIdAndStatus(Long merchantId, String status, Pageable pageable);

    Page<SubscriptionStatusProjection> findByMerchantIdAndCustomerId(Long merchantId, Long customerId, Pageable pageable);
}
