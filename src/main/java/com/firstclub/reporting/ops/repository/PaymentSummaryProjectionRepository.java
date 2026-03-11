package com.firstclub.reporting.ops.repository;

import com.firstclub.reporting.ops.entity.PaymentSummaryProjection;
import com.firstclub.reporting.ops.entity.PaymentSummaryProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PaymentSummaryProjectionRepository
        extends JpaRepository<PaymentSummaryProjection, PaymentSummaryProjectionId> {

    Optional<PaymentSummaryProjection> findByMerchantIdAndPaymentIntentId(Long merchantId, Long paymentIntentId);

    Page<PaymentSummaryProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Page<PaymentSummaryProjection> findByMerchantIdAndStatus(Long merchantId, String status, Pageable pageable);

    Page<PaymentSummaryProjection> findByMerchantIdAndCustomerId(Long merchantId, Long customerId, Pageable pageable);

    @Query("SELECT MIN(p.updatedAt) FROM PaymentSummaryProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
