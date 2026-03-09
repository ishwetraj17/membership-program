package com.firstclub.merchant.repository;

import com.firstclub.merchant.entity.MerchantSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantSettingsRepository extends JpaRepository<MerchantSettings, Long> {

    Optional<MerchantSettings> findByMerchantId(Long merchantId);

    boolean existsByMerchantId(Long merchantId);
}
