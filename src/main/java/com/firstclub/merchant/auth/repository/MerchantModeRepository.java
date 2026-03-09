package com.firstclub.merchant.auth.repository;

import com.firstclub.merchant.auth.entity.MerchantMode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantModeRepository extends JpaRepository<MerchantMode, Long> {
    // findById(merchantId) is the natural method since merchantId is the @Id
}
