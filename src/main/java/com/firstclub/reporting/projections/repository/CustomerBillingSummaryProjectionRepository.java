package com.firstclub.reporting.projections.repository;

import com.firstclub.reporting.projections.entity.CustomerBillingProjectionId;
import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface CustomerBillingSummaryProjectionRepository
        extends JpaRepository<CustomerBillingSummaryProjection, CustomerBillingProjectionId> {

    Page<CustomerBillingSummaryProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Optional<CustomerBillingSummaryProjection> findByMerchantIdAndCustomerId(Long merchantId, Long customerId);

    @Query("SELECT MIN(p.updatedAt) FROM CustomerBillingSummaryProjection p")
    Optional<LocalDateTime> findOldestUpdatedAt();
}
