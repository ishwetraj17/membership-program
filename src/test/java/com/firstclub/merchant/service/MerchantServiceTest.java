package com.firstclub.merchant.service;

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
import com.firstclub.merchant.service.impl.MerchantServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.platform.statemachine.StateMachineValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantServiceImpl Unit Tests")
class MerchantServiceTest {

    @Mock
    private MerchantAccountRepository merchantAccountRepository;

    @Mock
    private MerchantSettingsRepository merchantSettingsRepository;

    @Mock
    private MerchantMapper merchantMapper;

    @Spy
    private StateMachineValidator stateMachineValidator = new StateMachineValidator();

    @InjectMocks
    private MerchantServiceImpl merchantService;

    private MerchantCreateRequestDTO createRequest;
    private MerchantAccount pendingMerchant;
    private MerchantAccount activeMerchant;
    private MerchantAccount closedMerchant;
    private MerchantResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        createRequest = MerchantCreateRequestDTO.builder()
                .merchantCode("ACME_CORP")
                .legalName("Acme Corporation Pvt Ltd")
                .displayName("Acme Corp")
                .supportEmail("support@acmecorp.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();

        pendingMerchant = MerchantAccount.builder()
                .id(1L)
                .merchantCode("ACME_CORP")
                .legalName("Acme Corporation Pvt Ltd")
                .displayName("Acme Corp")
                .status(MerchantStatus.PENDING)
                .build();

        activeMerchant = MerchantAccount.builder()
                .id(1L)
                .merchantCode("ACME_CORP")
                .legalName("Acme Corporation Pvt Ltd")
                .displayName("Acme Corp")
                .status(MerchantStatus.ACTIVE)
                .build();

        closedMerchant = MerchantAccount.builder()
                .id(1L)
                .merchantCode("ACME_CORP")
                .status(MerchantStatus.CLOSED)
                .build();

        responseDTO = new MerchantResponseDTO();
        responseDTO.setId(1L);
        responseDTO.setMerchantCode("ACME_CORP");
        responseDTO.setStatus(MerchantStatus.PENDING);
    }

    @Nested
    @DisplayName("createMerchant")
    class CreateMerchantTests {

        @Test
        @DisplayName("Should create merchant successfully with defaults")
        void shouldCreateMerchantSuccessfully() {
            when(merchantAccountRepository.existsByMerchantCode("ACME_CORP")).thenReturn(false);
            when(merchantAccountRepository.save(any(MerchantAccount.class))).thenReturn(pendingMerchant);
            when(merchantSettingsRepository.save(any(MerchantSettings.class))).thenReturn(new MerchantSettings());
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(pendingMerchant));
            when(merchantMapper.toResponseDTO(pendingMerchant)).thenReturn(responseDTO);

            MerchantResponseDTO result = merchantService.createMerchant(createRequest);

            assertThat(result).isNotNull();
            assertThat(result.getMerchantCode()).isEqualTo("ACME_CORP");
            assertThat(result.getStatus()).isEqualTo(MerchantStatus.PENDING);

            // Auto-created settings must be saved
            verify(merchantSettingsRepository).save(any(MerchantSettings.class));
        }

        @Test
        @DisplayName("Should reject duplicate merchant code")
        void shouldRejectDuplicateMerchantCode() {
            when(merchantAccountRepository.existsByMerchantCode("ACME_CORP")).thenReturn(true);

            assertThatThrownBy(() -> merchantService.createMerchant(createRequest))
                    .isInstanceOf(MerchantException.class)
                    .hasMessageContaining("ACME_CORP")
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("MERCHANT_CODE_TAKEN");

            verify(merchantAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should default currency to INR when not specified")
        void shouldDefaultCurrencyToINR() {
            createRequest.setDefaultCurrency(null);
            when(merchantAccountRepository.existsByMerchantCode(any())).thenReturn(false);
            when(merchantAccountRepository.save(any(MerchantAccount.class))).thenAnswer(inv -> {
                MerchantAccount saved = inv.getArgument(0);
                assertThat(saved.getDefaultCurrency()).isEqualTo("INR");
                saved = pendingMerchant;
                return saved;
            });
            when(merchantSettingsRepository.save(any())).thenReturn(new MerchantSettings());
            when(merchantAccountRepository.findById(any())).thenReturn(Optional.of(pendingMerchant));
            when(merchantMapper.toResponseDTO(any())).thenReturn(responseDTO);

            merchantService.createMerchant(createRequest);
            verify(merchantAccountRepository).save(argThat(a -> "INR".equals(a.getDefaultCurrency())));
        }
    }

    @Nested
    @DisplayName("updateMerchant")
    class UpdateMerchantTests {

        @Test
        @DisplayName("Should update merchant successfully")
        void shouldUpdateMerchantSuccessfully() {
            MerchantUpdateRequestDTO updateRequest = new MerchantUpdateRequestDTO();
            updateRequest.setDisplayName("Acme Updated");

            MerchantResponseDTO updatedResponse = new MerchantResponseDTO();
            updatedResponse.setId(1L);
            updatedResponse.setDisplayName("Acme Updated");

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(pendingMerchant));
            doNothing().when(merchantMapper).updateEntityFromDTO(eq(updateRequest), eq(pendingMerchant));
            when(merchantAccountRepository.save(pendingMerchant)).thenReturn(pendingMerchant);
            when(merchantMapper.toResponseDTO(pendingMerchant)).thenReturn(updatedResponse);

            MerchantResponseDTO result = merchantService.updateMerchant(1L, updateRequest);

            assertThat(result).isNotNull();
            verify(merchantAccountRepository).save(pendingMerchant);
        }

        @Test
        @DisplayName("Should throw when merchant not found on update")
        void shouldThrowWhenMerchantNotFoundOnUpdate() {
            when(merchantAccountRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> merchantService.updateMerchant(999L, new MerchantUpdateRequestDTO()))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("MERCHANT_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("updateMerchantStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should activate a PENDING merchant")
        void shouldActivatePendingMerchant() {
            MerchantStatusUpdateRequestDTO request = new MerchantStatusUpdateRequestDTO();
            request.setStatus(MerchantStatus.ACTIVE);

            MerchantResponseDTO activeResponse = new MerchantResponseDTO();
            activeResponse.setStatus(MerchantStatus.ACTIVE);

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(pendingMerchant));
            when(merchantAccountRepository.save(pendingMerchant)).thenReturn(pendingMerchant);
            when(merchantMapper.toResponseDTO(pendingMerchant)).thenReturn(activeResponse);

            MerchantResponseDTO result = merchantService.updateMerchantStatus(1L, request);

            assertThat(result.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should reject invalid status transition: CLOSED → ACTIVE")
        void shouldRejectTransitionFromClosedToActive() {
            MerchantStatusUpdateRequestDTO request = new MerchantStatusUpdateRequestDTO();
            request.setStatus(MerchantStatus.ACTIVE);

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(closedMerchant));

            // StateMachineValidator throws MembershipException for illegal transitions
            assertThatThrownBy(() -> merchantService.updateMerchantStatus(1L, request))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("CLOSED")
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        @DisplayName("Should reject invalid status transition: PENDING → SUSPENDED")
        void shouldRejectTransitionFromPendingToSuspended() {
            MerchantStatusUpdateRequestDTO request = new MerchantStatusUpdateRequestDTO();
            request.setStatus(MerchantStatus.SUSPENDED);

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(pendingMerchant));

            assertThatThrownBy(() -> merchantService.updateMerchantStatus(1L, request))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("PENDING")
                    .hasMessageContaining("SUSPENDED");
        }
    }

    @Nested
    @DisplayName("validateMerchantActive")
    class ValidateMerchantActiveTests {

        @Test
        @DisplayName("Should pass for ACTIVE merchant")
        void shouldPassForActiveMerchant() {
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(activeMerchant));
            assertThatCode(() -> merchantService.validateMerchantActive(1L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should throw for PENDING merchant")
        void shouldThrowForPendingMerchant() {
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(pendingMerchant));

            assertThatThrownBy(() -> merchantService.validateMerchantActive(1L))
                    .isInstanceOf(MerchantException.class)
                    .extracting(e -> ((MerchantException) e).getErrorCode())
                    .isEqualTo("MERCHANT_NOT_ACTIVE");
        }
    }
}
