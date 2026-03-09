package com.firstclub.merchant.dto;

import com.firstclub.merchant.entity.MerchantStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO returned by all merchant admin endpoints.
 * Never exposes internal association IDs beyond what the API contract needs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantResponseDTO {

    private Long id;
    private String merchantCode;
    private String legalName;
    private String displayName;
    private MerchantStatus status;
    private String defaultCurrency;
    private String countryCode;
    private String timezone;
    private String supportEmail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Embedded settings — null if settings row not yet created. */
    private MerchantSettingsDTO settings;
}
