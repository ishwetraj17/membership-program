package com.firstclub.risk.service;

import com.firstclub.risk.dto.ManualReviewCaseResponseDTO;
import com.firstclub.risk.dto.ManualReviewResolveRequestDTO;
import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;
import com.firstclub.risk.repository.ManualReviewCaseRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManualReviewService Unit Tests")
class ManualReviewServiceTest {

    @Mock
    private ManualReviewCaseRepository caseRepository;

    @InjectMocks
    private ManualReviewService service;

    private static final Long CASE_ID      = 1L;
    private static final Long MERCHANT_ID  = 10L;
    private static final Long INTENT_ID    = 20L;
    private static final Long CUSTOMER_ID  = 30L;

    private ManualReviewCase caseWith(ReviewCaseStatus status) {
        return ManualReviewCase.builder()
                .id(CASE_ID).merchantId(MERCHANT_ID).paymentIntentId(INTENT_ID)
                .customerId(CUSTOMER_ID).status(status).build();
    }

    @Nested
    @DisplayName("createCase")
    class CreateCase {

        @Test
        @DisplayName("saves new OPEN case and returns it")
        void createsOpenCase() {
            ManualReviewCase saved = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.save(any())).thenReturn(saved);

            ManualReviewCase result = service.createCase(MERCHANT_ID, INTENT_ID, CUSTOMER_ID);

            assertThat(result.getStatus()).isEqualTo(ReviewCaseStatus.OPEN);
            ArgumentCaptor<ManualReviewCase> captor = ArgumentCaptor.forClass(ManualReviewCase.class);
            verify(caseRepository).save(captor.capture());
            assertThat(captor.getValue().getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(captor.getValue().getPaymentIntentId()).isEqualTo(INTENT_ID);
        }
    }

    @Nested
    @DisplayName("listCases")
    class ListCases {

        @Test
        @DisplayName("null status → returns all cases via unfiltered query")
        void nullStatus_allCases() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.findAllByOrderByCreatedAtDesc(any()))
                    .thenReturn(new PageImpl<>(List.of(c)));

            Page<ManualReviewCaseResponseDTO> page = service.listCases(null, Pageable.unpaged());

            assertThat(page.getTotalElements()).isEqualTo(1);
            verify(caseRepository, never()).findByStatusOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("specific status → filters via status query")
        void withStatus_filteredQuery() {
            when(caseRepository.findByStatusOrderByCreatedAtDesc(eq(ReviewCaseStatus.OPEN), any()))
                    .thenReturn(Page.empty());

            Page<ManualReviewCaseResponseDTO> page = service.listCases(ReviewCaseStatus.OPEN, Pageable.unpaged());

            assertThat(page).isEmpty();
            verify(caseRepository).findByStatusOrderByCreatedAtDesc(eq(ReviewCaseStatus.OPEN), any());
        }
    }

    @Nested
    @DisplayName("assignCase")
    class AssignCase {

        @Test
        @DisplayName("OPEN case can be assigned to a user")
        void openCase_assigned() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            ManualReviewCaseResponseDTO result = service.assignCase(CASE_ID, 42L);

            assertThat(result.assignedTo()).isEqualTo(42L);
        }

        @Test
        @DisplayName("APPROVED (terminal) case cannot be assigned — 409 CONFLICT")
        void terminalCase_throwsConflict() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.APPROVED);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));

            assertThatThrownBy(() -> service.assignCase(CASE_ID, 42L))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("not found → EntityNotFoundException")
        void notFound_throws() {
            when(caseRepository.findById(999L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.assignCase(999L, 1L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("resolveCase")
    class ResolveCase {

        @Test
        @DisplayName("OPEN → APPROVED is a valid transition")
        void openToApproved_succeeds() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.APPROVED).build();
            ManualReviewCaseResponseDTO result = service.resolveCase(CASE_ID, req);

            assertThat(result.status()).isEqualTo(ReviewCaseStatus.APPROVED);
        }

        @Test
        @DisplayName("OPEN → REJECTED is a valid transition")
        void openToRejected_succeeds() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.REJECTED).build();
            ManualReviewCaseResponseDTO result = service.resolveCase(CASE_ID, req);

            assertThat(result.status()).isEqualTo(ReviewCaseStatus.REJECTED);
        }

        @Test
        @DisplayName("OPEN → ESCALATED is a valid transition")
        void openToEscalated_succeeds() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.OPEN);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.ESCALATED).build();
            ManualReviewCaseResponseDTO result = service.resolveCase(CASE_ID, req);

            assertThat(result.status()).isEqualTo(ReviewCaseStatus.ESCALATED);
        }

        @Test
        @DisplayName("ESCALATED → APPROVED is a valid transition")
        void escalatedToApproved_succeeds() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.ESCALATED);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));
            when(caseRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.APPROVED).build();
            ManualReviewCaseResponseDTO result = service.resolveCase(CASE_ID, req);

            assertThat(result.status()).isEqualTo(ReviewCaseStatus.APPROVED);
        }

        @Test
        @DisplayName("APPROVED (terminal) → any transition throws 409 CONFLICT")
        void approvedTerminal_throws() {
            ManualReviewCase c = caseWith(ReviewCaseStatus.APPROVED);
            when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(c));

            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.REJECTED).build();
            assertThatThrownBy(() -> service.resolveCase(CASE_ID, req))
                    .isInstanceOf(ResponseStatusException.class);
        }

        @Test
        @DisplayName("not found → EntityNotFoundException")
        void notFound_throws() {
            when(caseRepository.findById(999L)).thenReturn(Optional.empty());
            var req = ManualReviewResolveRequestDTO.builder()
                    .resolution(ReviewCaseStatus.APPROVED).build();
            assertThatThrownBy(() -> service.resolveCase(999L, req))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
