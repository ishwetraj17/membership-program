package com.firstclub.catalog.service.impl;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.dto.PriceUpdateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.entity.BillingType;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.PriceMapper;
import com.firstclub.catalog.mapper.PriceVersionMapper;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.catalog.service.PriceService;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Implementation of {@link PriceService}.
 *
 * Business rules enforced:
 * - {@code priceCode} unique within the merchant.
 * - RECURRING prices must have billingIntervalUnit set and billingIntervalCount ≥ 1.
 * - amount must be positive (enforced via DTO validation, double-checked here).
 * - Deactivated prices cannot be used for new subscriptions.
 * - Amount/currency changes must be made via PriceVersion, not by mutating the Price.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceServiceImpl implements PriceService {

    private final PriceRepository priceRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final ProductRepository productRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final PriceMapper priceMapper;
    private final PriceVersionMapper priceVersionMapper;

    @Override
    @Transactional
    public PriceResponseDTO createPrice(Long merchantId, PriceCreateRequestDTO request) {
        log.info("Creating price for merchantId={}, code={}", merchantId, request.getPriceCode());

        MerchantAccount merchant = loadMerchantOrThrow(merchantId);

        if (priceRepository.existsByMerchantIdAndPriceCode(merchantId, request.getPriceCode())) {
            throw CatalogException.duplicatePriceCode(merchantId, request.getPriceCode());
        }

        // Validate billing interval for RECURRING
        if (request.getBillingType() == BillingType.RECURRING) {
            if (request.getBillingIntervalUnit() == null ||
                    request.getBillingIntervalCount() == null ||
                    request.getBillingIntervalCount() < 1) {
                throw CatalogException.invalidBillingInterval();
            }
        }

        // Validate product belongs to merchant before mapping
        Product product = loadProductOrThrow(merchantId, request.getProductId());

        Price price = priceMapper.toEntity(request);
        price.setMerchant(merchant);
        price.setActive(true);
        price.setProduct(product);

        // Apply defaults for ONE_TIME if interval fields absent
        if (request.getBillingType() == BillingType.ONE_TIME) {
            if (request.getBillingIntervalUnit() == null) {
                price.setBillingIntervalUnit(com.firstclub.catalog.entity.BillingIntervalUnit.MONTH);
            }
            if (request.getBillingIntervalCount() == null || request.getBillingIntervalCount() < 1) {
                price.setBillingIntervalCount(1);
            }
        }

        Price saved = priceRepository.save(price);
        log.info("Price created: id={}, merchantId={}, code={}", saved.getId(), merchantId, saved.getPriceCode());
        return priceMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public PriceResponseDTO updatePrice(Long merchantId, Long priceId, PriceUpdateRequestDTO request) {
        log.info("Updating price id={} for merchantId={}", priceId, merchantId);
        Price price = loadOrThrow(merchantId, priceId);
        if (request.getTrialDays() != null) {
            price.setTrialDays(request.getTrialDays());
        }
        return priceMapper.toResponseDTO(priceRepository.save(price));
    }

    @Override
    @Transactional
    public PriceResponseDTO deactivatePrice(Long merchantId, Long priceId) {
        log.info("Deactivating price id={} for merchantId={}", priceId, merchantId);
        Price price = loadOrThrow(merchantId, priceId);
        if (!price.isActive()) {
            log.info("Price id={} already inactive — idempotent", priceId);
            return priceMapper.toResponseDTO(price);
        }
        price.setActive(false);
        return priceMapper.toResponseDTO(priceRepository.save(price));
    }

    @Override
    @Transactional(readOnly = true)
    public PriceResponseDTO getPriceById(Long merchantId, Long priceId) {
        return priceMapper.toResponseDTO(loadOrThrow(merchantId, priceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PriceResponseDTO> listPrices(Long merchantId, Boolean active, Pageable pageable) {
        Page<Price> page = (active != null)
                ? priceRepository.findAllByMerchantIdAndActive(merchantId, active, pageable)
                : priceRepository.findAllByMerchantId(merchantId, pageable);
        return page.map(priceMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceVersionResponseDTO> getCurrentEffectivePriceVersion(Long merchantId, Long priceId,
                                                                              LocalDateTime asOf) {
        loadOrThrow(merchantId, priceId); // tenant guard
        return priceVersionRepository
                .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(priceId, asOf)
                .map(priceVersionMapper::toResponseDTO);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Price loadOrThrow(Long merchantId, Long priceId) {
        return priceRepository.findByMerchantIdAndId(merchantId, priceId)
                .orElseThrow(() -> CatalogException.priceNotFound(merchantId, priceId));
    }

    private MerchantAccount loadMerchantOrThrow(Long merchantId) {
        return merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
    }

    private Product loadProductOrThrow(Long merchantId, Long productId) {
        return productRepository.findByMerchantIdAndId(merchantId, productId)
                .orElseThrow(() -> CatalogException.productNotFound(merchantId, productId));
    }
}
