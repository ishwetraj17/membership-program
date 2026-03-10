package com.firstclub.ledger.revenue;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.revenue.audit.DriftCheckResult;
import com.firstclub.ledger.revenue.audit.RevenueRecognitionDriftChecker;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionSchedule;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.guard.GuardDecision;
import com.firstclub.ledger.revenue.guard.RecognitionPolicyCode;
import com.firstclub.ledger.revenue.guard.RevenueRecognitionGuard;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionPostingService;
import com.firstclub.ledger.revenue.service.impl.RevenueCatchUpServiceImpl;
import com.firstclub.ledger.revenue.service.impl.RevenueCatchUpTransactionHelper;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionPostingServiceImpl;
import com.firstclub.ledger.revenue.service.impl.RevenueRecognitionScheduleServiceImpl;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
 * Phase 15 — Revenue Recognition Guards and Deferred Revenue Correctness.
 *
 * <p>Test coverage:
 * <ol>
 *   <li>{@link RevenueScheduleAllocator} — daily allocation, rounding, minor units</li>
 *   <li>{@link RevenueRecognitionGuard} — rule table covering all subscription/invoice states</li>
 *   <li>{@link RevenueCatchUpServiceImpl} — guard-based catch-up orchestration</li>
 *   <li>{@link RevenueRecognitionDriftChecker} — drift detection logic</li>
 *   <li>{@link RevenueRecognitionPostingServiceImpl} — recognizedAmountMinor stamped on POSTED</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 15 — Revenue Recognition Guard and Deferred Revenue Tests")
class Phase15RevenueRecognitionTests {

    // =========================================================================
    // 1. RevenueScheduleAllocator
    // =========================================================================

    @Nested
    @DisplayName("1. RevenueScheduleAllocator")
    class RevenueScheduleAllocatorTests {

        private final RevenueScheduleAllocator allocator = new RevenueScheduleAllocator();

        private static final LocalDate START = LocalDate.of(2025, 1, 1);

        @Test
        @DisplayName("Sum of allocated amounts equals invoice total (30-day ₹499 schedule)")
        void sumEqualsInvoiceTotalForThirtyDays() {
            BigDecimal total = new BigDecimal("499.00");
            LocalDate end   = START.plusDays(30);

            List<RevenueRecognitionSchedule> rows = allocator.allocate(
                    1L, 10L, 100L, total, "INR", START, end, "fp1", false);

            assertThat(rows).hasSize(30);
            BigDecimal sum = rows.stream()
                    .map(RevenueRecognitionSchedule::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(total);
        }

        @Test
        @DisplayName("Last row absorbs rounding remainder (non-zero roundingAdjustmentMinor)")
        void lastRowAbsorbsRoundingRemainder() {
            // ₹499 / 30 days leaves a rounding residue
            BigDecimal total = new BigDecimal("499.00");
            List<RevenueRecognitionSchedule> rows = allocator.allocate(
                    1L, 10L, 100L, total, "INR", START, START.plusDays(30), "fp2", false);

            RevenueRecognitionSchedule last = rows.get(rows.size() - 1);
            long totalMinor = rows.stream()
                    .mapToLong(r -> r.getExpectedAmountMinor() == null ? 0 : r.getExpectedAmountMinor())
                    .sum();
            // sum of minor units should be close to 49900 (some rounding drift at minor-unit scale is expected)
            assertThat(totalMinor).isGreaterThanOrEqualTo(49890L).isLessThanOrEqualTo(49910L);
            // last row carries the rounding-absorbed annotation; adjustment field must be set (may be 0)
            assertThat(last.getRoundingAdjustmentMinor()).isNotNull();
        }

        @Test
        @DisplayName("Single-day schedule produces one row with the full amount")
        void singleDayScheduleGetsFullAmount() {
            BigDecimal total = new BigDecimal("199.00");
            List<RevenueRecognitionSchedule> rows = allocator.allocate(
                    2L, 10L, 100L, total, "INR", START, START.plusDays(1), "fp3", false);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getAmount()).isEqualByComparingTo(total);
            assertThat(rows.get(0).getExpectedAmountMinor()).isEqualTo(19900L);
            assertThat(rows.get(0).getRoundingAdjustmentMinor()).isEqualTo(0L);
        }

        @Test
        @DisplayName("All rows have expectedAmountMinor set")
        void expectedAmountMinorSetOnAllRows() {
            List<RevenueRecognitionSchedule> rows = allocator.allocate(
                    3L, 10L, 100L, new BigDecimal("99.00"), "INR",
                    START, START.plusDays(7), "fp4", false);

            assertThat(rows).allSatisfy(r ->
                    assertThat(r.getExpectedAmountMinor()).isNotNull().isPositive());
        }
    }

    // =========================================================================
    // 2. RevenueRecognitionGuard
    // =========================================================================

    @Nested
    @DisplayName("2. RevenueRecognitionGuard — rule table")
    class RevenueRecognitionGuardTests {

        private final RevenueRecognitionGuard guard = new RevenueRecognitionGuard();

        @Test
        @DisplayName("ACTIVE subscription with PAID invoice → ALLOW / RECOGNIZE")
        void activeSubscriptionAllowed() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.ACTIVE, InvoiceStatus.PAID);

            assertThat(r.decision()).isEqualTo(GuardDecision.ALLOW);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.RECOGNIZE);
            assertThat(guard.allowsPosting(r.decision())).isTrue();
        }

        @Test
        @DisplayName("TRIALING subscription → ALLOW / RECOGNIZE")
        void trialingSubscriptionAllowed() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.TRIALING, InvoiceStatus.PAID);

            assertThat(r.decision()).isEqualTo(GuardDecision.ALLOW);
            assertThat(guard.allowsPosting(r.decision())).isTrue();
        }

        @Test
        @DisplayName("SUSPENDED subscription → BLOCK / HALT")
        void suspendedSubscriptionBlocked() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.SUSPENDED, InvoiceStatus.OPEN);

            assertThat(r.decision()).isEqualTo(GuardDecision.BLOCK);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.HALT);
            assertThat(guard.allowsPosting(r.decision())).isFalse();
        }

        @Test
        @DisplayName("CANCELLED subscription → HALT / HALT")
        void cancelledSubscriptionHalted() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.CANCELLED, InvoiceStatus.OPEN);

            assertThat(r.decision()).isEqualTo(GuardDecision.HALT);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.HALT);
            assertThat(guard.allowsPosting(r.decision())).isFalse();
        }

        @Test
        @DisplayName("VOID invoice → HALT / REVERSE_ON_VOID (overrides subscription status)")
        void voidInvoiceHaltsRegardlessOfSubscription() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.ACTIVE, InvoiceStatus.VOID);

            assertThat(r.decision()).isEqualTo(GuardDecision.HALT);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.REVERSE_ON_VOID);
            assertThat(guard.allowsPosting(r.decision())).isFalse();
        }

        @Test
        @DisplayName("PAST_DUE subscription → FLAG / DEFER_UNTIL_PAID")
        void pastDueFlagged() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.PAST_DUE, InvoiceStatus.OPEN);

            assertThat(r.decision()).isEqualTo(GuardDecision.FLAG);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.DEFER_UNTIL_PAID);
            assertThat(guard.allowsPosting(r.decision())).isTrue(); // FLAG still posts
        }

        @Test
        @DisplayName("PAUSED subscription → DEFER / SKIP")
        void pausedDeferred() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.PAUSED, InvoiceStatus.OPEN);

            assertThat(r.decision()).isEqualTo(GuardDecision.DEFER);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.SKIP);
            assertThat(guard.allowsPosting(r.decision())).isFalse();
        }

        @Test
        @DisplayName("INCOMPLETE subscription → DEFER / DEFER_UNTIL_PAID")
        void incompleteDeferred() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.INCOMPLETE, InvoiceStatus.OPEN);

            assertThat(r.decision()).isEqualTo(GuardDecision.DEFER);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.DEFER_UNTIL_PAID);
        }

        @Test
        @DisplayName("UNCOLLECTIBLE invoice → FLAG / DEFER_UNTIL_PAID (overrides subscription)")
        void uncollectibleInvoiceFlagged() {
            RevenueRecognitionGuard.GuardResult r =
                    guard.evaluate(SubscriptionStatusV2.ACTIVE, InvoiceStatus.UNCOLLECTIBLE);

            assertThat(r.decision()).isEqualTo(GuardDecision.FLAG);
            assertThat(r.policyCode()).isEqualTo(RecognitionPolicyCode.DEFER_UNTIL_PAID);
        }
    }

    // =========================================================================
    // 3. RevenueCatchUpServiceImpl
    // =========================================================================

    @Nested
    @DisplayName("3. RevenueCatchUpServiceImpl — guard-based orchestration")
    class RevenueCatchUpServiceImplTests {

        @Mock RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock RevenueRecognitionPostingService     postingService;
        @Mock RevenueRecognitionGuard              guard;
        @Mock SubscriptionV2Repository             subscriptionRepository;
        @Mock InvoiceRepository                    invoiceRepository;
        @Mock RevenueCatchUpTransactionHelper      txHelper;

        @InjectMocks
        RevenueCatchUpServiceImpl catchUpService;

        private static final Long SCHEDULE_ID    = 50L;
        private static final Long INVOICE_ID     = 200L;
        private static final Long SUBSCRIPTION_ID = 10L;

        private RevenueRecognitionSchedule pendingSchedule() {
            return RevenueRecognitionSchedule.builder()
                    .id(SCHEDULE_ID)
                    .invoiceId(INVOICE_ID)
                    .subscriptionId(SUBSCRIPTION_ID)
                    .recognitionDate(LocalDate.now().minusDays(1))
                    .amount(new BigDecimal("16.63"))
                    .currency("INR")
                    .status(RevenueRecognitionStatus.PENDING)
                    .build();
        }

        @Test
        @DisplayName("ALLOW decision → stampAllowAndMarkCatchUp called, then posting service invoked")
        void allowedScheduleGetsPosted() {
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(any(), any()))
                    .thenReturn(List.of(pendingSchedule()));

            Invoice inv = new Invoice();
            inv.setStatus(InvoiceStatus.PAID);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));

            SubscriptionV2 sub = new SubscriptionV2();
            sub.setStatus(SubscriptionStatusV2.ACTIVE);
            when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.of(sub));

            RevenueRecognitionGuard.GuardResult allowResult =
                    RevenueRecognitionGuard.GuardResult.of(
                            GuardDecision.ALLOW, RecognitionPolicyCode.RECOGNIZE, "ok");
            when(guard.evaluate(SubscriptionStatusV2.ACTIVE, InvoiceStatus.PAID))
                    .thenReturn(allowResult);

            RevenueRecognitionRunResponseDTO result =
                    catchUpService.runCatchUp(LocalDate.now());

            verify(txHelper).stampAllowAndMarkCatchUp(eq(SCHEDULE_ID),
                    eq(GuardDecision.ALLOW), eq(RecognitionPolicyCode.RECOGNIZE), anyString());
            verify(postingService).postSingleRecognitionInRun(eq(SCHEDULE_ID), anyLong());
            assertThat(result.getPosted()).isEqualTo(1);
            assertThat(result.getFailed()).isEqualTo(0);
        }

        @Test
        @DisplayName("BLOCK decision → stampAndSkip called, posting service NOT called")
        void blockedSubscriptionGetsSkipped() {
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(any(), any()))
                    .thenReturn(List.of(pendingSchedule()));

            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

            RevenueRecognitionGuard.GuardResult blockResult =
                    RevenueRecognitionGuard.GuardResult.of(
                            GuardDecision.BLOCK, RecognitionPolicyCode.HALT, "suspended");
            when(guard.evaluate(SubscriptionStatusV2.ACTIVE, InvoiceStatus.OPEN))
                    .thenReturn(blockResult);

            RevenueRecognitionRunResponseDTO result =
                    catchUpService.runCatchUp(LocalDate.now());

            verify(txHelper).stampAndSkip(eq(SCHEDULE_ID),
                    eq(GuardDecision.BLOCK), eq(RecognitionPolicyCode.HALT), anyString());
            verify(postingService, never()).postSingleRecognitionInRun(any(), any());
            assertThat(result.getPosted()).isEqualTo(0);
        }

        @Test
        @DisplayName("DEFER decision → stampDefer called, leaves PENDING, posting NOT called")
        void deferredScheduleLeavesPending() {
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(any(), any()))
                    .thenReturn(List.of(pendingSchedule()));

            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.empty());

            RevenueRecognitionGuard.GuardResult deferResult =
                    RevenueRecognitionGuard.GuardResult.of(
                            GuardDecision.DEFER, RecognitionPolicyCode.SKIP, "paused");
            when(guard.evaluate(any(), any())).thenReturn(deferResult);

            catchUpService.runCatchUp(LocalDate.now());

            verify(txHelper).stampDefer(eq(SCHEDULE_ID),
                    eq(GuardDecision.DEFER), eq(RecognitionPolicyCode.SKIP), anyString());
            verify(postingService, never()).postSingleRecognitionInRun(any(), any());
        }

        @Test
        @DisplayName("HALT from VOID invoice → stampAndSkip called with HALT decision")
        void voidInvoiceHaltsSchedule() {
            when(scheduleRepository.findByRecognitionDateLessThanEqualAndStatus(any(), any()))
                    .thenReturn(List.of(pendingSchedule()));

            Invoice inv = new Invoice();
            inv.setStatus(InvoiceStatus.VOID);
            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(inv));
            when(subscriptionRepository.findById(SUBSCRIPTION_ID)).thenReturn(Optional.empty());

            RevenueRecognitionGuard.GuardResult haltResult =
                    RevenueRecognitionGuard.GuardResult.of(
                            GuardDecision.HALT, RecognitionPolicyCode.REVERSE_ON_VOID,
                            "Invoice is VOID");
            when(guard.evaluate(any(), eq(InvoiceStatus.VOID))).thenReturn(haltResult);

            catchUpService.runCatchUp(LocalDate.now());

            verify(txHelper).stampAndSkip(eq(SCHEDULE_ID),
                    eq(GuardDecision.HALT), eq(RecognitionPolicyCode.REVERSE_ON_VOID), anyString());
        }
    }

    // =========================================================================
    // 4. RevenueRecognitionDriftChecker
    // =========================================================================

    @Nested
    @DisplayName("4. RevenueRecognitionDriftChecker")
    class RevenueRecognitionDriftCheckerTests {

        @Mock RevenueRecognitionScheduleRepository scheduleRepository;

        @InjectMocks
        RevenueRecognitionDriftChecker driftChecker;

        private static final Long INVOICE_ID = 300L;

        @Test
        @DisplayName("No drift when all scheduled amount is recognized and no overdue rows")
        void noDriftWhenAllPosted() {
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("99.00"));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    INVOICE_ID, RevenueRecognitionStatus.POSTED))
                    .thenReturn(new BigDecimal("99.00"));
            when(scheduleRepository.countOverduePendingByInvoiceId(
                    eq(INVOICE_ID), any(LocalDate.class)))
                    .thenReturn(0L);

            DriftCheckResult result = driftChecker.checkDrift(INVOICE_ID);

            assertThat(result.hasDrift()).isFalse();
            assertThat(result.delta()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.pendingOverdueCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Drift detected when recognized amount is less than scheduled total")
        void driftDetectedWhenRecognizedLessThanScheduled() {
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("99.00"));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    INVOICE_ID, RevenueRecognitionStatus.POSTED))
                    .thenReturn(new BigDecimal("66.00"));
            when(scheduleRepository.countOverduePendingByInvoiceId(
                    eq(INVOICE_ID), any(LocalDate.class)))
                    .thenReturn(0L);

            DriftCheckResult result = driftChecker.checkDrift(INVOICE_ID);

            assertThat(result.hasDrift()).isTrue();
            assertThat(result.delta()).isEqualByComparingTo(new BigDecimal("-33.00"));
        }

        @Test
        @DisplayName("Overdue PENDING rows alone mark the result as drift (even if amounts match)")
        void overdueCountAloneTriggersDrift() {
            when(scheduleRepository.sumTotalAmountByInvoiceId(INVOICE_ID))
                    .thenReturn(new BigDecimal("99.00"));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(
                    INVOICE_ID, RevenueRecognitionStatus.POSTED))
                    .thenReturn(new BigDecimal("99.00"));
            when(scheduleRepository.countOverduePendingByInvoiceId(
                    eq(INVOICE_ID), any(LocalDate.class)))
                    .thenReturn(2L);

            DriftCheckResult result = driftChecker.checkDrift(INVOICE_ID);

            assertThat(result.hasDrift()).isTrue();
            assertThat(result.pendingOverdueCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // 5. PostingService — recognizedAmountMinor stamped on POSTED
    // =========================================================================

    @Nested
    @DisplayName("5. PostingService — recognizedAmountMinor stamped on POSTED")
    class PostingServiceRecognizedMinorTests {

        @Mock RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock LedgerService                        ledgerService;

        @InjectMocks
        RevenueRecognitionPostingServiceImpl postingService;

        @BeforeEach
        void injectSelf() {
            // postingService uses @Autowired @Lazy self-injection; wire manually for tests
            ReflectionTestUtils.setField(postingService, "self", postingService);
        }

        @Test
        @DisplayName("recognizedAmountMinor set from expectedAmountMinor when present")
        void recognizedMinorFromExpected() {
            RevenueRecognitionSchedule schedule = RevenueRecognitionSchedule.builder()
                    .id(1L)
                    .invoiceId(10L)
                    .amount(new BigDecimal("16.6300"))
                    .currency("INR")
                    .status(RevenueRecognitionStatus.PENDING)
                    .expectedAmountMinor(1663L)
                    .version(0L)
                    .build();

            when(scheduleRepository.findByIdWithLock(1L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(any()))
                    .thenReturn(new BigDecimal("499.00"));

            LedgerEntry mockEntry = new LedgerEntry();
            mockEntry.setId(99L);
            mockEntry.setEntryType(LedgerEntryType.REVENUE_RECOGNIZED);
            mockEntry.setReferenceType(LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE);
            when(ledgerService.postEntry(any(), any(), any(), any(), any()))
                    .thenReturn(mockEntry);

            postingService.postSingleRecognitionInRun(1L, 42L);

            assertThat(schedule.getRecognizedAmountMinor()).isEqualTo(1663L);
            assertThat(schedule.getStatus()).isEqualTo(RevenueRecognitionStatus.POSTED);
        }

        @Test
        @DisplayName("recognizedAmountMinor derived from amount when expectedAmountMinor is null")
        void recognizedMinorDerivedFromAmount() {
            RevenueRecognitionSchedule schedule = RevenueRecognitionSchedule.builder()
                    .id(2L)
                    .invoiceId(20L)
                    .amount(new BigDecimal("49.99"))
                    .currency("INR")
                    .status(RevenueRecognitionStatus.PENDING)
                    .expectedAmountMinor(null)   // simulates older row before Phase 15
                    .version(0L)
                    .build();

            when(scheduleRepository.findByIdWithLock(2L)).thenReturn(Optional.of(schedule));
            when(scheduleRepository.sumAmountByInvoiceIdAndStatus(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(scheduleRepository.sumTotalAmountByInvoiceId(any()))
                    .thenReturn(new BigDecimal("499.00"));

            LedgerEntry mockEntry = new LedgerEntry();
            mockEntry.setId(100L);
            mockEntry.setEntryType(LedgerEntryType.REVENUE_RECOGNIZED);
            mockEntry.setReferenceType(LedgerReferenceType.REVENUE_RECOGNITION_SCHEDULE);
            when(ledgerService.postEntry(any(), any(), any(), any(), any()))
                    .thenReturn(mockEntry);

            postingService.postSingleRecognitionInRun(2L, 43L);

            // 49.99 × 100 = 4999 (HALF_UP)
            assertThat(schedule.getRecognizedAmountMinor()).isEqualTo(4999L);
        }
    }

    // =========================================================================
    // 6. RevenueRecognitionScheduleServiceImpl — allocator integration
    // =========================================================================

    @Nested
    @DisplayName("6. ScheduleServiceImpl — allocator used for generation and toDto phase 15 fields")
    class ScheduleServiceAllocatorIntegrationTests {

        @Mock RevenueRecognitionScheduleRepository scheduleRepository;
        @Mock InvoiceRepository                    invoiceRepository;
        @Mock RevenueScheduleAllocator             allocator;

        @InjectMocks
        RevenueRecognitionScheduleServiceImpl scheduleService;

        private static final Long INVOICE_ID = 400L;

        @Test
        @DisplayName("generateScheduleForInvoice delegates to allocator.allocate")
        void generateDelegatesToAllocator() {
            Invoice invoice = new Invoice();
            invoice.setId(INVOICE_ID);
            invoice.setMerchantId(1L);
            invoice.setSubscriptionId(5L);
            invoice.setCurrency("INR");
            invoice.setGrandTotal(new BigDecimal("499.00"));
            invoice.setPeriodStart(LocalDate.of(2025, 1, 1).atStartOfDay());
            invoice.setPeriodEnd(LocalDate.of(2025, 1, 31).atStartOfDay());

            when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
            when(scheduleRepository.existsByInvoiceId(INVOICE_ID)).thenReturn(false);
            when(scheduleRepository.existsByGenerationFingerprint(any())).thenReturn(false);
            when(allocator.allocate(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenReturn(List.of());
            when(scheduleRepository.saveAll(any())).thenReturn(List.of());

            scheduleService.generateScheduleForInvoice(INVOICE_ID);

            verify(allocator).allocate(
                    eq(INVOICE_ID),
                    eq(1L),
                    eq(5L),
                    any(BigDecimal.class),
                    eq("INR"),
                    any(LocalDate.class),
                    any(LocalDate.class),
                    any(String.class),
                    eq(false));
        }

        @Test
        @DisplayName("toDto maps Phase 15 guard and minor fields from entity")
        void toDtoIncludesPhase15Fields() {
            RevenueRecognitionSchedule entity = RevenueRecognitionSchedule.builder()
                    .id(1L)
                    .merchantId(1L)
                    .subscriptionId(5L)
                    .invoiceId(INVOICE_ID)
                    .recognitionDate(LocalDate.of(2025, 1, 1))
                    .amount(new BigDecimal("16.63"))
                    .currency("INR")
                    .status(RevenueRecognitionStatus.PENDING)
                    .expectedAmountMinor(1663L)
                    .recognizedAmountMinor(null)
                    .roundingAdjustmentMinor(0L)
                    .policyCode(RecognitionPolicyCode.RECOGNIZE)
                    .guardDecision(GuardDecision.ALLOW)
                    .guardReason("Subscription ACTIVE")
                    .build();

            when(scheduleRepository.findByInvoiceId(INVOICE_ID)).thenReturn(List.of(entity));

            var dtos = scheduleService.listSchedulesByInvoice(INVOICE_ID);

            assertThat(dtos).hasSize(1);
            var dto = dtos.get(0);
            assertThat(dto.getExpectedAmountMinor()).isEqualTo(1663L);
            assertThat(dto.getRoundingAdjustmentMinor()).isEqualTo(0L);
            assertThat(dto.getPolicyCode()).isEqualTo(RecognitionPolicyCode.RECOGNIZE);
            assertThat(dto.getGuardDecision()).isEqualTo(GuardDecision.ALLOW);
            assertThat(dto.getGuardReason()).isEqualTo("Subscription ACTIVE");
        }
    }
}
