package com.firstclub.dunning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.service.impl.DunningPolicyServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DunningPolicyService Unit Tests")
class DunningPolicyServiceTest {

    @Mock  private DunningPolicyRepository    policyRepository;
    @Spy   private ObjectMapper               objectMapper = new ObjectMapper();

    @InjectMocks
    private DunningPolicyServiceImpl dunningPolicyService;

    private static final Long MERCHANT_ID = 1L;

    private DunningPolicyCreateRequestDTO validRequest;

    @BeforeEach
    void setUp() {
        validRequest = DunningPolicyCreateRequestDTO.builder()
                .policyCode("STANDARD")
                .retryOffsetsJson("[60, 360, 1440, 4320]")
                .maxAttempts(4)
                .graceDays(7)
                .fallbackToBackupPaymentMethod(false)
                .statusAfterExhaustion("SUSPENDED")
                .build();
    }

    // ── createPolicy ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createPolicy")
    class CreatePolicy {

        @Test
        @DisplayName("valid request → policy saved and DTO returned")
        void createPolicy_valid_success() {
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "STANDARD"))
                    .thenReturn(Optional.empty());
            DunningPolicy saved = DunningPolicy.builder()
                    .id(10L).merchantId(MERCHANT_ID).policyCode("STANDARD")
                    .retryOffsetsJson("[60, 360, 1440, 4320]").maxAttempts(4).graceDays(7)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.save(any(DunningPolicy.class))).thenReturn(saved);

            DunningPolicyResponseDTO dto = dunningPolicyService.createPolicy(MERCHANT_ID, validRequest);

            assertThat(dto.getId()).isEqualTo(10L);
            assertThat(dto.getPolicyCode()).isEqualTo("STANDARD");
            assertThat(dto.getStatusAfterExhaustion()).isEqualTo(DunningTerminalStatus.SUSPENDED);
        }

        @Test
        @DisplayName("invalid retryOffsetsJson — not JSON → 422")
        void createPolicy_invalidRetryOffsetsJson_422() {
            DunningPolicyCreateRequestDTO req = validRequest.toBuilder()
                    .retryOffsetsJson("not-json").build();

            assertThatThrownBy(() -> dunningPolicyService.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("retryOffsetsJson");
        }

        @Test
        @DisplayName("empty offsets array → 422")
        void createPolicy_emptyOffsets_422() {
            DunningPolicyCreateRequestDTO req = validRequest.toBuilder()
                    .retryOffsetsJson("[]").build();

            assertThatThrownBy(() -> dunningPolicyService.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("at least one offset");
        }

        @Test
        @DisplayName("negative offset → 422")
        void createPolicy_negativeOffset_422() {
            DunningPolicyCreateRequestDTO req = validRequest.toBuilder()
                    .retryOffsetsJson("[60, -10, 1440]").build();

            assertThatThrownBy(() -> dunningPolicyService.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("positive integer");
        }

        @Test
        @DisplayName("invalid statusAfterExhaustion → 422")
        void createPolicy_invalidTerminalStatus_422() {
            DunningPolicyCreateRequestDTO req = validRequest.toBuilder()
                    .statusAfterExhaustion("DELETED").build();

            assertThatThrownBy(() -> dunningPolicyService.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("statusAfterExhaustion");
        }

        @Test
        @DisplayName("duplicate policyCode → 409")
        void createPolicy_duplicateCode_409() {
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "STANDARD"))
                    .thenReturn(Optional.of(DunningPolicy.builder().id(5L).build()));

            assertThatThrownBy(() -> dunningPolicyService.createPolicy(MERCHANT_ID, validRequest))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("STANDARD");
        }
    }

    // ── listPolicies ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listPolicies → returns mapped DTOs for merchant")
    void listPolicies_returnsList() {
        DunningPolicy p1 = DunningPolicy.builder().id(1L).merchantId(MERCHANT_ID)
                .policyCode("DEFAULT").retryOffsetsJson("[60]").maxAttempts(1).graceDays(3)
                .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
        DunningPolicy p2 = DunningPolicy.builder().id(2L).merchantId(MERCHANT_ID)
                .policyCode("AGGRESSIVE").retryOffsetsJson("[30, 60]").maxAttempts(2).graceDays(2)
                .statusAfterExhaustion(DunningTerminalStatus.CANCELLED).build();
        when(policyRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(p1, p2));

        List<DunningPolicyResponseDTO> result = dunningPolicyService.listPolicies(MERCHANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(DunningPolicyResponseDTO::getPolicyCode)
                .containsExactlyInAnyOrder("DEFAULT", "AGGRESSIVE");
    }

    // ── getPolicyByCode ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("getPolicyByCode")
    class GetByCode {

        @Test
        @DisplayName("found → returns DTO")
        void getPolicyByCode_found() {
            DunningPolicy policy = DunningPolicy.builder().id(7L).merchantId(MERCHANT_ID)
                    .policyCode("DEFAULT").retryOffsetsJson("[60, 360]").maxAttempts(2).graceDays(5)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "DEFAULT"))
                    .thenReturn(Optional.of(policy));

            DunningPolicyResponseDTO dto = dunningPolicyService.getPolicyByCode(MERCHANT_ID, "DEFAULT");

            assertThat(dto.getId()).isEqualTo(7L);
            assertThat(dto.getMerchantId()).isEqualTo(MERCHANT_ID);
        }

        @Test
        @DisplayName("not found → 404")
        void getPolicyByCode_notFound_404() {
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "MISSING"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> dunningPolicyService.getPolicyByCode(MERCHANT_ID, "MISSING"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("MISSING");
        }
    }

    // ── resolvePolicy ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolvePolicy")
    class ResolvePolicy {

        @Test
        @DisplayName("merchant has DEFAULT policy → returns it without creating one")
        void resolvePolicy_findsDefault() {
            DunningPolicy defaultPolicy = DunningPolicy.builder().id(3L)
                    .merchantId(MERCHANT_ID).policyCode("DEFAULT")
                    .retryOffsetsJson("[60]").maxAttempts(1).graceDays(7)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "DEFAULT"))
                    .thenReturn(Optional.of(defaultPolicy));

            DunningPolicy result = dunningPolicyService.resolvePolicy(MERCHANT_ID);

            assertThat(result.getId()).isEqualTo(3L);
            verify(policyRepository, never()).save(any());
        }

        @Test
        @DisplayName("no DEFAULT but other policy exists → returns it without creating one")
        void resolvePolicy_fallsBackToAny() {
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "DEFAULT"))
                    .thenReturn(Optional.empty());
            DunningPolicy other = DunningPolicy.builder().id(4L)
                    .merchantId(MERCHANT_ID).policyCode("CUSTOM")
                    .retryOffsetsJson("[120]").maxAttempts(1).graceDays(5)
                    .statusAfterExhaustion(DunningTerminalStatus.CANCELLED).build();
            when(policyRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(other));

            DunningPolicy result = dunningPolicyService.resolvePolicy(MERCHANT_ID);

            assertThat(result.getPolicyCode()).isEqualTo("CUSTOM");
            verify(policyRepository, never()).save(any());
        }

        @Test
        @DisplayName("no policies exist → auto-creates DEFAULT and returns it")
        void resolvePolicy_autoCreatesDefault() {
            when(policyRepository.findByMerchantIdAndPolicyCode(MERCHANT_ID, "DEFAULT"))
                    .thenReturn(Optional.empty());
            when(policyRepository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of());
            ArgumentCaptor<DunningPolicy> cap = ArgumentCaptor.forClass(DunningPolicy.class);
            DunningPolicy autoCreated = DunningPolicy.builder().id(99L)
                    .merchantId(MERCHANT_ID).policyCode("DEFAULT")
                    .retryOffsetsJson("[60, 360, 1440, 4320]").maxAttempts(4).graceDays(7)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.save(cap.capture())).thenReturn(autoCreated);

            DunningPolicy result = dunningPolicyService.resolvePolicy(MERCHANT_ID);

            assertThat(result.getPolicyCode()).isEqualTo("DEFAULT");
            assertThat(cap.getValue().getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(cap.getValue().getStatusAfterExhaustion())
                    .isEqualTo(DunningTerminalStatus.SUSPENDED);
        }
    }
}
