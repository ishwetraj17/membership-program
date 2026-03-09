package com.firstclub.billing.tax.service.impl;

import com.firstclub.billing.tax.dto.*;
import com.firstclub.billing.tax.entity.CustomerTaxProfile;
import com.firstclub.billing.tax.entity.TaxProfile;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.billing.tax.service.TaxProfileService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaxProfileServiceImpl implements TaxProfileService {

    private final TaxProfileRepository taxProfileRepository;
    private final CustomerTaxProfileRepository customerTaxProfileRepository;

    @Override
    @Transactional
    public TaxProfileResponseDTO createOrUpdateMerchantTaxProfile(Long merchantId,
                                                                   TaxProfileCreateOrUpdateRequestDTO request) {
        TaxProfile profile = taxProfileRepository.findByMerchantId(merchantId)
                .orElseGet(() -> TaxProfile.builder().merchantId(merchantId).build());

        profile.setGstin(request.getGstin());
        profile.setLegalStateCode(request.getLegalStateCode());
        profile.setRegisteredBusinessName(request.getRegisteredBusinessName());
        profile.setTaxMode(request.getTaxMode());

        TaxProfile saved = taxProfileRepository.save(profile);
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TaxProfileResponseDTO getMerchantTaxProfile(Long merchantId) {
        TaxProfile profile = taxProfileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new MembershipException(
                        "Tax profile not found for merchant: " + merchantId,
                        "TAX_PROFILE_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        return toDto(profile);
    }

    @Override
    @Transactional
    public CustomerTaxProfileResponseDTO createOrUpdateCustomerTaxProfile(Long customerId,
                                                                           CustomerTaxProfileCreateOrUpdateRequestDTO request) {
        CustomerTaxProfile profile = customerTaxProfileRepository.findByCustomerId(customerId)
                .orElseGet(() -> CustomerTaxProfile.builder().customerId(customerId).build());

        profile.setGstin(request.getGstin());
        profile.setStateCode(request.getStateCode());
        profile.setEntityType(request.getEntityType());
        profile.setTaxExempt(request.isTaxExempt());

        CustomerTaxProfile saved = customerTaxProfileRepository.save(profile);
        return toCustomerDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerTaxProfileResponseDTO getCustomerTaxProfile(Long customerId) {
        CustomerTaxProfile profile = customerTaxProfileRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new MembershipException(
                        "Tax profile not found for customer: " + customerId,
                        "CUSTOMER_TAX_PROFILE_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        return toCustomerDto(profile);
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private TaxProfileResponseDTO toDto(TaxProfile p) {
        return TaxProfileResponseDTO.builder()
                .id(p.getId())
                .merchantId(p.getMerchantId())
                .gstin(p.getGstin())
                .legalStateCode(p.getLegalStateCode())
                .registeredBusinessName(p.getRegisteredBusinessName())
                .taxMode(p.getTaxMode())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private CustomerTaxProfileResponseDTO toCustomerDto(CustomerTaxProfile p) {
        return CustomerTaxProfileResponseDTO.builder()
                .id(p.getId())
                .customerId(p.getCustomerId())
                .gstin(p.getGstin())
                .stateCode(p.getStateCode())
                .entityType(p.getEntityType())
                .taxExempt(p.isTaxExempt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
