package com.firstclub.recon.service;

import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.dto.ReconMismatchDTO;
import com.firstclub.recon.entity.*;
import com.firstclub.recon.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdvancedReconciliationService Unit Tests")
class AdvancedReconciliationServiceTest {

    @Mock private PaymentRepository              paymentRepository;
    @Mock private SettlementRepository           settlementRepository;
    @Mock private SettlementBatchRepository      batchRepository;
    @Mock private ExternalStatementImportRepository importRepository;
    @Mock private ReconMismatchRepository        mismatchRepository;
    @Mock private ReconReportRepository          reportRepository;

    @InjectMocks private AdvancedReconciliationService service;

    private static final LocalDate DATE      = LocalDate.of(2025, 6, 1);
    private static final Long      REPORT_ID = 1L;

    private void stubReportExists() {
        when(reportRepository.existsById(REPORT_ID)).thenReturn(true);
    }

    @SuppressWarnings("unused")
    private ReconMismatch savedMismatch(MismatchType type, String details) {
        return ReconMismatch.builder().id(99L).reportId(REPORT_ID)
                .type(type).details(details).status(ReconMismatchStatus.OPEN).build();
    }

    // ---------------------------------------------------------------
    // Layer 2: payment vs ledger
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("reconcilePaymentToLedger")
    class PaymentToLedger {

        @Test
        @DisplayName("matching totals → no mismatches")
        void matching_noMismatches() {
            stubReportExists();
            Payment p = Payment.builder().id(1L).amount(new BigDecimal("1000")).build();
            Settlement s = Settlement.builder().id(1L).totalAmount(new BigDecimal("1000")).build();

            when(paymentRepository.findByStatusAndCapturedAtBetween(eq(PaymentStatus.CAPTURED), any(), any()))
                    .thenReturn(List.of(p));
            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.of(s));

            List<ReconMismatchDTO> result = service.reconcilePaymentToLedger(DATE, REPORT_ID);

            assertThat(result).isEmpty();
            verify(mismatchRepository, never()).save(any());
        }

        @Test
        @DisplayName("variance > tolerance → PAYMENT_LEDGER_VARIANCE mismatch")
        void variance_createsMismatch() {
            stubReportExists();
            Payment p = Payment.builder().id(1L).amount(new BigDecimal("1000")).build();
            Settlement s = Settlement.builder().id(1L).totalAmount(new BigDecimal("800")).build();

            when(paymentRepository.findByStatusAndCapturedAtBetween(eq(PaymentStatus.CAPTURED), any(), any()))
                    .thenReturn(List.of(p));
            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.of(s));
            when(mismatchRepository.save(any())).thenAnswer(inv -> {
                ReconMismatch m = inv.getArgument(0);
                m.setId(99L);
                return m;
            });

            List<ReconMismatchDTO> result = service.reconcilePaymentToLedger(DATE, REPORT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(MismatchType.PAYMENT_LEDGER_VARIANCE);
            assertThat(result.get(0).getStatus()).isEqualTo(ReconMismatchStatus.OPEN);
        }

        @Test
        @DisplayName("no settlement record → treats ledger as 0, creates mismatch if payments > 0")
        void noSettlement_zeroLedger() {
            stubReportExists();
            Payment p = Payment.builder().id(1L).amount(new BigDecimal("500")).build();
            when(paymentRepository.findByStatusAndCapturedAtBetween(eq(PaymentStatus.CAPTURED), any(), any()))
                    .thenReturn(List.of(p));
            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.empty());
            when(mismatchRepository.save(any())).thenAnswer(inv -> {
                ReconMismatch m = inv.getArgument(0);
                m.setId(100L);
                return m;
            });

            List<ReconMismatchDTO> result = service.reconcilePaymentToLedger(DATE, REPORT_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("report not found → EntityNotFoundException")
        void reportNotFound_throws() {
            when(reportRepository.existsById(REPORT_ID)).thenReturn(false);
            assertThatThrownBy(() -> service.reconcilePaymentToLedger(DATE, REPORT_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // ---------------------------------------------------------------
    // Layer 3: ledger vs batch
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("reconcileLedgerToSettlementBatch")
    class LedgerToBatch {

        @Test
        @DisplayName("batch gross matches settlement total → no mismatches")
        void matching_noMismatches() {
            stubReportExists();
            SettlementBatch batch = SettlementBatch.builder().id(5L).merchantId(1L)
                    .batchDate(DATE).grossAmount(new BigDecimal("1000")).build();
            Settlement s = Settlement.builder().id(1L).totalAmount(new BigDecimal("1000")).build();

            when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.of(s));

            List<ReconMismatchDTO> result = service.reconcileLedgerToSettlementBatch(5L, REPORT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("batch gross differs → LEDGER_BATCH_VARIANCE mismatch")
        void differ_createsMismatch() {
            stubReportExists();
            SettlementBatch batch = SettlementBatch.builder().id(5L).merchantId(1L)
                    .batchDate(DATE).grossAmount(new BigDecimal("1200")).build();
            Settlement s = Settlement.builder().id(1L).totalAmount(new BigDecimal("1000")).build();

            when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
            when(settlementRepository.findBySettlementDate(DATE)).thenReturn(Optional.of(s));
            when(mismatchRepository.save(any())).thenAnswer(inv -> {
                ReconMismatch m = inv.getArgument(0);
                m.setId(101L);
                return m;
            });

            List<ReconMismatchDTO> result = service.reconcileLedgerToSettlementBatch(5L, REPORT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(MismatchType.LEDGER_BATCH_VARIANCE);
        }
    }

    // ---------------------------------------------------------------
    // Layer 4: batch vs statement
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("reconcileSettlementBatchToStatement")
    class BatchToStatement {

        @Test
        @DisplayName("matching amounts → no mismatches")
        void matching_noMismatches() {
            stubReportExists();
            SettlementBatch batch = SettlementBatch.builder().id(5L).merchantId(1L)
                    .batchDate(DATE).grossAmount(new BigDecimal("3000")).build();
            ExternalStatementImport imp = ExternalStatementImport.builder().id(7L)
                    .merchantId(1L).statementDate(DATE)
                    .totalAmount(new BigDecimal("3000")).rowCount(3)
                    .status(StatementImportStatus.IMPORTED).build();

            when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
            when(importRepository.findById(7L)).thenReturn(Optional.of(imp));

            List<ReconMismatchDTO> result = service.reconcileSettlementBatchToStatement(5L, 7L, REPORT_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("batch/statement differ → BATCH_STATEMENT_VARIANCE mismatch")
        void differ_createsMismatch() {
            stubReportExists();
            SettlementBatch batch = SettlementBatch.builder().id(5L).merchantId(1L)
                    .batchDate(DATE).grossAmount(new BigDecimal("3000")).build();
            ExternalStatementImport imp = ExternalStatementImport.builder().id(7L)
                    .merchantId(1L).statementDate(DATE)
                    .totalAmount(new BigDecimal("2900")).rowCount(3)
                    .status(StatementImportStatus.IMPORTED).build();

            when(batchRepository.findById(5L)).thenReturn(Optional.of(batch));
            when(importRepository.findById(7L)).thenReturn(Optional.of(imp));
            when(mismatchRepository.save(any())).thenAnswer(inv -> {
                ReconMismatch m = inv.getArgument(0);
                m.setId(102L);
                return m;
            });

            List<ReconMismatchDTO> result = service.reconcileSettlementBatchToStatement(5L, 7L, REPORT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getType()).isEqualTo(MismatchType.BATCH_STATEMENT_VARIANCE);
        }
    }

    // ---------------------------------------------------------------
    // Mismatch lifecycle
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("mismatch lifecycle")
    class Lifecycle {

        private ReconMismatch openMismatch() {
            return ReconMismatch.builder().id(50L).reportId(REPORT_ID)
                    .type(MismatchType.AMOUNT_MISMATCH).status(ReconMismatchStatus.OPEN).build();
        }

        @Test
        @DisplayName("acknowledge → ACKNOWLEDGED status with ownerUserId set")
        void acknowledge() {
            ReconMismatch m = openMismatch();
            when(mismatchRepository.findById(50L)).thenReturn(Optional.of(m));
            when(mismatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReconMismatchDTO result = service.acknowledgeMismatch(50L, 99L);

            assertThat(result.getStatus()).isEqualTo(ReconMismatchStatus.ACKNOWLEDGED);
            assertThat(result.getOwnerUserId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("resolve → RESOLVED status with resolutionNote")
        void resolve() {
            ReconMismatch m = openMismatch();
            when(mismatchRepository.findById(50L)).thenReturn(Optional.of(m));
            when(mismatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReconMismatchDTO result = service.resolveMismatch(50L, "Duplicate charge reversed", 99L);

            assertThat(result.getStatus()).isEqualTo(ReconMismatchStatus.RESOLVED);
            assertThat(result.getResolutionNote()).isEqualTo("Duplicate charge reversed");
        }

        @Test
        @DisplayName("ignore → IGNORED status with reason as resolutionNote")
        void ignore() {
            ReconMismatch m = openMismatch();
            when(mismatchRepository.findById(50L)).thenReturn(Optional.of(m));
            when(mismatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            ReconMismatchDTO result = service.ignoreMismatch(50L, "Known test data");

            assertThat(result.getStatus()).isEqualTo(ReconMismatchStatus.IGNORED);
            assertThat(result.getResolutionNote()).isEqualTo("Known test data");
        }

        @Test
        @DisplayName("listMismatches with status filter")
        void listMismatches_withFilter() {
            ReconMismatch m = openMismatch();
            PageRequest pageable = PageRequest.of(0, 10);
            when(mismatchRepository.findByStatus(ReconMismatchStatus.OPEN, pageable))
                    .thenReturn(new PageImpl<>(List.of(m)));

            var page = service.listMismatches(ReconMismatchStatus.OPEN, pageable);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent().get(0).getStatus()).isEqualTo(ReconMismatchStatus.OPEN);
        }

        @Test
        @DisplayName("listMismatches without status filter → returns all ordered by createdAt desc")
        void listMismatches_noFilter() {
            ReconMismatch m = openMismatch();
            PageRequest pageable = PageRequest.of(0, 10);
            when(mismatchRepository.findAllByOrderByCreatedAtDesc(pageable))
                    .thenReturn(new PageImpl<>(List.of(m)));

            var page = service.listMismatches(null, pageable);

            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }
}
