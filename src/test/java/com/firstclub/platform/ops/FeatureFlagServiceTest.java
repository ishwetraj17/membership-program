package com.firstclub.platform.ops;

import com.firstclub.platform.ops.dto.FeatureFlagResponseDTO;
import com.firstclub.platform.ops.dto.FeatureFlagUpdateRequestDTO;
import com.firstclub.platform.ops.entity.FeatureFlag;
import com.firstclub.platform.ops.repository.FeatureFlagRepository;
import com.firstclub.platform.ops.service.impl.FeatureFlagServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureFlagService — Unit Tests")
class FeatureFlagServiceTest {

    @Mock FeatureFlagRepository featureFlagRepository;
    @InjectMocks FeatureFlagServiceImpl service;
    @Captor ArgumentCaptor<FeatureFlag> flagCaptor;

    private FeatureFlag globalFlag(String key, boolean enabled) {
        return FeatureFlag.builder().flagKey(key).enabled(enabled).scope("GLOBAL").build();
    }

    private FeatureFlag merchantFlag(String key, boolean enabled, Long merchantId) {
        return FeatureFlag.builder().flagKey(key).enabled(enabled).scope("MERCHANT").merchantId(merchantId).build();
    }

    @Nested
    @DisplayName("isEnabled — global lookup")
    class IsEnabledGlobal {

        @Test
        @DisplayName("returns true when global flag is enabled")
        void returnsTrue_whenGlobalFlagEnabled() {
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull("GATEWAY_ROUTING"))
                    .thenReturn(Optional.of(globalFlag("GATEWAY_ROUTING", true)));

            assertThat(service.isEnabled("GATEWAY_ROUTING")).isTrue();
        }

        @Test
        @DisplayName("returns false when global flag is disabled")
        void returnsFalse_whenGlobalFlagDisabled() {
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull("WEBHOOKS"))
                    .thenReturn(Optional.of(globalFlag("WEBHOOKS", false)));

            assertThat(service.isEnabled("WEBHOOKS")).isFalse();
        }

        @Test
        @DisplayName("returns false when flag does not exist")
        void returnsFalse_whenFlagNotFound() {
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull(any()))
                    .thenReturn(Optional.empty());

            assertThat(service.isEnabled("UNKNOWN_FLAG")).isFalse();
        }
    }

    @Nested
    @DisplayName("isEnabled — merchant override")
    class IsEnabledForMerchant {

        @Test
        @DisplayName("merchant override (true) takes precedence over disabled global")
        void merchantOverrideTakesPrecedence_overDisabledGlobal() {
            when(featureFlagRepository.findByFlagKeyAndMerchantId("SANDBOX_ENFORCEMENT", 42L))
                    .thenReturn(Optional.of(merchantFlag("SANDBOX_ENFORCEMENT", true, 42L)));

            assertThat(service.isEnabled("SANDBOX_ENFORCEMENT", 42L)).isTrue();
            verify(featureFlagRepository, never()).findByFlagKeyAndMerchantIdIsNull(any());
        }

        @Test
        @DisplayName("falls back to global when no merchant override exists")
        void fallsBackToGlobal_whenNoMerchantOverride() {
            when(featureFlagRepository.findByFlagKeyAndMerchantId("WEBHOOKS", 7L))
                    .thenReturn(Optional.empty());
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull("WEBHOOKS"))
                    .thenReturn(Optional.of(globalFlag("WEBHOOKS", true)));

            assertThat(service.isEnabled("WEBHOOKS", 7L)).isTrue();
        }

        @Test
        @DisplayName("returns false when neither merchant nor global flag exists")
        void returnsFalse_whenNeitherExists() {
            when(featureFlagRepository.findByFlagKeyAndMerchantId(any(), any()))
                    .thenReturn(Optional.empty());
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull(any()))
                    .thenReturn(Optional.empty());

            assertThat(service.isEnabled("MYSTERY_FLAG", 1L)).isFalse();
        }
    }

    @Nested
    @DisplayName("updateFlag")
    class UpdateFlag {

        @Test
        @DisplayName("updates enabled state on existing global flag")
        void updatesExistingFlag() {
            FeatureFlag existing = globalFlag("REVENUE_RECOG_MODE", false);
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull("REVENUE_RECOG_MODE"))
                    .thenReturn(Optional.of(existing));
            when(featureFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FeatureFlagUpdateRequestDTO req = FeatureFlagUpdateRequestDTO.builder()
                    .enabled(true).build();

            FeatureFlagResponseDTO result = service.updateFlag("REVENUE_RECOG_MODE", req);

            assertThat(result.enabled()).isTrue();
            verify(featureFlagRepository).save(flagCaptor.capture());
            assertThat(flagCaptor.getValue().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("creates new GLOBAL flag when none exists")
        void createsNewGlobalFlag_whenNotExists() {
            when(featureFlagRepository.findByFlagKeyAndMerchantIdIsNull("NEW_FLAG"))
                    .thenReturn(Optional.empty());
            when(featureFlagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            FeatureFlagUpdateRequestDTO req = FeatureFlagUpdateRequestDTO.builder()
                    .enabled(true).configJson("{\"pct\":10}").build();

            FeatureFlagResponseDTO result = service.updateFlag("NEW_FLAG", req);

            assertThat(result.flagKey()).isEqualTo("NEW_FLAG");
            assertThat(result.scope()).isEqualTo("GLOBAL");
            assertThat(result.enabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("listFlags")
    class ListFlags {

        @Test
        @DisplayName("returns all flags mapped to DTOs in alphabetical order")
        void returnsAllFlagsMapped() {
            when(featureFlagRepository.findAllByOrderByFlagKeyAsc())
                    .thenReturn(List.of(
                            globalFlag("A_FLAG", true),
                            globalFlag("B_FLAG", false)));

            List<FeatureFlagResponseDTO> result = service.listFlags();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).flagKey()).isEqualTo("A_FLAG");
            assertThat(result.get(1).flagKey()).isEqualTo("B_FLAG");
        }

        @Test
        @DisplayName("returns empty list when no flags configured")
        void returnsEmptyList_whenNoFlags() {
            when(featureFlagRepository.findAllByOrderByFlagKeyAsc()).thenReturn(List.of());

            assertThat(service.listFlags()).isEmpty();
        }
    }
}
