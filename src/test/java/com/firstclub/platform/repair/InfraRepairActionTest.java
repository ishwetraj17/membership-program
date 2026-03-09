package com.firstclub.platform.repair;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.platform.repair.actions.LedgerSnapshotRebuildAction;
import com.firstclub.platform.repair.actions.ProjectionRebuildAction;
import com.firstclub.platform.repair.actions.RevenueScheduleRegenerateAction;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.service.LedgerSnapshotService;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for infrastructure/state repair actions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Infrastructure Repair Actions — Unit Tests")
class InfraRepairActionTest {

    // ── ProjectionRebuildAction ───────────────────────────────────────────────

    @Nested
    @DisplayName("ProjectionRebuildAction")
    class ProjectionRebuildTests {

        @Mock private ProjectionRebuildService projectionRebuildService;
        @Mock private ObjectMapper             objectMapper;
        @InjectMocks private ProjectionRebuildAction action;

        @Test
        @DisplayName("metadata is correct")
        void metadata() {
            assertThat(action.getRepairKey()).isEqualTo("repair.projection.rebuild");
            assertThat(action.getTargetType()).isEqualTo("PROJECTION");
            assertThat(action.supportsDryRun()).isTrue();
        }

        @Test
        @DisplayName("EXECUTE — rebuilds customer_billing_summary")
        void execute_rebuildsCustomerBillingProjection() throws Exception {
            RebuildResponseDTO dto = RebuildResponseDTO.builder()
                    .projectionName("customer_billing_summary")
                    .eventsProcessed(50).recordsInProjection(10)
                    .rebuiltAt(LocalDateTime.now()).build();
            when(projectionRebuildService.rebuildCustomerBillingSummaryProjection()).thenReturn(dto);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "customer_billing_summary", Map.of(), false, 1L, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isDryRun()).isFalse();
            assertThat(result.getDetails()).contains("50 events");
            verify(projectionRebuildService).rebuildCustomerBillingSummaryProjection();
        }

        @Test
        @DisplayName("EXECUTE — rebuilds merchant_daily_kpi")
        void execute_rebuildsMerchantDailyKpi() throws Exception {
            RebuildResponseDTO dto = RebuildResponseDTO.builder()
                    .projectionName("merchant_daily_kpi")
                    .eventsProcessed(30).recordsInProjection(7)
                    .rebuiltAt(LocalDateTime.now()).build();
            when(projectionRebuildService.rebuildMerchantDailyKpiProjection()).thenReturn(dto);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "merchant_daily_kpi", Map.of(), false, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            verify(projectionRebuildService).rebuildMerchantDailyKpiProjection();
        }

        @Test
        @DisplayName("DRY-RUN — does not invoke rebuild service")
        void execute_dryRun_noRebuild() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "customer_billing_summary", Map.of(), true, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isDryRun()).isTrue();
            verifyNoInteractions(projectionRebuildService);
        }

        @Test
        @DisplayName("FAIL — throws on unsupported projection name")
        void execute_throwsOnUnsupportedProjection() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "bad_projection", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported projection");
        }
    }

    // ── LedgerSnapshotRebuildAction ───────────────────────────────────────────

    @Nested
    @DisplayName("LedgerSnapshotRebuildAction")
    class LedgerSnapshotRebuildTests {

        @Mock private LedgerSnapshotService ledgerSnapshotService;
        @Mock private ObjectMapper          objectMapper;
        @InjectMocks private LedgerSnapshotRebuildAction action;

        @Test
        @DisplayName("EXECUTE — calls service with correct date")
        void execute_callsServiceWithDate() throws Exception {
            when(ledgerSnapshotService.generateSnapshotForDate(any())).thenReturn(List.of());
            when(objectMapper.writeValueAsString(any())).thenReturn("[]");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    null, Map.of("date", "2024-03-15"), false, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            verify(ledgerSnapshotService).generateSnapshotForDate(LocalDate.of(2024, 3, 15));
        }

        @Test
        @DisplayName("FAIL — throws when date param missing")
        void execute_throwsWhenDateMissing() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    null, Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("date");
        }

        @Test
        @DisplayName("FAIL — throws when date format invalid")
        void execute_throwsOnBadDate() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    null, Map.of("date", "not-a-date"), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── RevenueScheduleRegenerateAction ──────────────────────────────────────

    @Nested
    @DisplayName("RevenueScheduleRegenerateAction")
    class RevenueScheduleRegenerateTests {

        @Mock private InvoiceRepository                   invoiceRepository;
        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private RevenueRecognitionScheduleService    scheduleService;
        @Mock private ObjectMapper                         objectMapper;
        @InjectMocks private RevenueScheduleRegenerateAction action;

        @Test
        @DisplayName("EXECUTE — generates schedule for PAID invoice")
        void execute_generatesScheduleForPaidInvoice() throws Exception {
            Invoice inv = paidInvoice(5L);
            when(invoiceRepository.findById(5L)).thenReturn(Optional.of(inv));
            when(scheduleRepository.existsByInvoiceId(5L)).thenReturn(false);
            when(scheduleService.generateScheduleForInvoice(5L)).thenReturn(List.of());
            when(objectMapper.writeValueAsString(any())).thenReturn("[]");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "5", Map.of(), false, 1L, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            verify(scheduleService).generateScheduleForInvoice(5L);
        }

        @Test
        @DisplayName("DRY-RUN — does not call schedule service")
        void execute_dryRun_noScheduleCreated() {
            Invoice inv = paidInvoice(6L);
            when(invoiceRepository.findById(6L)).thenReturn(Optional.of(inv));
            when(scheduleRepository.existsByInvoiceId(6L)).thenReturn(false);

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "6", Map.of(), true, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isDryRun()).isTrue();
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("FAIL — throws for non-PAID invoice")
        void execute_throwsForNonPaidInvoice() {
            Invoice inv = Invoice.builder().id(7L)
                    .status(InvoiceStatus.OPEN)
                    .subscriptionId(1L)
                    .periodStart(LocalDateTime.now().minusDays(30))
                    .periodEnd(LocalDateTime.now())
                    .grandTotal(BigDecimal.TEN).build();
            when(invoiceRepository.findById(7L)).thenReturn(Optional.of(inv));

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "7", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAID");
        }

        @Test
        @DisplayName("FAIL — throws for invoice with no subscriptionId")
        void execute_throwsForNoSubscription() {
            Invoice inv = Invoice.builder().id(8L)
                    .status(InvoiceStatus.PAID)
                    .periodStart(LocalDateTime.now().minusDays(30))
                    .periodEnd(LocalDateTime.now())
                    .grandTotal(BigDecimal.TEN).build();
            when(invoiceRepository.findById(8L)).thenReturn(Optional.of(inv));

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "8", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("subscriptionId");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static Invoice paidInvoice(Long id) {
        return Invoice.builder()
                .id(id)
                .status(InvoiceStatus.PAID)
                .subscriptionId(99L)
                .merchantId(1L)
                .periodStart(LocalDateTime.now().minusDays(30))
                .periodEnd(LocalDateTime.now())
                .grandTotal(new BigDecimal("300.00"))
                .build();
    }
}
