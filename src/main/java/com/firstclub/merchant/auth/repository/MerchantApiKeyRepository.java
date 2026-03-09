package com.firstclub.merchant.auth.repository;

import com.firstclub.merchant.auth.entity.MerchantApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, Long> {

    List<MerchantApiKey> findByMerchantId(Long merchantId);

    /** Fast prefix-based lookup used during API key authentication. */
    Optional<MerchantApiKey> findByKeyPrefix(String keyPrefix);

    /** Retrieve a specific key belonging to a merchant (prevents cross-merchant access). */
    Optional<MerchantApiKey> findByMerchantIdAndId(Long merchantId, Long id);
}
