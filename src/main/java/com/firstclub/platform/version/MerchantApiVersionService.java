package com.firstclub.platform.version;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Contract for managing per-merchant API version pins.
 *
 * <p>A version pin controls which API version is used for a merchant's
 * requests when they do not include an explicit {@code X-API-Version} header.
 *
 * @see MerchantApiVersionServiceImpl
 * @see ApiVersionedMapper
 */
public interface MerchantApiVersionService {

    /**
     * Pin — or update — the API version for a merchant.
     *
     * <p>If a pin already exists for the merchant it is updated in-place
     * (upsert semantics). The version string is validated via
     * {@link ApiVersion#fromString(String)} before persistence.
     *
     * @param merchantId    tenant merchant identifier
     * @param version       version string in {@code YYYY-MM-DD} format
     * @param effectiveFrom when the pin takes effect; defaults to today if null
     * @return the persisted {@link MerchantApiVersion} record
     * @throws IllegalArgumentException if {@code version} is not a valid
     *                                  {@code YYYY-MM-DD} string
     */
    MerchantApiVersion pinVersion(Long merchantId, String version, LocalDate effectiveFrom);

    /**
     * Resolve the pinned {@link ApiVersion} for a merchant.
     *
     * @param merchantId tenant merchant identifier
     * @return the pinned version, or empty if no pin is configured
     */
    Optional<ApiVersion> resolvePin(Long merchantId);

    /**
     * Retrieve the raw pin record.
     *
     * @param merchantId tenant merchant identifier
     * @return the entity, or empty if not pinned
     */
    Optional<MerchantApiVersion> findPin(Long merchantId);

    /**
     * Remove an existing version pin for a merchant.
     * After this the merchant falls back to {@link ApiVersion#DEFAULT}.
     *
     * @param merchantId tenant merchant identifier
     */
    void removePin(Long merchantId);
}
