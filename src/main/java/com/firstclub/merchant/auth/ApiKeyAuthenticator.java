package com.firstclub.merchant.auth;

import com.firstclub.merchant.auth.entity.MerchantApiKey;
import com.firstclub.merchant.auth.service.MerchantApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Foundation class for merchant API key authentication.
 *
 * <p>Usage in a future Spring Security filter:
 * <pre>{@code
 *   String header = request.getHeader("Authorization");
 *   if (header != null && header.startsWith("ApiKey ")) {
 *       String rawKey = header.substring(7);
 *       apiKeyAuthenticator.authenticate(rawKey)
 *           .ifPresent(key -> {
 *               // set SecurityContext with merchant identity and scopes
 *               SecurityContextHolder.getContext().setAuthentication(
 *                   new ApiKeyAuthenticationToken(key));
 *           });
 *   }
 * }</pre>
 *
 * <p>The authenticator also records the last-used timestamp on successful auth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthenticator {

    private final MerchantApiKeyService merchantApiKeyService;

    /**
     * Authenticates a raw API key string.
     * Updates {@code last_used_at} on successful authentication.
     *
     * @param rawKey the full plaintext key provided by the merchant client
     * @return the matching active {@link MerchantApiKey}, or empty if authentication fails
     */
    public Optional<MerchantApiKey> authenticate(String rawKey) {
        Optional<MerchantApiKey> result = merchantApiKeyService.authenticateApiKey(rawKey);
        result.ifPresent(key -> {
            merchantApiKeyService.updateLastUsed(key.getId());
            log.debug("API key authenticated: prefix={} merchant={}", key.getKeyPrefix(), key.getMerchantId());
        });
        return result;
    }
}
