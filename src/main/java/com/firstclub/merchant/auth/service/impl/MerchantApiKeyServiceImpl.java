package com.firstclub.merchant.auth.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyResponseDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKey;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantApiKeyStatus;
import com.firstclub.merchant.auth.repository.MerchantApiKeyRepository;
import com.firstclub.merchant.auth.service.MerchantApiKeyService;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantApiKeyServiceImpl implements MerchantApiKeyService {

    /**
     * Key format: fc_{mode}_{16 random hex chars}_{40 random hex chars}
     *
     * Examples:
     *   Sandbox: fc_sb_a1b2c3d4e5f6a7b8_aabbccddaabbccddaabbccddaabbccddaabbccdd
     *   Live:    fc_lv_a1b2c3d4e5f6a7b8_aabbccddaabbccddaabbccddaabbccddaabbccdd
     *
     * The prefix (first 22 chars: "fc_{mode}_" + 16 hex) is stored plaintext for O(1) lookup.
     * Only SHA-256(rawKey) is stored — the raw key is shown once at creation time.
     */
    public static final int PREFIX_LENGTH = 22;
    private static final int PREFIX_RANDOM_BYTES = 8;  // → 16 hex chars
    private static final int SECRET_BYTES = 20;         // → 40 hex chars

    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public MerchantApiKeyCreateResponseDTO createApiKey(Long merchantId,
                                                        MerchantApiKeyCreateRequestDTO request) {
        merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Merchant not found: " + merchantId));

        String rawKey     = generateRawKey(request.getMode());
        String keyPrefix  = rawKey.substring(0, PREFIX_LENGTH);
        String keyHash    = hashKey(rawKey);
        String scopesJson = serializeScopes(request.getScopes());

        MerchantApiKey key = MerchantApiKey.builder()
                .merchantId(merchantId)
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .mode(request.getMode())
                .scopesJson(scopesJson)
                .status(MerchantApiKeyStatus.ACTIVE)
                .build();

        MerchantApiKey saved = merchantApiKeyRepository.save(key);
        log.info("API key created for merchant={} prefix={} mode={}", merchantId, keyPrefix, request.getMode());

        return new MerchantApiKeyCreateResponseDTO(
                saved.getId(), merchantId, keyPrefix, rawKey,
                saved.getMode(), request.getScopes(), saved.getStatus(), saved.getCreatedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantApiKeyResponseDTO> listApiKeys(Long merchantId) {
        return merchantApiKeyRepository.findByMerchantId(merchantId)
                .stream()
                .map(k -> MerchantApiKeyResponseDTO.from(k, deserializeScopes(k.getScopesJson())))
                .toList();
    }

    @Override
    @Transactional
    public void revokeApiKey(Long merchantId, Long apiKeyId) {
        MerchantApiKey key = merchantApiKeyRepository.findByMerchantIdAndId(merchantId, apiKeyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API key not found: " + apiKeyId));
        key.setStatus(MerchantApiKeyStatus.REVOKED);
        merchantApiKeyRepository.save(key);
        log.info("API key {} revoked for merchant={}", apiKeyId, merchantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MerchantApiKey> authenticateApiKey(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX_LENGTH) {
            return Optional.empty();
        }
        String prefix = rawKey.substring(0, PREFIX_LENGTH);
        Optional<MerchantApiKey> keyOpt = merchantApiKeyRepository.findByKeyPrefix(prefix);
        if (keyOpt.isEmpty()) {
            return Optional.empty();
        }
        MerchantApiKey key = keyOpt.get();
        if (key.getStatus() != MerchantApiKeyStatus.ACTIVE) {
            return Optional.empty();
        }
        // Constant-time-equivalent check: compare hashes, not raw values
        if (!hashKey(rawKey).equals(key.getKeyHash())) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    @Override
    @Transactional
    public void updateLastUsed(Long apiKeyId) {
        merchantApiKeyRepository.findById(apiKeyId).ifPresent(key -> {
            key.setLastUsedAt(LocalDateTime.now());
            merchantApiKeyRepository.save(key);
        });
    }

    // ── Key generation ────────────────────────────────────────────────────────

    private String generateRawKey(MerchantApiKeyMode mode) {
        SecureRandom rand = new SecureRandom();
        byte[] prefixBytes = new byte[PREFIX_RANDOM_BYTES];
        byte[] secretBytes = new byte[SECRET_BYTES];
        rand.nextBytes(prefixBytes);
        rand.nextBytes(secretBytes);
        String modeCode = mode == MerchantApiKeyMode.SANDBOX ? "sb" : "lv";
        return "fc_" + modeCode + "_"
                + HexFormat.of().formatHex(prefixBytes) + "_"
                + HexFormat.of().formatHex(secretBytes);
    }

    String hashKey(String rawKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Scope serialization ───────────────────────────────────────────────────

    private String serializeScopes(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize scopes", e);
        }
    }

    private List<String> deserializeScopes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize scopes JSON, returning empty list: {}", json);
            return List.of();
        }
    }
}
