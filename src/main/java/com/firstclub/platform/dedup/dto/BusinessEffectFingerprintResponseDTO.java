package com.firstclub.platform.dedup.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Read-only view of a {@code business_effect_fingerprints} row for the admin API.
 */
@Value
@Builder
public class BusinessEffectFingerprintResponseDTO {

    Long          id;
    String        effectType;
    /** First 16 characters of the SHA-256 fingerprint for display; never the full hash. */
    String        fingerprintPrefix;
    String        referenceType;
    Long          referenceId;
    LocalDateTime createdAt;
}
