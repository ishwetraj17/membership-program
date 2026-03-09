package com.firstclub.ledger.revenue.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionScheduleServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RevenueRecognitionScheduleServiceImpl Unit Tests")
class RevenueRecognitionScheduleServiceTest {

    @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @InjectMocks private RevenueRecognitionScheduleServiceImpl service;

    private static final Long   INVOICE_ID      = 42L;
    private static final Long   SUBSCRIPTION_ID = 7L;
    private static final Long   MERCHANT_ID     = 1L;
    private static final String CURRENCY        = "INR";

    /** Builds an invoice whose service period spans exactly {@code days} days. */
    private Invoice invoiceWithPeriod(BigDecimal grandTotal, LocalDate start, int days) {
        return Invoice.builder()
                .id(INVOICE_ID)
                .userId(100L)
                .subscriptionId(SUBSCRIPTION_ID)
                .merchantId(MERCHANT_ID)
                .status(InvoiceStatus.PAID)
                .currency(CURRENCY)
                .totalAmount(grandTotal)
                .grandTotal(grandTotal)
                .periodStart(start.atStartOfDay())
                .periodEnd(start.plusDays(days).atStartOfDay())
                .dueDate(start.atStartOfDay())
                .build();
    }

    @BeforeEach
    void stubSaveAll() {
        // Return the same list that was passed in (simulate save-and-return IDs).
        // Lenient: validation-failure tests exit before saveAll is called.
        lenient().when(scheduleRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // Schedule generation — happy path
    // =========================================================================

    @Nested
    @DisplayName("generateScheduleForInvoice — happy path")
    class GenerateHappyPath {

        @Test
        @DisplayName("30-day period produces exactly 30 schedule rows")
        void thirtyDayPeriodProducesThirtyRows() {
            Invoice invoice = invoiceWithPeriod(new BigDecimal("900.00"),
                    LocalDate.of(2024, 1, 1), 30);
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(30);
        }

        @Test
        @DisplayName("Sum of all daily amounts equals the invoice grand total exactly")
        void sumOfRowsEqualsGrandTotal() {
            BigDecimal grandTotal = new BigDecimal("100.00");
            Invoice invoice = invoiceWithPeriod(grandTotal, LocalDate.of(2024, 2, 1), 30);
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            BigDecimal total = result.stream()
                    .map(RevenueRecognitionScheduleResponseDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Accept minor floating point: compare at scale 2
            assertThat(total.setScale(2, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo(grandTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("Last row absorbs rounding residue — 100 / 3 days distributes correctly")
        void lastRowAbsorbsRoundingResidue() {
            BigDecimal grandTotal = new BigDecimal("100.00");
            Invoice invoice = invoiceWithPeriod(grandTotal, LocalDate.of(2024, 3, 1), 3);
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(3);
            BigDecimal daily = result.get(0).getAmount(); // 33.3333
            BigDecimal last  = result.get(2).getAmount();

            // daily * 2 + last == 100.00
            assertThat(daily.multiply(BigDecimal.valueOf(2)).add(last)
                    .setScale(2, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo(grandTotal.setScale(2, java.math.RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("Each row has the correct recognition date")
        void recognitionDatesAscending() {
            LocalDate start = LocalDate.of(2024, 4, 1);
            Invoice invoice = invoiceWithPeriod(new BigDecimal("30.00"), start, 5);
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(5);
            for (int i = 0; i < 5; i++) {
                assertThat(result.get(i).getRecognitionDate()).isEqualTo(start.plusDays(i));
            }
        }

        @Test
        @DisplayName("All rows are created with PENDING status")
        void rowsAreInitiallyPending() {
            Invoice invoice = invoiceWithPeriod(new BigDecimal("60.00"), LocalDate.of(2024, 5, 1), 3);
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).allMatch(r -> r.getStatus() == RevenueRecognitionStatus.PENDING);
        }

        @Test
        @DisplayName("Falls back to totalAmount when grandTotal is zero")
        void fallbackToTotalAmountWhenGrandTotalZero() {
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(SUBSCRIPTION_ID)
                    .merchantId(MERCHANT_ID).status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(new BigDecimal("50.00")).grandTotal(BigDecimal.ZERO)
                    .periodStart(LocalDate.of(2024, 6, 1).atStartOfDay())
                    .periodEnd(LocalDate.of(2024, 6, 6).atStartOfDay())
                    .dueDate(LocalDate.of(2024, 6, 1).atStartOfDay())
                    .build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(5);
            BigDecimal sum = result.stream().map(RevenueRecognitionScheduleResponseDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum.setScale(2, java.math.RoundingMode.HALF_UP))
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Second call returns existing rows without creating new ones")
        void secondCallReturnsExisting() {
            RevenueRecognitionSchedule existing = RevenueRecognitionSchedule.builder()
                    .id(1L).invoiceId(INVOICE_ID).subscriptionId(SUBSCRIPTION_ID)
                    .merchantId(MERCHANT_ID).recognitionDate(LocalDate.of(2024, 1, 1))
                    .amount(new BigDecimal("10.0000")).currency(CURRENCY)
                    .status(RevenueRecognitionStatus.PENDING).build();

            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(true);
            when(scheduleRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of(existing));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(1);
            // saveAll must NOT be called again
            verify(scheduleRepository, never()).saveAll(anyList());
            verify(invoiceRepository, never()).findById(anyLong());
        }
    }

    // =========================================================================
    // Validation failures
    // =========================================================================

    @Nested
    @DisplayName("Validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("Invoice with null subscriptionId returns empty list (not a subscription invoice)")
        void noSubscriptionIdReturnsEmpty() {
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(null)
                    .status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(new BigDecimal("100")).grandTotal(new BigDecimal("100"))
                    .periodStart(LocalDate.of(2024, 1, 1).atStartOfDay())
                    .periodEnd(LocalDate.of(2024, 2, 1).atStartOfDay())
                    .dueDate(LocalDate.of(2024, 1, 1).atStartOfDay()).build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).isEmpty();
            verify(scheduleRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("Invoice with null periodStart throws INVALID_SERVICE_PERIOD")
        void nullPeriodStartThrows() {
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(SUBSCRIPTION_ID)
                    .status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(new BigDecimal("100")).grandTotal(new BigDecimal("100"))
                    .periodStart(null)
                    .periodEnd(LocalDate.of(2024, 2, 1).atStartOfDay())
                    .dueDate(LocalDate.of(2024, 1, 1).atStartOfDay()).build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            assertThatThrownBy(() -> service.generateScheduleForInvoice(INVOICE_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("service period");
        }

        @Test
        @DisplayName("Invoice with null periodEnd throws INVALID_SERVICE_PERIOD")
        void nullPeriodEndThrows() {
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(SUBSCRIPTION_ID)
                    .status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(new BigDecimal("100")).grandTotal(new BigDecimal("100"))
                    .periodStart(LocalDate.of(2024, 1, 1).atStartOfDay()).periodEnd(null)
                    .dueDate(LocalDate.of(2024, 1, 1).atStartOfDay()).build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            assertThatThrownBy(() -> service.generateScheduleForInvoice(INVOICE_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("service period");
        }

        @Test
        @DisplayName("periodEnd == periodStart (0-day period) throws INVALID_SERVICE_PERIOD")
        void zeroDayPeriodThrows() {
            LocalDateTime same = LocalDate.of(2024, 1, 15).atStartOfDay();
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(SUBSCRIPTION_ID)
                    .status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(new BigDecimal("100")).grandTotal(new BigDecimal("100"))
                    .periodStart(same).periodEnd(same)
                    .dueDate(same).build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            assertThatThrownBy(() -> service.generateScheduleForInvoice(INVOICE_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("1 day");
        }

        @Test
        @DisplayName("Invoice with both grandTotal and totalAmount zero returns empty list")
        void zeroAmountReturnsEmpty() {
            Invoice invoice = Invoice.builder()
                    .id(INVOICE_ID).userId(100L).subscriptionId(SUBSCRIPTION_ID)
                    .status(InvoiceStatus.PAID).currency(CURRENCY)
                    .totalAmount(BigDecimal.ZERO).grandTotal(BigDecimal.ZERO)
                    .periodStart(LocalDate.of(2024, 1, 1).atStartOfDay())
                    .periodEnd(LocalDate.of(2024, 2, 1).atStartOfDay())
                    .dueDate(LocalDate.of(2024, 1, 1).atStartOfDay()).build();
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Invoice not found throws MembershipException")
        void invoiceNotFoundThrows() {
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateScheduleForInvoice(INVOICE_ID))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining(String.valueOf(INVOICE_ID));
        }
    }

    // =========================================================================
    // listAllSchedules
    // =========================================================================

    @Nested
    @DisplayName("listAllSchedules")
    class ListAllSchedules {

        @Test
        @DisplayName("Delegates to repository findAll and maps to DTOs")
        void delegatesToRepository() {
            RevenueRecognitionSchedule s = RevenueRecognitionSchedule.builder()
                    .id(99L).invoiceId(1L).subscriptionId(2L).merchantId(3L)
                    .recognitionDate(LocalDate.of(2024, 1, 1))
                    .amount(new BigDecimal("50.0000")).currency("INR")
                    .status(RevenueRecognitionStatus.POSTED).build();
            when(scheduleRepository.findAll()).thenReturn(List.of(s));

            List<RevenueRecognitionScheduleResponseDTO> result = service.listAllSchedules();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(99L);
            assertThat(result.get(0).getStatus()).isEqualTo(RevenueRecognitionStatus.POSTED);
        }
    }
}
