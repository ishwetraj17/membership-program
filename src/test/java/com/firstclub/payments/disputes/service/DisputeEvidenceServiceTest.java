package com.firstclub.payments.disputes.service;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.disputes.dto.DisputeEvidenceCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeEvidenceResponseDTO;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeEvidence;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeEvidenceRepository;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.disputes.service.impl.DisputeEvidenceServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisputeEvidenceService — Unit Tests")
class DisputeEvidenceServiceTest {

    private static final Long MERCHANT_ID = 1L;
    private static final Long DISPUTE_ID  = 10L;
    private static final Long UPLOADER_ID = 7L;

    @Mock private DisputeRepository         disputeRepository;
    @Mock private DisputeEvidenceRepository evidenceRepository;

    @InjectMocks
    private DisputeEvidenceServiceImpl evidenceService;

    private Dispute openDispute(LocalDateTime dueBy) {
        return Dispute.builder()
                .id(DISPUTE_ID)
                .merchantId(MERCHANT_ID)
                .paymentId(42L)
                .customerId(5L)
                .amount(java.math.BigDecimal.TEN)
                .reasonCode("FRAUD")
                .status(DisputeStatus.OPEN)
                .dueBy(dueBy)
                .build();
    }

    private DisputeEvidenceCreateRequestDTO evidenceRequest() {
        return DisputeEvidenceCreateRequestDTO.builder()
                .evidenceType("INVOICE")
                .contentReference("s3://bucket/invoice-123.pdf")
                .uploadedBy(UPLOADER_ID)
                .build();
    }

    private DisputeEvidence savedEvidence() {
        return DisputeEvidence.builder()
                .id(100L)
                .disputeId(DISPUTE_ID)
                .evidenceType("INVOICE")
                .contentReference("s3://bucket/invoice-123.pdf")
                .uploadedBy(UPLOADER_ID)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── addEvidence ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addEvidence")
    class AddEvidenceTests {

        @Test
        @DisplayName("no due date — always accepted")
        void addEvidence_noDueDate_success() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.of(openDispute(null)));
            when(evidenceRepository.save(any(DisputeEvidence.class))).thenReturn(savedEvidence());

            DisputeEvidenceResponseDTO result = evidenceService.addEvidence(MERCHANT_ID, DISPUTE_ID, evidenceRequest());

            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getEvidenceType()).isEqualTo("INVOICE");
            assertThat(result.getUploadedBy()).isEqualTo(UPLOADER_ID);
            verify(evidenceRepository).save(any(DisputeEvidence.class));
        }

        @Test
        @DisplayName("due date in future — accepted")
        void addEvidence_beforeDueDate_success() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.of(openDispute(LocalDateTime.now().plusDays(7))));
            when(evidenceRepository.save(any(DisputeEvidence.class))).thenReturn(savedEvidence());

            DisputeEvidenceResponseDTO result = evidenceService.addEvidence(MERCHANT_ID, DISPUTE_ID, evidenceRequest());
            assertThat(result.getDisputeId()).isEqualTo(DISPUTE_ID);
        }

        @Test
        @DisplayName("due date in past → 422 EVIDENCE_DEADLINE_PASSED")
        void addEvidence_afterDueDate_rejected() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.of(openDispute(LocalDateTime.now().minusDays(1))));

            assertThatThrownBy(() -> evidenceService.addEvidence(MERCHANT_ID, DISPUTE_ID, evidenceRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("EVIDENCE_DEADLINE_PASSED");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    });
            verify(evidenceRepository, never()).save(any());
        }

        @Test
        @DisplayName("dispute not found → 404 DISPUTE_NOT_FOUND")
        void addEvidence_disputeNotFound() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> evidenceService.addEvidence(MERCHANT_ID, DISPUTE_ID, evidenceRequest()))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> {
                        MembershipException me = (MembershipException) ex;
                        assertThat(me.getErrorCode()).isEqualTo("DISPUTE_NOT_FOUND");
                        assertThat(me.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }
    }

    // ── listEvidence ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listEvidence")
    class ListEvidenceTests {

        @Test
        @DisplayName("returns evidence ordered by creation time")
        void listEvidence_returnsOrderedList() {
            when(disputeRepository.findByMerchantIdAndId(MERCHANT_ID, DISPUTE_ID))
                    .thenReturn(Optional.of(openDispute(null)));
            when(evidenceRepository.findByDisputeIdOrderByCreatedAtAsc(DISPUTE_ID))
                    .thenReturn(List.of(savedEvidence(), savedEvidence()));

            List<DisputeEvidenceResponseDTO> result = evidenceService.listEvidence(MERCHANT_ID, DISPUTE_ID);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("wrong merchant → 404 DISPUTE_NOT_FOUND")
        void listEvidence_wrongMerchant_notFound() {
            when(disputeRepository.findByMerchantIdAndId(99L, DISPUTE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> evidenceService.listEvidence(99L, DISPUTE_ID))
                    .isInstanceOf(MembershipException.class)
                    .satisfies(ex -> assertThat(((MembershipException) ex).getErrorCode())
                            .isEqualTo("DISPUTE_NOT_FOUND"));
        }
    }
}
