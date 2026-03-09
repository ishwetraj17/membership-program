package com.firstclub.platform.ops.dto;

import com.firstclub.platform.ops.entity.FeatureFlag;

import java.time.LocalDateTime;

/**
 * Read-only view of a feature flag.
 * Merchant-scoped flags include a non-null merchantId.
 */
public record FeatureFlagResponseDTO(
        String        flagKey,
        boolean       enabled,
        String        scope,
        Long          merchantId,
        String        configJson,
        LocalDateTime updatedAt
) {
    public static FeatureFlagResponseDTO from(FeatureFlag f) {
        return new FeatureFlagResponseDTO(
                f.getFlagKey(), f.isEnabled(), f.getScope(),
                f.getMerchantId(), f.getConfigJson(), f.getUpdatedAt());
    }
}
