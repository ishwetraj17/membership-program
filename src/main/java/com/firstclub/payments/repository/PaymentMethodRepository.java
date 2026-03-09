package com.firstclub.payments.repository;

import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PaymentMethod}.
 *
 * All queries are scoped by {@code merchantId} to enforce tenant isolation.
 */
@Repository
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    /** List all payment methods for a customer within a merchant (any status). */
    List<PaymentMethod> findByMerchantIdAndCustomerId(Long merchantId, Long customerId);

    /** Tenant-safe lookup by (merchantId, customerId, paymentMethodId). */
    Optional<PaymentMethod> findByMerchantIdAndCustomerIdAndId(
            Long merchantId, Long customerId, Long paymentMethodId);

    /** Find the current default payment method for a customer. */
    Optional<PaymentMethod> findByCustomerIdAndIsDefaultTrue(Long customerId);

    /** Check whether a provider token is already registered for that provider. */
    boolean existsByProviderAndProviderToken(String provider, String providerToken);

    /** Filter by status within a merchant+customer scope. */
    List<PaymentMethod> findByMerchantIdAndCustomerIdAndStatus(
            Long merchantId, Long customerId, PaymentMethodStatus status);
}
