package com.firstclub.payments.repository;

import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentIntentV2Repository extends JpaRepository<PaymentIntentV2, Long> {

    Optional<PaymentIntentV2> findByMerchantIdAndId(Long merchantId, Long id);

    List<PaymentIntentV2> findByMerchantIdAndCustomerId(Long merchantId, Long customerId);

    List<PaymentIntentV2> findByInvoiceId(Long invoiceId);

    List<PaymentIntentV2> findBySubscriptionId(Long subscriptionId);

    Optional<PaymentIntentV2> findByIdempotencyKeyAndMerchantId(String idempotencyKey,
                                                                  Long merchantId);

    List<PaymentIntentV2> findByMerchantIdAndCustomerIdAndStatus(
            Long merchantId, Long customerId, PaymentIntentStatusV2 status);

    /** Returns the merchant PK for a given V2 intent — used by webhook processing to populate payments.merchant_id. */
    @org.springframework.data.jpa.repository.Query("SELECT p.merchant.id FROM PaymentIntentV2 p WHERE p.id = :id")
    java.util.Optional<Long> findMerchantIdById(@org.springframework.data.repository.query.Param("id") Long id);
}
