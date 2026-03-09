package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Price version management — historical and future pricing snapshots.
 */
public interface PriceVersionService {

    PriceVersionResponseDTO createPriceVersion(Long merchantId, Long priceId,
                                               PriceVersionCreateRequestDTO request);

    List<PriceVersionResponseDTO> listVersions(Long merchantId, Long priceId);

    /** Returns the version that was authoritative at the given timestamp. */
    Optional<PriceVersionResponseDTO> resolveEffectiveVersionForTimestamp(Long priceId,
                                                                          LocalDateTime asOf);
}
