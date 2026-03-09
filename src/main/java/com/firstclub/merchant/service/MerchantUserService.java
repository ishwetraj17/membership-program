package com.firstclub.merchant.service;

import com.firstclub.merchant.dto.MerchantUserCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantUserResponseDTO;

import java.util.List;

/**
 * Service contract for managing users within a merchant/tenant.
 *
 * Implemented by Shwet Raj
 */
public interface MerchantUserService {

    MerchantUserResponseDTO addUserToMerchant(Long merchantId, MerchantUserCreateRequestDTO request);

    List<MerchantUserResponseDTO> listMerchantUsers(Long merchantId);

    void removeUserFromMerchant(Long merchantId, Long userId);
}
