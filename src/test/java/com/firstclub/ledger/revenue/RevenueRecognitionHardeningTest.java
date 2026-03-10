package com.firstclub.ledger.revenue;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.revenue.RevenueScheduleAllocator;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.dto.RevenueWaterfallProjectionDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.entity.RevenueWaterfallProjection;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.repository.RevenueWaterfallProjectionRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionPostingServiceImpl;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionScheduleServiceImpl;
import com.firstclub.ledger.revenue.service.impl.RevenueWaterfallProjectionServiceImpl;
import com.firstclub.ledger.service.LedgerService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 14 — Revenue Recognition Hardening unit tests.
 *
 * <p>Covers:
 * <ol>
 *   <li>Duplicate schedule generation blocked by {@code existsByInvoiceId} check</li>
 *   <li>Fingerprint secondary idempotency guard</li>
 *   <li>Generation fingerprint stored on every row</li>
 *   <li>Duplicate posting blocked by POSTED status guard</li>
 *   <li>Posting run ID stamped on posted rows</li>
 *   <li>Revenue recognition ceiling check prevents over-recognition</li>
 *   <li>Force-regeneration (catch-up run) deletes PENDING rows and sets catchUpRun=true</li>
 *   <li>Waterfall projection updated correctly from POSTED schedules</li>
 *   <li>Repair action force=true triggers regenerateScheduleForInvoice</li>
 *   <li>Repair action normal mode calls generateScheduleForInvoice (idempotent)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 14 — Revenue Recognition Hardening Tests")
class RevenueRecognitionHardeningTest {

    // ── shared fixtures ───────────────────────────────────────────────────────
    private static final Long   INVOICE_ID      = 100L;
    private static final Long   SUBSCRIPTION_ID = 7L;
    private static final Long   MERCHANT_ID     = 1L;
    private static final String CURRENCY        = "INR";
    private static final LocalDate START        = LocalDate.of(2024, 1, 1);
    private static final int   DAYS             = 3;  // 3-day period for easy maths

    // =========================================================================
    // 1–3: Schedule generation hardening
    // =========================================================================

    @Nested
    @DisplayName("Schedule generation hardening")
    class ScheduleGenerationHardening {

        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private InvoiceRepository invoiceRepository;
        @Spy  private RevenueScheduleAllocator allocator = new RevenueScheduleAllocator();
        @InjectMocks private RevenueRecognitionScheduleServiceImpl service;

        @BeforeEach
        void stubSaveAll() {
            lenient().when(scheduleRepository.saveAll(anyList()))
                    .thenAnswer(inv -> inv.getArgument(0));
        }

        private Invoice buildInvoice(BigDecimal grandTotal) {
            return Invoice.builder()
                    .id(INVOICE_ID)
                    .userId(99L)
                    .subscriptionId(SUBSCRIPTION_ID)
                    .merchantId(MERCHANT_ID)
                    .status(InvoiceStatus.PAID)
                    .currency(CURRENCY)
                    .totalAmount(grandTotal)
                    .grandTotal(grandTotal)
                    .periodStart(START.atStartOfDay())
                    .periodEnd(START.plusDays(DAYS).atStartOfDay())
                    .dueDate(START.atStartOfDay())
                    .build();
        }

        @Test
        @DisplayName("Duplicate generation blocked — existsByInvoiceId returns existing rows without saveAll")
        void duplicateGenerationBlockedByInvoiceIdCheck() {
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(true);
            when(scheduleRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of(
                    buildPostedSchedule(1L), buildPostedSchedule(2L)));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(2);
            verify(scheduleRepository, never()).saveAll(any());
            verify(invoiceRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Fingerprint secondary idempotency — existsByGenerationFingerprint blocks second concurrent generation")
        void fingerprintSecondaryIdempotencyGuard() {
            Invoice invoice = buildInvoice(new BigDecimal("90.00"));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            // Simulate fingerprint already present (concurrent request committed first)
            when(scheduleRepository.existsByGenerationFingerprint(anyString())).thenReturn(true);
            when(scheduleRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of(
                    buildPostedSchedule(10L)));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.generateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(1);
            verify(scheduleRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Generation fingerprint is stored on every generated row")
        void fingerprintStoredOnGeneratedRows() {
            Invoice invoice = buildInvoice(new BigDecimal("90.00"));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(scheduleRepository.existsByGenerationFingerprint(anyString())).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            service.generateScheduleForInvoice(INVOICE_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RevenueRecognitionSchedule>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(scheduleRepository).saveAll(captor.capture());
            List<RevenueRecognitionSchedule> saved = captor.getValue();

            assertThat(saved).isNotEmpty();
            // Every row should carry the same non-null fingerprint
            String fp = saved.get(0).getGenerationFingerprint();
            assertThat(fp).isNotBlank();
            assertThat(saved).allMatch(r -> fp.equals(r.getGenerationFingerprint()));
        }

        @Test
        @DisplayName("Fingerprint is deterministic — same invoice produces same fingerprint")
        void fingerprintIsDeterministic() {
            Invoice invoice = buildInvoice(new BigDecimal("300.00"));
            String fp1 = service.computeFingerprint(invoice);
            String fp2 = service.computeFingerprint(invoice);
            assertThat(fp1).isEqualTo(fp2).hasSize(64); // 64 hex chars for SHA-256
        }

        @Test
        @DisplayName("CatchUpRun=false on rows from normal generation")
        void normalGenerationSetsCatchUpRunFalse() {
            Invoice invoice = buildInvoice(new BigDecimal("90.00"));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(scheduleRepository.existsByGenerationFingerprint(anyString())).thenReturn(false);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            service.generateScheduleForInvoice(INVOICE_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RevenueRecognitionSchedule>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(scheduleRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).allMatch(r -> !r.isCatchUpRun());
        }

        // ── force regeneration / catch-up run ────────────────────────────────

        @Test
        @DisplayName("Force regeneration deletes PENDING rows and generates new catch-up rows")
        void forceRegenerationDeletesPendingAndSetsCatchUpFlag() {
            Invoice invoice = buildInvoice(new BigDecimal("90.00"));
            RevenueRecognitionSchedule pending1 = buildPendingSchedule(20L);
            RevenueRecognitionSchedule pending2 = buildPendingSchedule(21L);
            when(scheduleRepository.findByInvoiceIdAndStatus(INVOICE_ID, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of(pending1, pending2));
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            service.regenerateScheduleForInvoice(INVOICE_ID);

            // Must delete the PENDING rows
            verify(scheduleRepository).deleteAll(List.of(pending1, pending2));

            // Must save new rows with catchUpRun=true
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RevenueRecognitionSchedule>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(scheduleRepository).saveAll(captor.capture());
            assertThat(captor.getValue()).allMatch(RevenueRecognitionSchedule::isCatchUpRun);
        }

        @Test
        @DisplayName("Force regeneration produces correct row count (3-day period → 3 rows)")
        void forceRegenerationProducesCorrectRowCount() {
            Invoice invoice = buildInvoice(new BigDecimal("90.00"));
            when(scheduleRepository.findByInvoiceIdAndStatus(INVOICE_ID, RevenueRecognitionStatus.PENDING))
                    .thenReturn(List.of());
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));

            List<RevenueRecognitionScheduleResponseDTO> result =
                    service.regenerateScheduleForInvoice(INVOICE_ID);

            assertThat(result).hasSize(DAYS);
            assertThat(result).allMatch(RevenueRecognitionScheduleResponseDTO::isCatchUpRun);
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private RevenueRecognitionSchedule buildPostedSchedule(Long id) {
            return RevenueRecognitionSchedule.builder()
                    .id(id).invoiceId(INVOICE_ID).merchantId(MERCHANT_ID)
                    .subscriptionId(SUBSCRIPTION_ID)
                    .recognitionDate(START).amount(new BigDecimal("30.00"))
                    .currency(CURRENCY).status(RevenueRecognitionStatus.POSTED)
                    .ledgerEntryId(999L).generationFingerprint("abc123").build();
        }

        private RevenueRecognitionSchedule buildPendingSchedule(Long id) {
            return RevenueRecognitionSchedule.builder()
                    .id(id).invoiceId(INVOICE_ID).merchantId(MERCHANT_ID)
                    .subscriptionId(SUBSCRIPTION_ID)
                    .recognitionDate(START).amount(new BigDecimal("30.00"))
                    .currency(CURRENCY).status(RevenueRecognitionStatus.PENDING).build();
        }
    }

    // =========================================================================
    // 4–6: Posting service hardening
    // =========================================================================

    @Nested
    @DisplayName("Posting service hardening")
    class PostingServiceHardening {

        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private LedgerService ledgerService;
        @Mock private RevenueRecognitionPostingService selfMock;
        @InjectMocks private RevenueRecognitionPostingServiceImpl service;

        @BeforeEach
        void injectSelf() {
            ReflectionTestUtils.setField(service, "self", selfMock);
        }

        @Test
        @DisplayName("Duplicate posting blocked — already-POSTED schedule is a no-op")
        void duplicatePostingBlocked() {
            RevenueRecognitionSchedule already = pendingSchedule();
            already.setStatus(RevenueRecognitionStatus.POSTED);
            already.setLedgerEntryId(888L);
            when(scheduleRepository.findByIdWithLock(1L)).thenReturn(Optional.of(already));

            service.postSingleRecognitionInRun(1L, 999L);

            verify(ledgerService, never()).postEntry(any(), any(), any(), any(), any());
            verify(scheduleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Posting run ID is stamped on the schedule row after posting")
        void postingRunIdStampedOnRow() {
            RevenueRecognitionSchedule schedule = pendingSchedule();
            when(scheduleRepository.findByIdWithLock(1L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    eq(INVOICE_ID), eq(RevenueRecognitionStatus.POSTED))).thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("90.00"));
            LedgerEntry entry = LedgerEntry.builder().id(55L)
                    .entryType(LedgerEntryType.REVENUE_RECOGNIZED)
                    .referenceType(LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE)
                    .referenceId(1L).currency(CURRENCY).build();
            when(ledgerService.postEntry(any(), any(), any(), any(), any())).thenReturn(entry);

            long runId = 123456L;
            service.postSingleRecognitionInRun(1L, runId);

            ArgumentCaptor<RevenueRecognitionSchedule> captor =
                    ArgumentCaptor.forClass(RevenueRecognitionSchedule.class);
            verify(scheduleRepository).save(captor.capture());
            assertThat(captor.getValue().getPostingRunId()).isEqualTo(runId);
            assertThat(captor.getValue().getStatus()).isEqualTo(RevenueRecognitionStatus.POSTED);
            assertThat(captor.getValue().getLedgerEntryId()).isEqualTo(55L);
        }

        @Test
        @DisplayName("Ceiling check blocks posting when already posted amount + this row > scheduled total")
        void ceilingCheckPreventsOverRecognition() {
            RevenueRecognitionSchedule schedule = pendingSchedule();
            // schedule.amount = 30.00, already posted = 85.00, total scheduled = 90.00
            // 85 + 30 = 115 > 90 → ceiling breached
            when(scheduleRepository.findByIdWithLock(1L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    eq(INVOICE_ID), eq(RevenueRecognitionStatus.POSTED)))
                    .thenReturn(new BigDecimal("85.00"));
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("90.00"));

            assertThatThrownBy(() -> service.postSingleRecognitionInRun(1L, 1L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("ceiling");

            verify(ledgerService, never()).postEntry(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Ceiling check passes when already posted amount + this row == scheduled total")
        void ceilingCheckPassesAtExactTotal() {
            RevenueRecognitionSchedule schedule = pendingSchedule(); // amount = 30.00
            // 60 + 30 = 90 == 90 → exactly at ceiling, should pass
            when(scheduleRepository.findByIdWithLock(1L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    eq(INVOICE_ID), eq(RevenueRecognitionStatus.POSTED)))
                    .thenReturn(new BigDecimal("60.00"));
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("90.00"));
            LedgerEntry entry = LedgerEntry.builder().id(77L)
                    .entryType(LedgerEntryType.REVENUE_RECOGNIZED)
                    .referenceType(LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE)
                    .referenceId(1L).currency(CURRENCY).build();
            when(ledgerService.postEntry(any(), any(), any(), any(), any())).thenReturn(entry);

            assertThatCode(() -> service.postSingleRecognitionInRun(1L, 1L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("postDueRecognitionsForDate generates a run ID and calls postSingleRecognitionInRun")
        void batchRunCallsPostSingleRecognitionInRun() {
            RevenueRecognitionSchedule s1 = pendingSchedule();
            s1.setId(10L);
            RevenueRecognitionSchedule s2 = pendingSchedule();
            s2.setId(11L);
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(
                    any(), eq(RevenueRecognitionStatus.PENDING))).thenReturn(List.of(s1, s2));

            service.postDueRecognitionsForDate(LocalDate.of(2024, 1, 3));

            // Self-mock should have received postSingleRecognitionInRun(id, nonNullRunId)
            ArgumentCaptor<Long> scheduleIdCaptor = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> runIdCaptor = ArgumentCaptor.forClass(Long.class);
            verify(selfMock, times(2)).postSingleRecognitionInRun(
                    scheduleIdCaptor.capture(), runIdCaptor.capture());

            assertThat(scheduleIdCaptor.getAllValues()).containsExactlyInAnyOrder(10L, 11L);
            // Both rows posted in same run → same run ID
            assertThat(runIdCaptor.getAllValues().get(0))
                    .isEqualTo(runIdCaptor.getAllValues().get(1));
            assertThat(runIdCaptor.getAllValues().get(0)).isPositive();
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private RevenueRecognitionSchedule pendingSchedule() {
            return RevenueRecognitionSchedule.builder()
                    .id(1L).invoiceId(INVOICE_ID).merchantId(MERCHANT_ID)
                    .subscriptionId(SUBSCRIPTION_ID)
                    .recognitionDate(START).amount(new BigDecimal("30.00"))
                    .currency(CURRENCY).status(RevenueRecognitionStatus.PENDING).build();
        }
    }

    // =========================================================================
    // 7: Waterfall projection
    // =========================================================================

    @Nested
    @DisplayName("Waterfall projection service")
    class WaterfallProjectionTests {

        @Mock private RevenueWaterfallProjectionRepository waterfallRepository;
        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @InjectMocks private RevenueWaterfallProjectionServiceImpl service;

        @Test
        @DisplayName("updateProjectionForDate uses POSTED schedule amounts as recognized_amount")
        void updateProjectionUsesPostedAmounts() {
            when(scheduleRepository.sumPostedAmountByMerchantAndDate(MERCHANT_ID, START))
                    .thenReturn(new BigDecimal("30.00"));
            when(waterfallRepository.findByMerchantIdAndBusinessDate(MERCHANT_ID, START))
                    .thenReturn(Optional.empty());
            when(waterfallRepository.save(any(RevenueWaterfallProjection.class)))
                    .thenAnswer(inv -> {
                        RevenueWaterfallProjection p = inv.getArgument(0);
                        p.setId(1L);
                        return p;
                    });

            RevenueWaterfallProjectionDTO result =
                    service.updateProjectionForDate(MERCHANT_ID, START);

            assertThat(result.getRecognizedAmount())
                    .isEqualByComparingTo(new BigDecimal("30.00"));
        }

        @Test
        @DisplayName("updateProjectionForDate returns zero recognized when no POSTED rows exist")
        void updateProjectionHandlesNullSum() {
            when(scheduleRepository.sumPostedAmountByMerchantAndDate(MERCHANT_ID, START))
                    .thenReturn(null); // repository returns null when no rows match
            when(waterfallRepository.findByMerchantIdAndBusinessDate(MERCHANT_ID, START))
                    .thenReturn(Optional.empty());
            when(waterfallRepository.save(any(RevenueWaterfallProjection.class)))
                    .thenAnswer(inv -> {
                        RevenueWaterfallProjection p = inv.getArgument(0);
                        p.setId(2L);
                        return p;
                    });

            RevenueWaterfallProjectionDTO result =
                    service.updateProjectionForDate(MERCHANT_ID, START);

            assertThat(result.getRecognizedAmount())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("updateProjectionForDate computes deferred_closing correctly")
        void deferredClosingComputedCorrectly() {
            // deferredClosing = deferredOpening + billed - recognized - refunded - disputed
            // Opening=100, billed=0, recognized=30, refunded=0, disputed=0 → closing=70
            RevenueWaterfallProjection existing = RevenueWaterfallProjection.builder()
                    .id(5L).merchantId(MERCHANT_ID).businessDate(START)
                    .deferredOpening(new BigDecimal("100.00"))
                    .billedAmount(BigDecimal.ZERO)
                    .recognizedAmount(BigDecimal.ZERO)
                    .refundedAmount(BigDecimal.ZERO)
                    .disputedAmount(BigDecimal.ZERO)
                    .deferredClosing(new BigDecimal("100.00"))
                    .build();
            when(scheduleRepository.sumPostedAmountByMerchantAndDate(MERCHANT_ID, START))
                    .thenReturn(new BigDecimal("30.00"));
            when(waterfallRepository.findByMerchantIdAndBusinessDate(MERCHANT_ID, START))
                    .thenReturn(Optional.of(existing));
            when(waterfallRepository.save(any(RevenueWaterfallProjection.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RevenueWaterfallProjectionDTO result =
                    service.updateProjectionForDate(MERCHANT_ID, START);

            assertThat(result.getDeferredClosing()).isEqualByComparingTo(new BigDecimal("70.00"));
        }

        @Test
        @DisplayName("getWaterfall delegates to repository by merchant and date range")
        void getWaterfallDelegates() {
            LocalDate to = START.plusDays(5);
            when(waterfallRepository.findByMerchantIdAndBusinessDateBetweenOrderByBusinessDateAsc(
                    MERCHANT_ID, START, to)).thenReturn(List.of());

            List<RevenueWaterfallProjectionDTO> result = service.getWaterfall(MERCHANT_ID, START, to);

            assertThat(result).isEmpty();
            verify(waterfallRepository)
                    .findByMerchantIdAndBusinessDateBetweenOrderByBusinessDateAsc(MERCHANT_ID, START, to);
        }
    }

    // =========================================================================
    // 8: Repair action — force vs normal mode
    // =========================================================================

    @Nested
    @DisplayName("RevenueScheduleRegenerateAction — force vs normal mode")
    class RepairActionForceMode {

        @Mock private RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock private RevenueRecognitionScheduleService    scheduleService;
        @Mock private InvoiceRepository                    invoiceRepository;
        @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

        private com.firstclub.platform.repair.actions.RevenueScheduleRegenerateAction action;

        @BeforeEach
        void setUp() {
            action = new com.firstclub.platform.repair.actions.RevenueScheduleRegenerateAction(
                    invoiceRepository, scheduleRepository, scheduleService, objectMapper);
        }

        @Test
        @DisplayName("Normal mode calls generateScheduleForInvoice (idempotent)")
        void normalModeCallsGenerate() throws Exception {
            Invoice invoice = paidInvoice();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(scheduleService.generateScheduleForInvoice(INVOICE_ID)).thenReturn(List.of());
            when(objectMapper.writeValueAsString(any())).thenReturn("[]");

            com.firstclub.platform.repair.RepairAction.RepairContext ctx =
                    new com.firstclub.platform.repair.RepairAction.RepairContext(
                            String.valueOf(INVOICE_ID), java.util.Map.of(), false, null, null);

            action.execute(ctx);

            verify(scheduleService).generateScheduleForInvoice(INVOICE_ID);
            verify(scheduleService, never()).regenerateScheduleForInvoice(any());
        }

        @Test
        @DisplayName("Force mode calls regenerateScheduleForInvoice and result details mention catch-up")
        void forceModeCallsRegenerate() throws Exception {
            Invoice invoice = paidInvoice();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(true);
            when(scheduleRepository.findByInvoiceIdAndStatus(
                    INVOICE_ID, RevenueRecognitionStatus.PENDING)).thenReturn(List.of());
            when(scheduleService.regenerateScheduleForInvoice(INVOICE_ID)).thenReturn(List.of());
            when(objectMapper.writeValueAsString(any())).thenReturn("[]");

            com.firstclub.platform.repair.RepairAction.RepairContext ctx =
                    new com.firstclub.platform.repair.RepairAction.RepairContext(
                            String.valueOf(INVOICE_ID),
                            java.util.Map.of("force", "true"),
                            false, null, null);

            com.firstclub.platform.repair.RepairActionResult result = action.execute(ctx);

            verify(scheduleService).regenerateScheduleForInvoice(INVOICE_ID);
            verify(scheduleService, never()).generateScheduleForInvoice(any());
            assertThat(result.getDetails()).contains("catch-up");
        }

        @Test
        @DisplayName("Dry-run with force=true reports deletion count without persisting")
        void dryRunForceModeReportsWithoutPersisting() {
            Invoice invoice = paidInvoice();
            RevenueRecognitionSchedule pending = RevenueRecognitionSchedule.builder()
                    .id(50L).invoiceId(INVOICE_ID).status(RevenueRecognitionStatus.PENDING)
                    .merchantId(MERCHANT_ID).subscriptionId(SUBSCRIPTION_ID)
                    .recognitionDate(START).amount(BigDecimal.TEN).currency(CURRENCY).build();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(true);
            when(scheduleRepository.findByInvoiceIdAndStatus(
                    INVOICE_ID, RevenueRecognitionStatus.PENDING)).thenReturn(List.of(pending));

            com.firstclub.platform.repair.RepairAction.RepairContext ctx =
                    new com.firstclub.platform.repair.RepairAction.RepairContext(
                            String.valueOf(INVOICE_ID),
                            java.util.Map.of("force", "true"),
                            true, null, null);

            com.firstclub.platform.repair.RepairActionResult result = action.execute(ctx);

            assertThat(result.isDryRun()).isTrue();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getDetails()).contains("DRY-RUN").contains("1");
            verify(scheduleService, never()).generateScheduleForInvoice(any());
            verify(scheduleService, never()).regenerateScheduleForInvoice(any());
        }

        @Test
        @DisplayName("Action rejects non-PAID invoices with IllegalStateException")
        void rejectsNonPaidInvoice() {
            Invoice openInvoice = Invoice.builder()
                    .id(INVOICE_ID).userId(1L).subscriptionId(SUBSCRIPTION_ID)
                    .merchantId(MERCHANT_ID).status(InvoiceStatus.OPEN)
                    .currency(CURRENCY).totalAmount(BigDecimal.TEN).grandTotal(BigDecimal.TEN)
                    .periodStart(START.atStartOfDay()).periodEnd(START.plusDays(3).atStartOfDay())
                    .dueDate(START.atStartOfDay()).build();
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(openInvoice));

            com.firstclub.platform.repair.RepairAction.RepairContext ctx =
                    new com.firstclub.platform.repair.RepairAction.RepairContext(
                            String.valueOf(INVOICE_ID), java.util.Map.of(), false, null, null);

            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PAID");
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private Invoice paidInvoice() {
            return Invoice.builder()
                    .id(INVOICE_ID).userId(99L).subscriptionId(SUBSCRIPTION_ID)
                    .merchantId(MERCHANT_ID).status(InvoiceStatus.PAID)
                    .currency(CURRENCY).totalAmount(new BigDecimal("90.00"))
                    .grandTotal(new BigDecimal("90.00"))
                    .periodStart(START.atStartOfDay()).periodEnd(START.plusDays(DAYS).atStartOfDay())
                    .dueDate(START.atStartOfDay()).build();
        }
    }
}
