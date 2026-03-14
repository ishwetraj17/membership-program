package com.firstclub.billing.service;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.guard.InvoicePeriodGuard;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionHistoryRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.events.service.DomainEventLog;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.platform.statemachine.StateMachineValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targeted mutation-killing tests for InvoiceService.
 *
 * Each test targets specific surviving PIT mutants identified in the mutation
 * analysis. Tests are grouped by method and annotated with the mutant type
 * they are designed to kill.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InvoiceService — Mutation-Killing Tests")
class InvoiceServiceMutationTest {

    @Mock InvoiceRepository invoiceRepository;
    @Mock InvoiceLineRepository invoiceLineRepository;
    @Mock CreditNoteRepository creditNoteRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock SubscriptionHistoryRepository historyRepository;
    @Mock MembershipPlanRepository planRepository;
    @Mock StateMachineValidator stateMachineValidator;
    @Mock OutboxService outboxService;
    @Mock DomainEventLog domainEventLog;
    @Mock InvoiceTotalService invoiceTotalService;
    @Mock InvoicePeriodGuard invoicePeriodGuard;
    @Mock RevenueRecognitionScheduleService recognitionScheduleService;

    InvoiceService invoiceService;

    @BeforeEach
    void setUp() {
        invoiceService = new InvoiceService(
                invoiceRepository, invoiceLineRepository, creditNoteRepository,
                subscriptionRepository, historyRepository, planRepository,
                stateMachineValidator, outboxService, domainEventLog,
                invoiceTotalService, invoicePeriodGuard);
        // Inject the @Lazy field via reflection
        try {
            var field = InvoiceService.class.getDeclaredField("recognitionScheduleService");
            field.setAccessible(true);
            field.set(invoiceService, recognitionScheduleService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Invoice openInvoice(long id) {
        return Invoice.builder()
                .id(id).userId(10L).subscriptionId(20L)
                .status(InvoiceStatus.OPEN).currency("INR")
                .totalAmount(new BigDecimal("1000.00"))
                .grandTotal(new BigDecimal("1000.00"))
                .subtotal(new BigDecimal("1000.00"))
                .discountTotal(BigDecimal.ZERO).creditTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO)
                .dueDate(LocalDateTime.now().plusDays(7))
                .periodStart(LocalDateTime.now().minusDays(1))
                .periodEnd(LocalDateTime.now().plusDays(29))
                .merchantId(1L)
                .build();
    }

    private MembershipPlan plan(long id, String price) {
        return MembershipPlan.builder()
                .id(id).name("Test Plan").price(new BigDecimal(price))
                .durationInMonths(1).build();
    }

    private User user(long id) {
        return User.builder().id(id).build();
    }

    private Subscription subscription(long id, Subscription.SubscriptionStatus status) {
        User u = user(10L);
        MembershipPlan p = plan(1L, "999.00");
        return Subscription.builder()
                .id(id).user(u).plan(p).status(status)
                .startDate(LocalDateTime.now().minusDays(30))
                .endDate(LocalDateTime.now().plusDays(30))
                .build();
    }

    private CreditNote credit(long id, String amount, String used) {
        return CreditNote.builder()
                .id(id).userId(10L).currency("INR")
                .amount(new BigDecimal(amount))
                .usedAmount(new BigDecimal(used))
                .reason("test credit")
                .build();
    }

    private void stubInvoiceSaveAndLines(Invoice invoice) {
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> {
            Invoice inv = i.getArgument(0);
            if (inv.getId() == null) inv.setId(1L);
            return inv;
        });
        when(invoiceLineRepository.save(any(InvoiceLine.class))).thenAnswer(i -> i.getArgument(0));
        when(invoiceTotalService.recomputeTotals(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
        when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());
        when(creditNoteRepository.findAvailableByUserId(anyLong())).thenReturn(List.of());
    }

    // =========================================================================
    // createInvoiceForSubscription — Mutant targets
    // =========================================================================

    @Nested
    @DisplayName("createInvoiceForSubscription")
    class CreateInvoiceTests {

        /**
         * Kills mutant: line 90 — VoidMethodCallMutator — removed call to
         * InvoicePeriodGuard::assertNoPeriodOverlap.
         * Verifies that the period guard IS called during invoice creation.
         */
        @Test
        @DisplayName("calls invoicePeriodGuard.assertNoPeriodOverlap during creation")
        void create_callsPeriodGuard() {
            MembershipPlan p = plan(1L, "999.00");
            when(planRepository.findById(1L)).thenReturn(Optional.of(p));

            stubInvoiceSaveAndLines(null);

            LocalDateTime start = LocalDateTime.now();
            LocalDateTime end = start.plusDays(30);
            invoiceService.createInvoiceForSubscription(10L, 20L, 1L, start, end);

            verify(invoicePeriodGuard).assertNoPeriodOverlap(20L, start, end);
        }

        /**
         * Kills mutants: lines 126, 134 — RemoveConditionalMutator —
         * subscriptionId != null ternary in event data.
         * Verifies that null subscriptionId is handled correctly (maps to 0L).
         */
        @Test
        @DisplayName("event data uses 0L for null subscriptionId")
        void create_nullSubscriptionId_eventDataUses0() {
            MembershipPlan p = plan(1L, "999.00");
            when(planRepository.findById(1L)).thenReturn(Optional.of(p));
            stubInvoiceSaveAndLines(null);

            // The invoice will have subscriptionId = null for this edge case
            when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> {
                Invoice inv = i.getArgument(0);
                inv.setId(1L);
                inv.setSubscriptionId(null); // force null subscriptionId
                return inv;
            });

            invoiceService.createInvoiceForSubscription(10L, null, 1L,
                    LocalDateTime.now(), LocalDateTime.now().plusDays(30));

            // Verify domainEventLog captures 0L for null subscriptionId
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(domainEventLog).record(eq("INVOICE_CREATED"), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(0L);

            // Verify outboxService also captures 0L
            verify(outboxService).publish(eq(DomainEventTypes.INVOICE_CREATED), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(0L);
        }

        /**
         * Kills mutant: line 131 — VoidMethodCallMutator — removed call to
         * outboxService::publish for INVOICE_CREATED.
         */
        @Test
        @DisplayName("publishes INVOICE_CREATED outbox event")
        void create_publishesOutboxEvent() {
            MembershipPlan p = plan(1L, "999.00");
            when(planRepository.findById(1L)).thenReturn(Optional.of(p));
            stubInvoiceSaveAndLines(null);

            invoiceService.createInvoiceForSubscription(10L, 20L, 1L,
                    LocalDateTime.now(), LocalDateTime.now().plusDays(30));

            verify(outboxService).publish(eq(DomainEventTypes.INVOICE_CREATED), anyMap());
        }

        /**
         * Kills mutants: lines 126, 134 — RemoveConditionalMutator_EQUAL_ELSE —
         * non-null subscriptionId must appear in event data (not 0L).
         */
        @Test
        @DisplayName("event data uses actual subscriptionId when non-null")
        void create_nonNullSubscriptionId_eventDataUsesActualId() {
            MembershipPlan p = plan(1L, "999.00");
            when(planRepository.findById(1L)).thenReturn(Optional.of(p));
            stubInvoiceSaveAndLines(null);

            invoiceService.createInvoiceForSubscription(10L, 20L, 1L,
                    LocalDateTime.now(), LocalDateTime.now().plusDays(30));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(domainEventLog).record(eq("INVOICE_CREATED"), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(20L);

            verify(outboxService).publish(eq(DomainEventTypes.INVOICE_CREATED), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(20L);
        }
    }

    // =========================================================================
    // onPaymentSucceeded — Mutant targets
    // =========================================================================

    @Nested
    @DisplayName("onPaymentSucceeded")
    class PaymentSucceededTests {

        /**
         * Kills mutant: line 178 — RemoveConditionalMutator_EQUAL_ELSE —
         * replaced equality check with false (disabling idempotency guard).
         * Verifies that already-PAID invoice returns silently without
         * re-publishing or re-activating.
         */
        @Test
        @DisplayName("already-PAID invoice returns silently without side effects")
        void paid_returnsIdempotently() {
            Invoice paidInvoice = openInvoice(1L);
            paidInvoice.setStatus(InvoiceStatus.PAID);

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(paidInvoice));

            invoiceService.onPaymentSucceeded(1L);

            // Must NOT call state machine, outbox, or subscription activation
            verifyNoInteractions(stateMachineValidator);
            verify(outboxService, never()).publish(any(), anyMap());
            verify(subscriptionRepository, never()).findById(anyLong());
        }

        /**
         * Kills mutant: line 184 — VoidMethodCallMutator — removed call to
         * StateMachineValidator::validate.
         */
        @Test
        @DisplayName("validates OPEN→PAID state transition via state machine")
        void payment_validatesStateTransition() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            invoiceService.onPaymentSucceeded(1L);

            verify(stateMachineValidator).validate("INVOICE", InvoiceStatus.OPEN, InvoiceStatus.PAID);
        }

        /**
         * Kills mutant: line 197 — VoidMethodCallMutator — removed call to
         * outboxService::publish for PAYMENT_SUCCEEDED.
         */
        @Test
        @DisplayName("publishes PAYMENT_SUCCEEDED outbox event")
        void payment_publishesOutboxEvent() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            verify(outboxService).publish(eq(DomainEventTypes.PAYMENT_SUCCEEDED), anyMap());
        }

        /**
         * Kills mutants: lines 192, 199 — RemoveConditionalMutator —
         * subscriptionId null-check ternary in event data.
         */
        @Test
        @DisplayName("event data handles null subscriptionId as 0L")
        void payment_nullSubscriptionId_maps0L() {
            Invoice invoice = openInvoice(1L);
            invoice.setSubscriptionId(null);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            invoiceService.onPaymentSucceeded(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(domainEventLog).record(eq("PAYMENT_SUCCEEDED"), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(0L);
        }

        /**
         * Kills mutants: lines 192, 199 — RemoveConditionalMutator_EQUAL_ELSE —
         * non-null subscriptionId must appear in event data (not 0L).
         */
        @Test
        @DisplayName("event data uses actual subscriptionId when non-null")
        void payment_nonNullSubscriptionId_eventDataUsesActualId() {
            Invoice invoice = openInvoice(1L);  // has subscriptionId=20L
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(domainEventLog).record(eq("PAYMENT_SUCCEEDED"), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(20L);

            verify(outboxService).publish(eq(DomainEventTypes.PAYMENT_SUCCEEDED), captor.capture());
            assertThat(captor.getValue().get("subscriptionId")).isEqualTo(20L);
        }

        /**
         * Kills mutant: line 204 — RemoveConditionalMutator_EQUAL_IF —
         * replaced equality check with true (always activating subscription
         * even when subscriptionId is null).
         */
        @Test
        @DisplayName("does NOT activate subscription when subscriptionId is null")
        void payment_nullSubId_noActivation() {
            Invoice invoice = openInvoice(1L);
            invoice.setSubscriptionId(null);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            invoiceService.onPaymentSucceeded(1L);

            verify(subscriptionRepository, never()).findById(anyLong());
        }
    }

    // =========================================================================
    // activateSubscription (via onPaymentSucceeded) — Mutant targets
    // =========================================================================

    @Nested
    @DisplayName("activateSubscription (via onPaymentSucceeded)")
    class ActivateSubscriptionTests {

        private Invoice setupPaymentSucceeded() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());
            return invoice;
        }

        /**
         * Kills mutant: line 281 — RemoveConditionalMutator_EQUAL_ELSE —
         * removed conditional: when subscription not found, must return silently.
         */
        @Test
        @DisplayName("missing V1 subscription returns silently")
        void missingSubscription_returnsGracefully() {
            setupPaymentSucceeded();
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.empty());

            assertThatCode(() -> invoiceService.onPaymentSucceeded(1L))
                    .doesNotThrowAnyException();

            // No subscription save should happen
            verify(subscriptionRepository, never()).save(any());
        }

        /**
         * Kills mutant: line 289 — RemoveConditionalMutator_EQUAL_ELSE —
         * removed conditional: already-ACTIVE subscription should not be
         * re-activated.
         */
        @Test
        @DisplayName("already-ACTIVE subscription is not re-activated")
        void alreadyActive_skipsReactivation() {
            setupPaymentSucceeded();
            Subscription activeSub = subscription(20L, Subscription.SubscriptionStatus.ACTIVE);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(activeSub));

            invoiceService.onPaymentSucceeded(1L);

            // Subscription should NOT be saved (no status change)
            verify(subscriptionRepository, never()).save(any(Subscription.class));
            verify(stateMachineValidator, never()).validate(eq("SUBSCRIPTION"), any(), any());
        }

        /**
         * Kills mutant: line 295 — VoidMethodCallMutator — removed call to
         * StateMachineValidator::validate for subscription.
         */
        @Test
        @DisplayName("validates PENDING→ACTIVE subscription transition")
        void activation_validatesStateTransition() {
            setupPaymentSucceeded();
            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            verify(stateMachineValidator).validate(
                    "SUBSCRIPTION",
                    Subscription.SubscriptionStatus.PENDING,
                    Subscription.SubscriptionStatus.ACTIVE);
        }

        /**
         * Kills mutant: line 315 — VoidMethodCallMutator — removed call to
         * outboxService::publish for SUBSCRIPTION_ACTIVATED.
         */
        @Test
        @DisplayName("publishes SUBSCRIPTION_ACTIVATED outbox event")
        void activation_publishesOutbox() {
            setupPaymentSucceeded();
            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            verify(outboxService).publish(eq(DomainEventTypes.SUBSCRIPTION_ACTIVATED), anyMap());
        }

        /**
         * Kills mutants: lines 312, 317 — RemoveConditionalMutator_EQUAL_ELSE/IF —
         * sub.getUser() != null ternary in event data for subscription activation.
         */
        @Test
        @DisplayName("activation event data handles null user as 0L")
        void activation_nullUser_maps0L() {
            setupPaymentSucceeded();
            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            sub.setUser(null);
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(outboxService).publish(eq(DomainEventTypes.SUBSCRIPTION_ACTIVATED), captor.capture());
            assertThat(captor.getValue().get("userId")).isEqualTo(0L);
        }
    }

    // =========================================================================
    // applyAvailableCredits — Mutant targets
    // =========================================================================

    @Nested
    @DisplayName("applyAvailableCredits")
    class ApplyCreditsTests {

        /**
         * Kills mutants: lines 242, 248 — ConditionalsBoundaryMutator and
         * RemoveConditionalMutator_ORDER_ELSE — boundary checks on
         * remaining.compareTo(ZERO).
         */
        @Test
        @DisplayName("zero totalAmount returns invoice without applying credits")
        void zeroTotal_skipsCredits() {
            Invoice invoice = openInvoice(1L);
            invoice.setTotalAmount(BigDecimal.ZERO);

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            InvoiceDTO dto = invoiceService.applyAvailableCredits(10L, 1L);

            // No credit notes should be queried since totalAmount is zero
            verify(creditNoteRepository, never()).findAvailableByUserId(anyLong());
        }

        /**
         * Kills mutant: line 262 — VoidMethodCallMutator — removed call to
         * credit.setUsedAmount.
         * Verifies that credit.usedAmount is incremented after application.
         */
        @Test
        @DisplayName("usedAmount is updated on credit note after application")
        void credits_updatesUsedAmount() {
            Invoice invoice = openInvoice(1L);
            invoice.setTotalAmount(new BigDecimal("500.00"));

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

            CreditNote cn = credit(1L, "1000.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            invoiceService.applyAvailableCredits(10L, 1L);

            // Verify that credit note usedAmount was incremented
            ArgumentCaptor<CreditNote> captor = ArgumentCaptor.forClass(CreditNote.class);
            verify(creditNoteRepository).save(captor.capture());
            assertThat(captor.getValue().getUsedAmount()).isEqualByComparingTo("500.00");
        }

        /**
         * Kills mutant: line 248 boundary — remaining becomes zero after
         * first credit, loop should break and not process second credit.
         */
        @Test
        @DisplayName("loop breaks when remaining reaches zero")
        void credits_loopBreaksAtZero() {
            Invoice invoice = openInvoice(1L);
            invoice.setTotalAmount(new BigDecimal("300.00"));

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

            CreditNote cn1 = credit(1L, "300.00", "0.00");
            CreditNote cn2 = credit(2L, "200.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn1, cn2));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            invoiceService.applyAvailableCredits(10L, 1L);

            // Only first credit note should be saved (cn2 should not be touched)
            verify(creditNoteRepository, times(1)).save(any(CreditNote.class));
        }
    }

    // =========================================================================
    // toDto — Mutant target
    // =========================================================================

    @Nested
    @DisplayName("toDto mapping")
    class ToDtoTests {

        /**
         * Kills mutant: line 342 — NullReturnValsMutator — replaced return
         * value with null in lambda$toDto$4.
         * Verifies that invoice lines are correctly mapped in the DTO.
         */
        @Test
        @DisplayName("invoice lines are mapped to DTO including all fields")
        void toDto_mapsLinesCorrectly() {
            Invoice invoice = openInvoice(1L);

            InvoiceLine line = InvoiceLine.builder()
                    .id(100L).invoiceId(1L)
                    .lineType(InvoiceLineType.PLAN_CHARGE)
                    .description("Subscription: Premium")
                    .amount(new BigDecimal("999.00"))
                    .build();

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(line));

            InvoiceDTO dto = invoiceService.findById(1L);

            assertThat(dto.getLines()).hasSize(1);
            InvoiceLineDTO lineDto = dto.getLines().get(0);
            assertThat(lineDto.getId()).isEqualTo(100L);
            assertThat(lineDto.getInvoiceId()).isEqualTo(1L);
            assertThat(lineDto.getLineType()).isEqualTo(InvoiceLineType.PLAN_CHARGE);
            assertThat(lineDto.getDescription()).isEqualTo("Subscription: Premium");
            assertThat(lineDto.getAmount()).isEqualByComparingTo("999.00");
        }
    }

    // =========================================================================
    // activateSubscription — non-null user branch
    // =========================================================================

    @Nested
    @DisplayName("activateSubscription — non-null user event data")
    class ActivateSubscriptionUserTests {

        /**
         * Kills mutants: lines 312, 317 — RemoveConditionalMutator_EQUAL_ELSE —
         * non-null user → event data must contain the user's actual ID.
         */
        @Test
        @DisplayName("activation event data uses actual userId when user is non-null")
        void activation_nonNullUser_usesActualId() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            Subscription sub = subscription(20L, Subscription.SubscriptionStatus.PENDING);
            // sub already has user with id=10L from helper
            when(subscriptionRepository.findById(20L)).thenReturn(Optional.of(sub));
            when(subscriptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(historyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            invoiceService.onPaymentSucceeded(1L);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(domainEventLog).record(eq("SUBSCRIPTION_ACTIVATED"), captor.capture());
            assertThat(captor.getValue().get("userId")).isEqualTo(10L);

            verify(outboxService).publish(eq(DomainEventTypes.SUBSCRIPTION_ACTIVATED), captor.capture());
            assertThat(captor.getValue().get("userId")).isEqualTo(10L);
        }
    }

    // =========================================================================
    // fetchOpenInvoice — status guard
    // =========================================================================

    @Nested
    @DisplayName("fetchOpenInvoice (via applyAvailableCredits)")
    class FetchOpenInvoiceTests {

        /**
         * Kills mutant: line 326 — RemoveConditionalMutator_EQUAL_ELSE —
         * replaces `invoice.getStatus() != InvoiceStatus.OPEN` with false.
         * A non-OPEN invoice must be rejected.
         */
        @Test
        @DisplayName("applyAvailableCredits rejects PAID invoice with INVOICE_NOT_OPEN")
        void paidInvoice_throws() {
            Invoice paidInv = openInvoice(1L);
            paidInv.setStatus(InvoiceStatus.PAID);

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(paidInv));

            assertThatThrownBy(() -> invoiceService.applyAvailableCredits(10L, 1L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not OPEN");
        }

        @Test
        @DisplayName("applyAvailableCredits rejects VOID invoice")
        void voidInvoice_throws() {
            Invoice voidInv = openInvoice(1L);
            voidInv.setStatus(InvoiceStatus.VOID);

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(voidInv));

            assertThatThrownBy(() -> invoiceService.applyAvailableCredits(10L, 1L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not OPEN");
        }
    }

    // =========================================================================
    // applyAvailableCredits — return value null mutant
    // =========================================================================

    @Nested
    @DisplayName("applyAvailableCredits — return value")
    class ApplyCreditsReturnTests {

        /**
         * Kills mutant: line 160 — NullReturnValsMutator — replaced return
         * value with null in public applyAvailableCredits.
         */
        @Test
        @DisplayName("applyAvailableCredits returns non-null DTO")
        void apply_returnsNonNullDto() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of());
            when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(anyLong())).thenReturn(List.of());

            InvoiceDTO result = invoiceService.applyAvailableCredits(10L, 1L);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        }
    }
}
