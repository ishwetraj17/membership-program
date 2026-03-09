package com.firstclub.merchant.auth.dto;

import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantMode;

import java.time.LocalDateTime;

public record MerchantModeResponseDTO(
        Long merchantId,
        boolean sandboxEnabled,
        boolean liveEnabled,
        MerchantApiKeyMode defaultMode,
        LocalDateTime updatedAt
) {
    public static MerchantModeResponseDTO from(MerchantMode mode) {
        return new MerchantModeResponseDTO(
                mode.getMerchantId(),
                mode.isSandboxEnabled(),
                mode.isLiveEnabled(),
                mode.getDefaultMode(),
                mode.getUpdatedAt()
        );
    }
}
