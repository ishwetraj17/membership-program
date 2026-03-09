package com.firstclub.catalog.service.impl;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.dto.ProductUpdateRequestDTO;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.entity.ProductStatus;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.ProductMapper;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.catalog.service.ProductService;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link ProductService}.
 *
 * Business rules enforced:
 * - {@code productCode} is normalised (trimmed) but case-preserved; unique per merchant.
 * - Once ARCHIVED, a product cannot be used for new subscriptions.
 * - Archive is a one-way transition; no un-archive operation is offered.
 * - Tenant isolation: every query includes {@code merchantId}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final ProductMapper productMapper;

    @Override
    @Transactional
    public ProductResponseDTO createProduct(Long merchantId, ProductCreateRequestDTO request) {
        log.info("Creating product for merchantId={}, code={}", merchantId, request.getProductCode());

        MerchantAccount merchant = loadMerchantOrThrow(merchantId);

        if (productRepository.existsByMerchantIdAndProductCode(merchantId, request.getProductCode())) {
            throw CatalogException.duplicateProductCode(merchantId, request.getProductCode());
        }

        Product product = productMapper.toEntity(request);
        product.setMerchant(merchant);
        product.setStatus(ProductStatus.ACTIVE);

        Product saved = productRepository.save(product);
        log.info("Product created: id={}, merchantId={}, code={}", saved.getId(), merchantId, saved.getProductCode());
        return productMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public ProductResponseDTO updateProduct(Long merchantId, Long productId, ProductUpdateRequestDTO request) {
        log.info("Updating product id={} for merchantId={}", productId, merchantId);
        Product product = loadOrThrow(merchantId, productId);
        productMapper.updateEntityFromDTO(request, product);
        return productMapper.toResponseDTO(productRepository.save(product));
    }

    @Override
    @Transactional
    public ProductResponseDTO archiveProduct(Long merchantId, Long productId) {
        log.info("Archiving product id={} for merchantId={}", productId, merchantId);
        Product product = loadOrThrow(merchantId, productId);
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            log.info("Product id={} is already ARCHIVED — idempotent", productId);
            return productMapper.toResponseDTO(product);
        }
        product.setStatus(ProductStatus.ARCHIVED);
        return productMapper.toResponseDTO(productRepository.save(product));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long merchantId, Long productId) {
        return productMapper.toResponseDTO(loadOrThrow(merchantId, productId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> listProducts(Long merchantId, ProductStatus status, Pageable pageable) {
        Page<Product> page = (status != null)
                ? productRepository.findAllByMerchantIdAndStatus(merchantId, status, pageable)
                : productRepository.findAllByMerchantId(merchantId, pageable);
        return page.map(productMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public void ensureProductActive(Long merchantId, Long productId) {
        Product product = loadOrThrow(merchantId, productId);
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw CatalogException.productArchived(productId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Product loadOrThrow(Long merchantId, Long productId) {
        return productRepository.findByMerchantIdAndId(merchantId, productId)
                .orElseThrow(() -> CatalogException.productNotFound(merchantId, productId));
    }

    private MerchantAccount loadMerchantOrThrow(Long merchantId) {
        return merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
    }
}
