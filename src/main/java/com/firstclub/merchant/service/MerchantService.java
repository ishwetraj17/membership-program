package com.firstclub.merchant.service;

import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for merchant/tenant management.
 *
 * Implemented by Shwet Raj
 */
public interface MerchantService {

    MerchantResponseDTO createMerchant(MerchantCreateRequestDTO request);

    MerchantResponseDTO updateMerchant(Long id, MerchantUpdateRequestDTO request);

    MerchantResponseDTO updateMerchantStatus(Long id, MerchantStatusUpdateRequestDTO request);

    MerchantResponseDTO getMerchantById(Long id);

    Page<MerchantResponseDTO> getAllMerchants(Pageable pageable);

    Page<MerchantResponseDTO> getMerchantsByStatus(MerchantStatus status, Pageable pageable);

    /**
     * Asserts the merchant exists and is ACTIVE.
     * Called by downstream services before accepting tenant-scoped operations.
     *
     * @throws com.firstclub.merchant.exception.MerchantException if not ACTIVE
     */
    void validateMerchantActive(Long merchantId);
}
