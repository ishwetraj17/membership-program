package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.dto.ProductUpdateRequestDTO;
import com.firstclub.catalog.entity.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Product management within a merchant's catalog.
 */
public interface ProductService {

    ProductResponseDTO createProduct(Long merchantId, ProductCreateRequestDTO request);

    ProductResponseDTO updateProduct(Long merchantId, Long productId, ProductUpdateRequestDTO request);

    ProductResponseDTO archiveProduct(Long merchantId, Long productId);

    ProductResponseDTO getProductById(Long merchantId, Long productId);

    Page<ProductResponseDTO> listProducts(Long merchantId, ProductStatus status, Pageable pageable);

    /** Throws {@link com.firstclub.catalog.exception.CatalogException#productArchived} if ARCHIVED. */
    void ensureProductActive(Long merchantId, Long productId);
}
