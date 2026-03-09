package com.firstclub.platform.ops.service.impl;

import com.firstclub.platform.ops.dto.FeatureFlagResponseDTO;
import com.firstclub.platform.ops.dto.FeatureFlagUpdateRequestDTO;
import com.firstclub.platform.ops.entity.FeatureFlag;
import com.firstclub.platform.ops.repository.FeatureFlagRepository;
import com.firstclub.platform.ops.service.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FeatureFlagServiceImpl implements FeatureFlagService {

    private final FeatureFlagRepository featureFlagRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagKey) {
        return isEnabled(flagKey, null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String flagKey, Long merchantId) {
        // Merchant-specific override takes precedence
        if (merchantId != null) {
            var merchantFlag = featureFlagRepository.findByFlagKeyAndMerchantId(flagKey, merchantId);
            if (merchantFlag.isPresent()) {
                return merchantFlag.get().isEnabled();
            }
        }
        // Fall back to global
        return featureFlagRepository.findByFlagKeyAndMerchantIdIsNull(flagKey)
                .map(FeatureFlag::isEnabled)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeatureFlagResponseDTO> listFlags() {
        return featureFlagRepository.findAllByOrderByFlagKeyAsc()
                .stream()
                .map(FeatureFlagResponseDTO::from)
                .toList();
    }

    @Override
    @Transactional
    public FeatureFlagResponseDTO updateFlag(String flagKey, FeatureFlagUpdateRequestDTO request) {
        FeatureFlag flag = featureFlagRepository.findByFlagKeyAndMerchantIdIsNull(flagKey)
                .orElseGet(() -> FeatureFlag.builder()
                        .flagKey(flagKey)
                        .scope("GLOBAL")
                        .build());

        flag.setEnabled(request.getEnabled());
        if (request.getConfigJson() != null) {
            flag.setConfigJson(request.getConfigJson());
        }

        return FeatureFlagResponseDTO.from(featureFlagRepository.save(flag));
    }
}
