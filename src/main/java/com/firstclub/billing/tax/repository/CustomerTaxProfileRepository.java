package com.firstclub.billing.tax.repository;

import com.firstclub.billing.tax.entity.CustomerTaxProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerTaxProfileRepository extends JpaRepository<CustomerTaxProfile, Long> {

    Optional<CustomerTaxProfile> findByCustomerId(Long customerId);

    boolean existsByCustomerId(Long customerId);
}
