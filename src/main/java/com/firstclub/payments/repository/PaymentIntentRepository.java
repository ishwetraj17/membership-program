package com.firstclub.payments.repository;

import com.firstclub.payments.entity.PaymentIntent;
import com.firstclub.payments.model.PaymentIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByClientSecret(String clientSecret);

    Optional<PaymentIntent> findByGatewayReference(String gatewayReference);

    List<PaymentIntent> findByStatus(PaymentIntentStatus status);

    /** All intents linked to a specific invoice — used by reconciliation. */
    List<PaymentIntent> findByInvoiceId(Long invoiceId);
}
