package com.firstclub.platform.version;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the <em>effective</em> {@link ApiVersion} for a request by applying
 * the three-tier precedence hierarchy:
 *
 * <ol>
 *   <li><strong>Explicit header</strong> — {@code X-API-Version} value forwarded
 *       from the request (highest priority; client always wins)</li>
 *   <li><strong>Merchant pin</strong> — the version stored in
 *       {@code merchant_api_versions} for this merchant</li>
 *   <li><strong>Platform default</strong> — {@link ApiVersion#DEFAULT}
 *       (fallback; always safe)</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   ApiVersion effective = apiVersionedMapper.resolveEffectiveVersion(
 *       requestContext.getMerchantId(),
 *       requestContext.getApiVersion()   // raw header string, may be null
 *   );
 *
 *   if (effective.isAfterOrEqual(ApiVersion.V_2025_01)) {
 *       return enrichedResponse(entity);
 *   }
 *   return legacyResponse(entity);
 * }</pre>
 *
 * @see MerchantApiVersionService
 * @see ApiVersion
 */
@Component
@RequiredArgsConstructor
public class ApiVersionedMapper {

    private final MerchantApiVersionService merchantApiVersionService;

    /**
     * Returns the effective {@link ApiVersion} using the three-tier hierarchy.
     *
     * @param merchantId    tenant merchant id (may be {@code null} for platform-admin requests)
     * @param headerVersion raw value of the {@code X-API-Version} request header
     *                      (may be {@code null} or blank)
     * @return the resolved {@link ApiVersion}; never {@code null}
     */
    public ApiVersion resolveEffectiveVersion(Long merchantId, String headerVersion) {
        // Tier 1: explicit header wins
        if (headerVersion != null && !headerVersion.isBlank()) {
            return ApiVersion.parseOrDefault(headerVersion);
        }

        // Tier 2: merchant-level pin
        if (merchantId != null) {
            return merchantApiVersionService
                    .resolvePin(merchantId)
                    .orElse(ApiVersion.DEFAULT);
        }

        // Tier 3: platform default
        return ApiVersion.DEFAULT;
    }
}
