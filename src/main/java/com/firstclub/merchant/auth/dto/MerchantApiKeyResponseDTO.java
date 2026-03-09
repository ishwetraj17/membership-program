package com.firstclub.merchant.auth.dto;

import com.firstclub.merchant.auth.entity.MerchantApiKey;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantApiKeyStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Safe representation of an API key — does NOT include the raw key or hash.
 */
public record MerchantApiKeyResponseDTO(
        Long id,
        Long merchantId,
        String keyPrefix,
        MerchantApiKeyMode mode,
        List<String> scopes,
        MerchantApiKeyStatus status,
        LocalDateTime lastUsedAt,
        LocalDateTime createdAt
) {
    public static MerchantApiKeyResponseDTO from(MerchantApiKey key, List<String> scopes) {
        return new MerchantApiKeyResponseDTO(
                key.getId(),
                key.getMerchantId(),
                key.getKeyPrefix(),
                key.getMode(),
                scopes,
                key.getStatus(),
                key.getLastUsedAt(),
                key.getCreatedAt()
        );
    }
}
