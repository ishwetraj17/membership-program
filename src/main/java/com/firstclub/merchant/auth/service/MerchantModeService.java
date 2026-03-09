package com.firstclub.merchant.auth.service;

import com.firstclub.merchant.auth.dto.MerchantModeResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantModeUpdateRequestDTO;

public interface MerchantModeService {

    /**
     * Returns the current mode configuration for the merchant.
     * If no configuration has been set yet, a default is created (sandbox=true, live=false).
     */
    MerchantModeResponseDTO getMode(Long merchantId);

    /**
     * Updates the mode configuration for the merchant.
     * Business rules:
     * <ul>
     *   <li>Enabling live mode requires the merchant to have ACTIVE status.</li>
     *   <li>The {@code defaultMode} must be consistent with the enabled flags.</li>
     * </ul>
     */
    MerchantModeResponseDTO updateMode(Long merchantId, MerchantModeUpdateRequestDTO request);
}
