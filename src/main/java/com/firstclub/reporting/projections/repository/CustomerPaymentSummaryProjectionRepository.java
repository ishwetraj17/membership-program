package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CustomerPaymentSummaryProjectionRepository
        extends JpaRepository<CustomerPaymentSummaryProjection, CustomerPaymentSummaryProjectionId> {

    Page<CustomerPaymentSummaryProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<CustomerPaymentSummaryProjection> findByMerchantIdAndCustomerId(Long merchantId, Long customerId);

    @Query("SELECT MIN(p.updatedAt) FROM CustomerPaymentSummaryProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
