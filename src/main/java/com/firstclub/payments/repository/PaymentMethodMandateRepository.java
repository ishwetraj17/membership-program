package com.firstclub.payments.repository;

import com.firstclub.payments.entity.PaymentMethodMandate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PaymentMethodMandate}.
 */
@Repository
public interface PaymentMethodMandateRepository extends JpaRepository<PaymentMethodMandate, Long> {

    /** List all mandates for a payment method, newest first. */
    List<PaymentMethodMandate> findByPaymentMethodIdOrderByCreatedAtDesc(Long paymentMethodId);

    /** Tenant-safe mandate lookup by (mandateId, paymentMethodId). */
    Optional<PaymentMethodMandate> findByIdAndPaymentMethodId(Long mandateId, Long paymentMethodId);
}
