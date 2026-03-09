package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.dto.PriceUpdateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Price management within a merchant's catalog.
 */
public interface PriceService {

    PriceResponseDTO createPrice(Long merchantId, PriceCreateRequestDTO request);

    PriceResponseDTO updatePrice(Long merchantId, Long priceId, PriceUpdateRequestDTO request);

    PriceResponseDTO deactivatePrice(Long merchantId, Long priceId);

    PriceResponseDTO getPriceById(Long merchantId, Long priceId);

    Page<PriceResponseDTO> listPrices(Long merchantId, Boolean active, Pageable pageable);

    /** Returns the effective price version at the given timestamp, if any. */
    Optional<PriceVersionResponseDTO> getCurrentEffectivePriceVersion(Long merchantId, Long priceId,
                                                                      LocalDateTime asOf);
}
