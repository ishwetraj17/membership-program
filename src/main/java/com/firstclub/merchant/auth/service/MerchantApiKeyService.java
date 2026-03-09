package com.firstclub.merchant.auth.service;

import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyResponseDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKey;

import java.util.List;
import java.util.Optional;

public interface MerchantApiKeyService {

    /**
     * Creates a new API key for the merchant.
     * The returned {@link MerchantApiKeyCreateResponseDTO} includes the plaintext
     * {@code rawKey} which is shown ONLY ONCE and never stored.
     */
    MerchantApiKeyCreateResponseDTO createApiKey(Long merchantId, MerchantApiKeyCreateRequestDTO request);

    /** Returns all keys (active and revoked) for the merchant, without raw key material. */
    List<MerchantApiKeyResponseDTO> listApiKeys(Long merchantId);

    /** Marks the specified key as REVOKED.  Revoked keys cannot authenticate. */
    void revokeApiKey(Long merchantId, Long apiKeyId);

    /**
     * Authenticates a full raw API key string.
     * Performs prefix-based lookup + secure hash comparison.
     * Returns the matching active {@link MerchantApiKey} or empty if authentication fails.
     */
    Optional<MerchantApiKey> authenticateApiKey(String rawKey);

    /** Records the current timestamp as the key's last-used time. */
    void updateLastUsed(Long apiKeyId);
}
