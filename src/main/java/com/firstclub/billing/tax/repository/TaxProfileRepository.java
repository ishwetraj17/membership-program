package com.firstclub.billing.tax.repository;

import com.firstclub.billing.tax.entity.TaxProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TaxProfileRepository extends JpaRepository<TaxProfile, Long> {

    Optional<TaxProfile> findByMerchantId(Long merchantId);

    boolean existsByMerchantId(Long merchantId);
}
