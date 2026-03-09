package com.firstclub.merchant.service.impl;

import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantSettings;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.mapper.MerchantMapper;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.merchant.repository.MerchantSettingsRepository;
import com.firstclub.merchant.service.MerchantService;
import com.firstclub.platform.statemachine.StateMachineValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link MerchantService}.
 *
 * Business rules:
 * - merchantCode is immutable after creation
 * - Status transitions are validated by {@link StateMachineValidator}
 * - MerchantSettings are created automatically on merchant creation
 *
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantServiceImpl implements MerchantService {

    private final MerchantAccountRepository merchantAccountRepository;
    private final MerchantSettingsRepository merchantSettingsRepository;
    private final MerchantMapper merchantMapper;
    private final StateMachineValidator stateMachineValidator;

    @Override
    @Transactional
    public MerchantResponseDTO createMerchant(MerchantCreateRequestDTO request) {
        log.info("Creating merchant with code: {}", request.getMerchantCode());

        if (merchantAccountRepository.existsByMerchantCode(request.getMerchantCode())) {
            throw MerchantException.merchantCodeTaken(request.getMerchantCode());
        }

        MerchantAccount account = MerchantAccount.builder()
                .merchantCode(request.getMerchantCode())
                .legalName(request.getLegalName())
                .displayName(request.getDisplayName())
                .supportEmail(request.getSupportEmail())
                .defaultCurrency(request.getDefaultCurrency() != null ? request.getDefaultCurrency() : "INR")
                .countryCode(request.getCountryCode())
                .timezone(request.getTimezone())
                .status(MerchantStatus.PENDING)
                .build();

        MerchantAccount saved = merchantAccountRepository.save(account);

        // Auto-create default settings for every new merchant
        MerchantSettings settings = MerchantSettings.builder()
                .merchant(saved)
                .build();
        merchantSettingsRepository.save(settings);

        // Reload to pick up the settings association
        MerchantAccount reloaded = merchantAccountRepository.findById(saved.getId())
                .orElseThrow(() -> MerchantException.merchantNotFound(saved.getId()));

        log.info("Merchant created: id={}, code={}", reloaded.getId(), reloaded.getMerchantCode());
        return merchantMapper.toResponseDTO(reloaded);
    }

    @Override
    @Transactional
    public MerchantResponseDTO updateMerchant(Long id, MerchantUpdateRequestDTO request) {
        log.info("Updating merchant id={}", id);

        MerchantAccount account = loadOrThrow(id);
        merchantMapper.updateEntityFromDTO(request, account);
        merchantAccountRepository.save(account);

        return merchantMapper.toResponseDTO(account);
    }

    @Override
    @Transactional
    public MerchantResponseDTO updateMerchantStatus(Long id, MerchantStatusUpdateRequestDTO request) {
        log.info("Updating merchant id={} status → {}", id, request.getStatus());

        MerchantAccount account = loadOrThrow(id);
        MerchantStatus current = account.getStatus();
        MerchantStatus target = request.getStatus();

        stateMachineValidator.validate("MERCHANT", current, target);

        account.setStatus(target);
        merchantAccountRepository.save(account);

        log.info("Merchant id={} status changed: {} → {}", id, current, target);
        return merchantMapper.toResponseDTO(account);
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantResponseDTO getMerchantById(Long id) {
        return merchantMapper.toResponseDTO(loadOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MerchantResponseDTO> getAllMerchants(Pageable pageable) {
        return merchantAccountRepository.findAll(pageable)
                .map(merchantMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MerchantResponseDTO> getMerchantsByStatus(MerchantStatus status, Pageable pageable) {
        return merchantAccountRepository.findByStatus(status, pageable)
                .map(merchantMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public void validateMerchantActive(Long merchantId) {
        MerchantAccount account = loadOrThrow(merchantId);
        if (account.getStatus() != MerchantStatus.ACTIVE) {
            throw MerchantException.merchantNotActive(merchantId);
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private MerchantAccount loadOrThrow(Long id) {
        return merchantAccountRepository.findById(id)
                .orElseThrow(() -> MerchantException.merchantNotFound(id));
    }
}
