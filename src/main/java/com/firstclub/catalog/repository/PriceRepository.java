package com.firstclub.catalog.repository;

import com.firstclub.catalog.entity.Price;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Price}.
 */
public interface PriceRepository extends JpaRepository<Price, Long> {

    Optional<Price> findByMerchantIdAndId(Long merchantId, Long priceId);

    Optional<Price> findByMerchantIdAndPriceCode(Long merchantId, String priceCode);

    boolean existsByMerchantIdAndPriceCode(Long merchantId, String priceCode);

    Page<Price> findAllByMerchantId(Long merchantId, Pageable pageable);

    Page<Price> findAllByMerchantIdAndActive(Long merchantId, boolean active, Pageable pageable);

    List<Price> findAllByProductId(Long productId);
}
