package com.firstclub.billing.repository;

import com.firstclub.billing.entity.DiscountRedemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountRedemptionRepository extends JpaRepository<DiscountRedemption, Long> {

    long countByDiscountId(Long discountId);

    long countByDiscountIdAndCustomerId(Long discountId, Long customerId);

    boolean existsByDiscountIdAndInvoiceId(Long discountId, Long invoiceId);
}
