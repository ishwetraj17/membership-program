package com.firstclub.merchant.auth;

import com.firstclub.merchant.auth.dto.MerchantModeResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantModeUpdateRequestDTO;
import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import com.firstclub.merchant.auth.entity.MerchantMode;
import com.firstclub.merchant.auth.repository.MerchantModeRepository;
import com.firstclub.merchant.auth.service.impl.MerchantModeServiceImpl;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantModeServiceImpl Unit Tests")
class MerchantModeServiceTest {

    @Mock private MerchantModeRepository modeRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;

    @InjectMocks
    private MerchantModeServiceImpl service;

    private static final Long MERCHANT_ID = 5L;

    private MerchantAccount activeMerchant;
    private MerchantAccount pendingMerchant;

    @BeforeEach
    void setUp() {
        activeMerchant = MerchantAccount.builder()
                .id(MERCHANT_ID).merchantCode("ACT").legalName("Active Ltd")
                .status(MerchantStatus.ACTIVE).build();
        pendingMerchant = MerchantAccount.builder()
                .id(MERCHANT_ID).merchantCode("PND").legalName("Pending Ltd")
                .status(MerchantStatus.PENDING).build();
    }

    // ── GetMode ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMode")
    class GetModeTests {

        @Test
        @DisplayName("returns existing mode as DTO")
        void getMode_existingMode_returnsDTO() {
            MerchantMode existing = MerchantMode.builder()
                    .merchantId(MERCHANT_ID).sandboxEnabled(true).liveEnabled(false)
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(existing));

            MerchantModeResponseDTO result = service.getMode(MERCHANT_ID);

            assertThat(result.merchantId()).isEqualTo(MERCHANT_ID);
            assertThat(result.sandboxEnabled()).isTrue();
            assertThat(result.liveEnabled()).isFalse();
            assertThat(result.defaultMode()).isEqualTo(MerchantApiKeyMode.SANDBOX);
        }

        @Test
        @DisplayName("creates default mode when none exists")
        void getMode_noExistingMode_createsDefault() {
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant));
            when(modeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MerchantModeResponseDTO result = service.getMode(MERCHANT_ID);

            assertThat(result.sandboxEnabled()).isTrue();
            assertThat(result.liveEnabled()).isFalse();
            assertThat(result.defaultMode()).isEqualTo(MerchantApiKeyMode.SANDBOX);
            verify(modeRepository).save(any(MerchantMode.class));
        }

        @Test
        @DisplayName("404 when merchant does not exist and mode is absent")
        void getMode_merchantNotFound_throws404() {
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMode(MERCHANT_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Merchant not found");
        }
    }

    // ── UpdateMode ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateMode")
    class UpdateModeTests {

        private MerchantMode existingMode() {
            return MerchantMode.builder()
                    .merchantId(MERCHANT_ID).sandboxEnabled(true).liveEnabled(false)
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();
        }

        @Test
        @DisplayName("valid sandbox-only update succeeds")
        void updateMode_sandboxOnly_succeeds() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant));
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(existingMode()));
            when(modeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .sandboxEnabled(true).liveEnabled(false)
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();

            MerchantModeResponseDTO result = service.updateMode(MERCHANT_ID, req);

            assertThat(result.sandboxEnabled()).isTrue();
            assertThat(result.defaultMode()).isEqualTo(MerchantApiKeyMode.SANDBOX);
        }

        @Test
        @DisplayName("enabling live mode for ACTIVE merchant succeeds")
        void updateMode_enableLive_activeMerchant_succeeds() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant));
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(existingMode()));
            when(modeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .sandboxEnabled(true).liveEnabled(true)
                    .defaultMode(MerchantApiKeyMode.LIVE).build();

            MerchantModeResponseDTO result = service.updateMode(MERCHANT_ID, req);

            assertThat(result.liveEnabled()).isTrue();
            assertThat(result.defaultMode()).isEqualTo(MerchantApiKeyMode.LIVE);
        }

        @Test
        @DisplayName("enabling live mode for PENDING merchant → 400")
        void updateMode_enableLive_pendingMerchant_throws400() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(pendingMerchant));

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .sandboxEnabled(true).liveEnabled(true)
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();

            assertThatThrownBy(() -> service.updateMode(MERCHANT_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Live mode can only be enabled for ACTIVE merchants");
        }

        @Test
        @DisplayName("defaultMode=SANDBOX when sandboxEnabled=false → 400")
        void updateMode_defaultSandboxWhenDisabled_throws400() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant));
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(existingMode()));

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .sandboxEnabled(false).liveEnabled(true)
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();

            assertThatThrownBy(() -> service.updateMode(MERCHANT_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Cannot set defaultMode to SANDBOX when sandbox is disabled");
        }

        @Test
        @DisplayName("defaultMode=LIVE when liveEnabled=false → 400")
        void updateMode_defaultLiveWhenNotEnabled_throws400() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(activeMerchant));
            when(modeRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(existingMode()));

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .sandboxEnabled(true).liveEnabled(false)
                    .defaultMode(MerchantApiKeyMode.LIVE).build();

            assertThatThrownBy(() -> service.updateMode(MERCHANT_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Cannot set defaultMode to LIVE when live is not enabled");
        }

        @Test
        @DisplayName("404 when merchant not found")
        void updateMode_merchantNotFound_throws404() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            MerchantModeUpdateRequestDTO req = MerchantModeUpdateRequestDTO.builder()
                    .defaultMode(MerchantApiKeyMode.SANDBOX).build();

            assertThatThrownBy(() -> service.updateMode(MERCHANT_ID, req))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Merchant not found");
        }
    }
}
