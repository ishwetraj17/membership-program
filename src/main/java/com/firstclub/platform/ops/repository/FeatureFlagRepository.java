package com.firstclub.platform.ops.repository;

import com.firstclub.platform.ops.entity.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {

    /** Merchant-specific flag override. */
    Optional<FeatureFlag> findByFlagKeyAndMerchantId(String flagKey, Long merchantId);

    /** Global platform-wide flag (no merchant scope). */
    Optional<FeatureFlag> findByFlagKeyAndMerchantIdIsNull(String flagKey);

    List<FeatureFlag> findAllByOrderByFlagKeyAsc();
}
