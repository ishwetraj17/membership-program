package com.firstclub.platform.version;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Default {@link MerchantApiVersionService} implementation.
 *
 * <p>Uses upsert semantics: if a pin already exists for the merchant the
 * existing entity is updated; otherwise a new row is inserted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantApiVersionServiceImpl implements MerchantApiVersionService {

    private final MerchantApiVersionRepository repository;

    @Override
    @Transactional
    public MerchantApiVersion pinVersion(Long merchantId, String version, LocalDate effectiveFrom) {
        // Validate the version string early — throws IllegalArgumentException on bad input.
        ApiVersion.fromString(version);

        LocalDate from = effectiveFrom != null ? effectiveFrom : LocalDate.now();

        MerchantApiVersion pin = repository.findByMerchantId(merchantId)
                .orElseGet(() -> MerchantApiVersion.builder()
                        .merchantId(merchantId)
                        .build());

        pin.setPinnedVersion(version);
        pin.setEffectiveFrom(from);

        MerchantApiVersion saved = repository.save(pin);
        log.info("Merchant {} pinned to API version {} effective {}", merchantId, version, from);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApiVersion> resolvePin(Long merchantId) {
        return repository.findByMerchantId(merchantId)
                .map(pin -> ApiVersion.parseOrDefault(pin.getPinnedVersion()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MerchantApiVersion> findPin(Long merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    public void removePin(Long merchantId) {
        repository.findByMerchantId(merchantId).ifPresent(pin -> {
            repository.delete(pin);
            log.info("Removed API version pin for merchant {}", merchantId);
        });
    }
}
