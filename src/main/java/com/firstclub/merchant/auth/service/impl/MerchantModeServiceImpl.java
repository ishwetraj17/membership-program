package com.firstclub.merchant.auth.service.impl;

import com.firstclub.merchant.auth.dto.MerchantModeResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantModeUpdateRequestDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantMode;
import com.firstclub.merchant.auth.repository.MerchantModeRepository;
import com.firstclub.merchant.auth.service.MerchantModeService;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerchantModeServiceImpl implements MerchantModeService {

    private final MerchantModeRepository merchantModeRepository;
    private final MerchantAccountRepository merchantAccountRepository;

    @Override
    @Transactional
    public MerchantModeResponseDTO getMode(Long merchantId) {
        return MerchantModeResponseDTO.from(getOrCreateMode(merchantId));
    }

    @Override
    @Transactional
    public MerchantModeResponseDTO updateMode(Long merchantId, MerchantModeUpdateRequestDTO request) {
        MerchantAccount merchant = merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Merchant not found: " + merchantId));

        // Enabling live mode is gated on ACTIVE merchant status
        if (Boolean.TRUE.equals(request.getLiveEnabled())
                && merchant.getStatus() != MerchantStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Live mode can only be enabled for ACTIVE merchants");
        }

        MerchantMode mode = getOrCreateMode(merchantId);

        if (request.getSandboxEnabled() != null) {
            mode.setSandboxEnabled(request.getSandboxEnabled());
        }
        if (request.getLiveEnabled() != null) {
            mode.setLiveEnabled(request.getLiveEnabled());
        }
        mode.setDefaultMode(request.getDefaultMode());

        // Validate that defaultMode is consistent with the enabled flags
        if (mode.getDefaultMode() == MerchantApiKeyMode.SANDBOX && !mode.isSandboxEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot set defaultMode to SANDBOX when sandbox is disabled");
        }
        if (mode.getDefaultMode() == MerchantApiKeyMode.LIVE && !mode.isLiveEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot set defaultMode to LIVE when live is not enabled");
        }

        MerchantMode saved = merchantModeRepository.save(mode);
        log.info("Merchant {} mode updated: sandbox={} live={} default={}",
                merchantId, saved.isSandboxEnabled(), saved.isLiveEnabled(), saved.getDefaultMode());
        return MerchantModeResponseDTO.from(saved);
    }

    /**
     * Returns the existing {@link MerchantMode} for the merchant, or lazily creates
     * a default one (sandbox=true, live=false) on first access.
     */
    private MerchantMode getOrCreateMode(Long merchantId) {
        return merchantModeRepository.findById(merchantId)
                .orElseGet(() -> {
                    merchantAccountRepository.findById(merchantId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                    "Merchant not found: " + merchantId));
                    MerchantMode defaults = MerchantMode.builder()
                            .merchantId(merchantId)
                            .build();
                    return merchantModeRepository.save(defaults);
                });
    }
}
