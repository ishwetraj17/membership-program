package com.firstclub.billing.repository;

import com.firstclub.billing.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiscountRepository extends JpaRepository<Discount, Long> {

    Optional<Discount> findByMerchantIdAndCodeIgnoreCase(Long merchantId, String code);

    boolean existsByMerchantIdAndCodeIgnoreCase(Long merchantId, String code);

    List<Discount> findAllByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    Optional<Discount> findByIdAndMerchantId(Long id, Long merchantId);
}
