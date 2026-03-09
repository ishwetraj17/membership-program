package com.firstclub.reporting.ops.repository;

import com.firstclub.reporting.ops.entity.InvoiceSummaryProjection;
import com.firstclub.reporting.ops.entity.InvoiceSummaryProjectionId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvoiceSummaryProjectionRepository
        extends JpaRepository<InvoiceSummaryProjection, InvoiceSummaryProjectionId> {

    Optional<InvoiceSummaryProjection> findByMerchantIdAndInvoiceId(Long merchantId, Long invoiceId);

    Page<InvoiceSummaryProjection> findByMerchantId(Long merchantId, Pageable pageable);

    Page<InvoiceSummaryProjection> findByMerchantIdAndStatus(Long merchantId, String status, Pageable pageable);

    Page<InvoiceSummaryProjection> findByMerchantIdAndCustomerId(Long merchantId, Long customerId, Pageable pageable);

    Page<InvoiceSummaryProjection> findByMerchantIdAndOverdueFlagTrue(Long merchantId, Pageable pageable);
}
