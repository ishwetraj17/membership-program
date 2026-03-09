package com.firstclub.merchant.dto;

import com.firstclub.merchant.entity.SettlementFrequency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO representing the settings for a merchant account.
 * Embedded inside {@link MerchantResponseDTO}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantSettingsDTO {

    private Long id;
    private Boolean webhookEnabled;
    private SettlementFrequency settlementFrequency;
    private Boolean autoRetryEnabled;
    private Integer defaultGraceDays;
    private String defaultDunningPolicyCode;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
