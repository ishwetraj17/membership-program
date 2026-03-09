package com.firstclub.billing.tax.service;

import com.firstclub.billing.tax.dto.*;

public interface TaxProfileService {

    TaxProfileResponseDTO createOrUpdateMerchantTaxProfile(Long merchantId, TaxProfileCreateOrUpdateRequestDTO request);

    TaxProfileResponseDTO getMerchantTaxProfile(Long merchantId);

    CustomerTaxProfileResponseDTO createOrUpdateCustomerTaxProfile(Long customerId, CustomerTaxProfileCreateOrUpdateRequestDTO request);

    CustomerTaxProfileResponseDTO getCustomerTaxProfile(Long customerId);
}
