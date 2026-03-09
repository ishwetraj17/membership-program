package com.firstclub.merchant.auth.dto;

import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantModeUpdateRequestDTO {

    /** Whether sandbox mode is enabled for this merchant. */
    private Boolean sandboxEnabled;

    /** Whether live mode is enabled for this merchant.  Enabling live requires ACTIVE merchant status. */
    private Boolean liveEnabled;

    /**
     * The mode applied by default when no explicit mode is provided.
     * Must be one of the enabled modes: if SANDBOX, sandboxEnabled must be true;
     * if LIVE, liveEnabled must be true.
     */
    @NotNull
    private MerchantApiKeyMode defaultMode;
}
