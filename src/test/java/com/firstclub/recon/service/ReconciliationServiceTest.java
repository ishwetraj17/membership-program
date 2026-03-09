package com.firstclub.recon.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentIntent;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentIntentRepository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.dto.ReconReportDTO;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationService Unit Tests")
class ReconciliationServiceTest {

    @Mock private InvoiceRepository       invoiceRepository;
    @Mock private PaymentRepository       paymentRepository;
    @Mock private PaymentIntentRepository paymentIntentRepository;
    @Mock private ReconReportRepository   reconReportRepository;
    @Mock private ReconMismatchRepository reconMismatchRepository;

    @InjectMocks
    private ReconciliationService service;

    private static final LocalDate   TODAY    = LocalDate.of(2025, 6, 1);
    private static final LocalDateTime DAY_START = TODAY.atStartOfDay();
    private static final LocalDateTime DAY_END   = TODAY.atTime(LocalTime.MAX);

    @BeforeEach
    void stubReportSave() {
        when(reconReportRepository.findByReportDateForUpdate(TODAY)).thenReturn(Optional.empty());
        when(reconReportRepository.save(any())).thenAnswer(inv -> {
            ReconReport r = inv.getArgument(0);
            if (r.getId() == null) {
                // simulate auto-generated ID
                java.lang.reflect.Field f;
                try { f = ReconReport.class.getDeclaredField("id"); f.setAccessible(true); f.set(r, 1L); } catch (Exception ignored) {}
            }
            return r;
        });
        when(reconMismatchRepository.findByReportId(anyLong())).thenReturn(List.of());
        when(reconMismatchRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Scenario A: Invoice with no payment → INVOICE_NO_PAYMENT mismatch
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("invoice without payment")
    class InvoiceNoPayment {

        @Test
        @DisplayName("produces INVOICE_NO_PAYMENT mismatch")
        void invoiceNoPayment_mismatch() {
            Invoice invoice = Invoice.builder()
                    .id(10L).userId(1L).status(InvoiceStatus.OPEN)
                    .currency("INR").totalAmount(new BigDecimal("499"))
                    .dueDate(DAY_END).build();

            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of(invoice));
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of());
            when(paymentIntentRepository.findByInvoiceId(10L)).thenReturn(List.of());

            ReconReportDTO report = service.runForDate(TODAY);

            assertThat(report.getMismatchCount()).isEqualTo(1);
            assertThat(report.getMismatches()).hasSize(1);
            assertThat(report.getMismatches().get(0).getType()).isEqualTo(MismatchType.INVOICE_NO_PAYMENT);
            assertThat(report.getMismatches().get(0).getInvoiceId()).isEqualTo(10L);
            assertThat(report.getExpectedTotal()).isEqualByComparingTo("499");
            assertThat(report.getActualTotal()).isEqualByComparingTo("0");
        }
    }

    // -------------------------------------------------------------------------
    // Scenario B: Payment with no invoice → PAYMENT_NO_INVOICE mismatch
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("payment without invoice")
    class PaymentNoInvoice {

        @Test
        @DisplayName("produces PAYMENT_NO_INVOICE mismatch")
        void paymentNoInvoice_mismatch() {
            Payment payment = Payment.builder()
                    .id(20L).paymentIntentId(5L).amount(new BigDecimal("299"))
                    .currency("INR").status(PaymentStatus.CAPTURED)
                    .gatewayTxnId("TXN-001").capturedAt(DAY_START.plusHours(2))
                    .build();

            PaymentIntent pi = PaymentIntent.builder()
                    .id(5L).invoiceId(null)   // no invoice linked
                    .amount(new BigDecimal("299")).currency("INR").build();

            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of());
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of(payment));
            when(paymentIntentRepository.findById(5L)).thenReturn(Optional.of(pi));

            ReconReportDTO report = service.runForDate(TODAY);

            assertThat(report.getMismatchCount()).isEqualTo(1);
            assertThat(report.getMismatches().get(0).getType()).isEqualTo(MismatchType.PAYMENT_NO_INVOICE);
            assertThat(report.getMismatches().get(0).getPaymentId()).isEqualTo(20L);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario C: Amount mismatch
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("amount mismatch")
    class AmountMismatch {

        @Test
        @DisplayName("invoice total differs from captured payment → AMOUNT_MISMATCH")
        void amountMismatch_detected() {
            Invoice invoice = Invoice.builder()
                    .id(30L).userId(1L).status(InvoiceStatus.OPEN)
                    .currency("INR").totalAmount(new BigDecimal("799"))
                    .dueDate(DAY_END).build();

            PaymentIntent pi = PaymentIntent.builder()
                    .id(6L).invoiceId(30L).amount(new BigDecimal("500"))
                    .currency("INR").build();

            Payment payment = Payment.builder()
                    .id(31L).paymentIntentId(6L).amount(new BigDecimal("500"))
                    .currency("INR").status(PaymentStatus.CAPTURED)
                    .gatewayTxnId("TXN-002").capturedAt(DAY_START.plusHours(1))
                    .build();

            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of(invoice));
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of(payment));
            when(paymentIntentRepository.findByInvoiceId(30L)).thenReturn(List.of(pi));
            when(paymentIntentRepository.findById(6L)).thenReturn(Optional.of(pi));

            ReconReportDTO report = service.runForDate(TODAY);

            boolean hasAmountMismatch = report.getMismatches().stream()
                    .anyMatch(m -> m.getType() == MismatchType.AMOUNT_MISMATCH);
            assertThat(hasAmountMismatch).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Scenario D: Duplicate gateway txn ID
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("duplicate gateway txn")
    class DuplicateGatewayTxn {

        @Test
        @DisplayName("two payments with same gatewayTxnId → DUPLICATE_GATEWAY_TXN")
        void duplicateTxnId_detected() {
            PaymentIntent pi1 = PaymentIntent.builder().id(7L).invoiceId(null).build();
            PaymentIntent pi2 = PaymentIntent.builder().id(8L).invoiceId(null).build();

            Payment p1 = Payment.builder().id(40L).paymentIntentId(7L)
                    .amount(new BigDecimal("299")).currency("INR")
                    .status(PaymentStatus.CAPTURED).gatewayTxnId("SAME-TXN")
                    .capturedAt(DAY_START.plusHours(1)).build();

            Payment p2 = Payment.builder().id(41L).paymentIntentId(8L)
                    .amount(new BigDecimal("299")).currency("INR")
                    .status(PaymentStatus.CAPTURED).gatewayTxnId("SAME-TXN")
                    .capturedAt(DAY_START.plusHours(2)).build();

            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of());
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of(p1, p2));
            when(paymentIntentRepository.findById(7L)).thenReturn(Optional.of(pi1));
            when(paymentIntentRepository.findById(8L)).thenReturn(Optional.of(pi2));

            ReconReportDTO report = service.runForDate(TODAY);

            long dupCount = report.getMismatches().stream()
                    .filter(m -> m.getType() == MismatchType.DUPLICATE_GATEWAY_TXN)
                    .count();
            assertThat(dupCount).isEqualTo(2);  // one row per duplicate payment
        }
    }

    // -------------------------------------------------------------------------
    // Scenario E: Clean run — no mismatches
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("clean run")
    class CleanRun {

        @Test
        @DisplayName("matched invoice and payment → no mismatches, totals equal")
        void cleanRun_noMismatches() {
            Invoice invoice = Invoice.builder()
                    .id(50L).userId(1L).status(InvoiceStatus.PAID)
                    .currency("INR").totalAmount(new BigDecimal("499"))
                    .dueDate(DAY_END).build();

            PaymentIntent pi = PaymentIntent.builder().id(9L).invoiceId(50L)
                    .amount(new BigDecimal("499")).currency("INR").build();

            Payment payment = Payment.builder()
                    .id(51L).paymentIntentId(9L).amount(new BigDecimal("499"))
                    .currency("INR").status(PaymentStatus.CAPTURED)
                    .gatewayTxnId("TXN-OK").capturedAt(DAY_START.plusHours(3))
                    .build();

            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of(invoice));
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of(payment));
            when(paymentIntentRepository.findByInvoiceId(50L)).thenReturn(List.of(pi));
            when(paymentIntentRepository.findById(9L)).thenReturn(Optional.of(pi));

            ReconReportDTO report = service.runForDate(TODAY);

            assertThat(report.getMismatchCount()).isZero();
            assertThat(report.getExpectedTotal()).isEqualByComparingTo("499");
            assertThat(report.getActualTotal()).isEqualByComparingTo("499");
            assertThat(report.getVariance()).isEqualByComparingTo("0");
        }
    }

    // -------------------------------------------------------------------------
    // Idempotency: re-run replaces existing report
    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("re-running for same date replaces existing report")
        void rerun_replacesExistingReport() {
            ReconReport existing = ReconReport.builder()
                    .id(100L).reportDate(TODAY)
                    .expectedTotal(BigDecimal.ZERO).actualTotal(BigDecimal.ZERO)
                    .mismatchCount(5).build();

            when(reconReportRepository.findByReportDateForUpdate(TODAY)).thenReturn(Optional.of(existing));
            when(invoiceRepository.findByCreatedAtBetween(DAY_START, DAY_END)).thenReturn(List.of());
            when(paymentRepository.findByStatusAndCapturedAtBetween(PaymentStatus.CAPTURED, DAY_START, DAY_END))
                    .thenReturn(List.of());

            // No save() override needed — @BeforeEach lambda returns 'r' as-is when id != null

            ReconReportDTO report = service.runForDate(TODAY);

            verify(reconMismatchRepository).deleteAll(anyList());
            assertThat(report.getMismatchCount()).isZero();
        }
    }
}

