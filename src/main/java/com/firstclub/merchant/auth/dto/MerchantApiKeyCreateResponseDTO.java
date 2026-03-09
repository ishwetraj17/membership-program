package com.firstclub.merchant.auth.dto;

import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantApiKeyStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Returned ONCE at key creation time.
 * The {@code rawKey} field contains the full plaintext API key — it is never
 * stored and cannot be retrieved again.  The client must save it securely.
 */
public record MerchantApiKeyCreateResponseDTO(
        Long id,
        Long merchantId,
        String keyPrefix,
        /** Full plaintext key — shown only at creation, never persisted. */
        String rawKey,
        MerchantApiKeyMode mode,
        List<String> scopes,
        MerchantApiKeyStatus status,
        LocalDateTime createdAt
) {}
