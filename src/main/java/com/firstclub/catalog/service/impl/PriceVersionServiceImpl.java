package com.firstclub.catalog.service.impl;

import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.PriceVersionMapper;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.service.PriceVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of {@link PriceVersionService}.
 *
 * Business rules enforced:
 * <ul>
 *   <li>No two versions for the same price may have overlapping effective windows.</li>
 *   <li>When a new version is created, the current open-ended version (effectiveTo == null)
 *       is closed by setting its effectiveTo to the new version's effectiveFrom.</li>
 *   <li>Future-dated versions are allowed.</li>
 *   <li>Past-dated effectiveFrom is rejected — use only current or future dates.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceVersionServiceImpl implements PriceVersionService {

    private final PriceRepository priceRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final PriceVersionMapper priceVersionMapper;

    @Override
    @Transactional
    public PriceVersionResponseDTO createPriceVersion(Long merchantId, Long priceId,
                                                      PriceVersionCreateRequestDTO request) {
        log.info("Creating price version for priceId={}, merchantId={}, effectiveFrom={}",
                priceId, merchantId, request.getEffectiveFrom());

        Price price = loadPriceOrThrow(merchantId, priceId);

        LocalDateTime effectiveFrom = request.getEffectiveFrom();

        // Guard: effectiveFrom must not be in the past (allow up to 1 minute leeway for clock skew)
        if (effectiveFrom.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw CatalogException.effectiveFromInPast();
        }

        // Guard: no existing version starts at or after effectiveFrom (would create an overlap)
        Optional<PriceVersion> laterVersion =
                priceVersionRepository.findTopByPriceIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(
                        priceId, effectiveFrom.minusSeconds(1));
        if (laterVersion.isPresent()) {
            throw CatalogException.overlappingPriceVersion(priceId);
        }

        // Close the current open-ended version, if any
        priceVersionRepository.findTopByPriceIdAndEffectiveToIsNullOrderByEffectiveFromDesc(priceId)
                .ifPresent(prev -> {
                    prev.setEffectiveTo(effectiveFrom);
                    priceVersionRepository.save(prev);
                    log.info("Closed previous price version id={}, effectiveTo={}", prev.getId(), effectiveFrom);
                });

        PriceVersion version = priceVersionMapper.toEntity(request);
        version.setPrice(price);

        PriceVersion saved = priceVersionRepository.save(version);
        log.info("PriceVersion created: id={}, priceId={}", saved.getId(), priceId);
        return priceVersionMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceVersionResponseDTO> listVersions(Long merchantId, Long priceId) {
        loadPriceOrThrow(merchantId, priceId); // tenant guard
        return priceVersionRepository.findByPriceIdOrderByEffectiveFromDesc(priceId)
                .stream()
                .map(priceVersionMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceVersionResponseDTO> resolveEffectiveVersionForTimestamp(Long priceId,
                                                                                  LocalDateTime asOf) {
        return priceVersionRepository
                .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(priceId, asOf)
                .map(priceVersionMapper::toResponseDTO);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Price loadPriceOrThrow(Long merchantId, Long priceId) {
        return priceRepository.findByMerchantIdAndId(merchantId, priceId)
                .orElseThrow(() -> CatalogException.priceNotFound(merchantId, priceId));
    }
}
