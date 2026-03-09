package com.firstclub.catalog.repository;

import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.entity.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Product}.
 *
 * <p>All finder methods include {@code merchantId} to enforce tenant isolation
 * at the persistence layer.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByMerchantIdAndId(Long merchantId, Long productId);

    Optional<Product> findByMerchantIdAndProductCode(Long merchantId, String productCode);

    boolean existsByMerchantIdAndProductCode(Long merchantId, String productCode);

    Page<Product> findAllByMerchantId(Long merchantId, Pageable pageable);

    Page<Product> findAllByMerchantIdAndStatus(Long merchantId, ProductStatus status, Pageable pageable);
}
