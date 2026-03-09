package com.firstclub.customer.repository;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Customer}.
 *
 * All query methods include {@code merchantId} to enforce tenant isolation —
 * never load a customer without also verifying its merchant.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /** Primary tenant-safe lookup by (merchantId, customerId). */
    Optional<Customer> findByMerchantIdAndId(Long merchantId, Long customerId);

    /** Case-insensitive email lookup within a merchant. */
    Optional<Customer> findByMerchantIdAndEmailIgnoreCase(Long merchantId, String email);

    /** Check whether an email is already taken within a merchant. */
    boolean existsByMerchantIdAndEmailIgnoreCase(Long merchantId, String email);

    /** Check whether an external customer ID is already taken within a merchant. */
    boolean existsByMerchantIdAndExternalCustomerId(Long merchantId, String externalCustomerId);

    /** Page all customers of a merchant in insertion order. */
    Page<Customer> findAllByMerchantId(Long merchantId, Pageable pageable);

    /** Page customers of a merchant filtered by status. */
    Page<Customer> findAllByMerchantIdAndStatus(Long merchantId, CustomerStatus status, Pageable pageable);
}
