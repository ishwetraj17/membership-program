package com.firstclub.platform.version;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link MerchantApiVersion}.
 */
public interface MerchantApiVersionRepository extends JpaRepository<MerchantApiVersion, Long> {

    /** Lookup by tenant merchant ID. */
    Optional<MerchantApiVersion> findByMerchantId(Long merchantId);

    /** Check whether a pin already exists for a merchant. */
    boolean existsByMerchantId(Long merchantId);
}
