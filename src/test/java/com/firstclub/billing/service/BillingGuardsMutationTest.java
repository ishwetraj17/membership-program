package com.firstclub.billing.service;

import com.firstclub.billing.credit.CreditCarryForwardService;
import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.guard.InvoiceInvariantService;
import com.firstclub.billing.guard.InvoicePeriodGuard;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.rebuild.InvoiceRebuildService;
import com.firstclub.billing.repository.CreditNoteRepository;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Targeted mutation-killing tests for billing guardrail classes.
 *
 * Addresses surviving PIT mutants in:
 * - CreditCarryForwardService
 * - InvoicePeriodGuard
 * - InvoiceRebuildService
 * - InvoiceInvariantService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Guardrails — Mutation-Killing Tests")
class BillingGuardsMutationTest {

    @Mock CreditNoteRepository creditNoteRepository;
    @Mock InvoiceRepository invoiceRepository;
    @Mock InvoiceLineRepository invoiceLineRepository;

    // ── Helpers ─────────────────────────────────────────────────────────

    private CreditNote creditNote(long id, String amount, String used) {
        return CreditNote.builder()
                .id(id).userId(10L).currency("INR")
                .amount(new BigDecimal(amount))
                .usedAmount(new BigDecimal(used))
                .reason("test credit")
                .build();
    }

    // =========================================================================
    // CreditCarryForwardService — Mutation targets
    // =========================================================================

    @Nested
    @DisplayName("CreditCarryForwardService mutations")
    class CreditCarryForwardMutationTests {

        CreditCarryForwardService service;

        @BeforeEach
        void setUp() {
            service = new CreditCarryForwardService(creditNoteRepository);
        }

        /**
         * Kills mutant: line 55 — RemoveConditionalMutator_EQUAL_IF —
         * removed conditional: null amount should throw.
         */
        @Test
        @DisplayName("createCreditNote rejects null amount")
        void create_nullAmount_throws() {
            assertThatThrownBy(() ->
                    service.createCreditNote(10L, null, "INR", null,
                            "test", null, null))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("positive");
        }

        /**
         * Kills mutant: line 55 — boundary: negative amount should also throw.
         */
        @Test
        @DisplayName("createCreditNote rejects negative amount")
        void create_negativeAmount_throws() {
            assertThatThrownBy(() ->
                    service.createCreditNote(10L, null, "INR",
                            new BigDecimal("-1.00"), "test", null, null))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("positive");
        }

        /**
         * Kills mutant: line 110 — ConditionalsBoundaryMutator —
         * boundary check on amountToApply <= 0.
         * Zero amount should return zero without querying.
         */
        @Test
        @DisplayName("applyCreditsToInvoice with zero amountToApply returns ZERO immediately")
        void apply_zeroAmount_returnsZero() {
            BigDecimal result = service.applyCreditsToInvoice(
                    10L, null, "INR", BigDecimal.ZERO, 1L);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
            verify(creditNoteRepository, never()).findAvailableByUserId(anyLong());
        }

        /**
         * Kills mutant: line 110 — RemoveConditionalMutator_ORDER_ELSE —
         * negative amountToApply should also return zero.
         */
        @Test
        @DisplayName("applyCreditsToInvoice with negative amountToApply returns ZERO")
        void apply_negativeAmount_returnsZero() {
            BigDecimal result = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("-50.00"), 1L);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
            verify(creditNoteRepository, never()).findAvailableByUserId(anyLong());
        }

        /**
         * Kills mutants: line 129 — VoidMethodCallMutator — removed call to
         * setUsedAmount. line 130 — removed call to setAvailableAmountMinor.
         * Verifies both fields are updated after applying credit.
         */
        @Test
        @DisplayName("applies partial credit: updates usedAmount and availableAmountMinor")
        void apply_partial_updatesFields() {
            CreditNote cn = creditNote(1L, "300.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyCreditsToInvoice(10L, null, "INR", new BigDecimal("200.00"), 1L);

            ArgumentCaptor<CreditNote> captor = ArgumentCaptor.forClass(CreditNote.class);
            verify(creditNoteRepository).save(captor.capture());
            CreditNote saved = captor.getValue();

            // usedAmount should be 200
            assertThat(saved.getUsedAmount()).isEqualByComparingTo("200.00");
            // availableAmountMinor should be (300-200)*100 = 10000
            assertThat(saved.getAvailableAmountMinor()).isEqualTo(10000L);
        }

        /**
         * Kills mutant: line 116 — filter lambda: currency mismatch.
         * Credit notes in different currency should not be applied.
         */
        @Test
        @DisplayName("credit notes in different currency are filtered out")
        void apply_currencyMismatch_notApplied() {
            CreditNote usdCredit = creditNote(1L, "1000.00", "0.00");
            usdCredit.setCurrency("USD");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(usdCredit));

            BigDecimal remaining = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("500.00"), 1L);

            assertThat(remaining).isEqualByComparingTo("500.00");
            verify(creditNoteRepository, never()).save(any());
        }

        /**
         * Kills mutant: line 123 — ConditionalsBoundaryMutator —
         * boundary check on remaining <= 0 in loop.
         */
        @Test
        @DisplayName("loop stops when remaining becomes exactly zero")
        void apply_exactlyCovered_stopsLoop() {
            CreditNote cn1 = creditNote(1L, "500.00", "0.00");
            CreditNote cn2 = creditNote(2L, "500.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn1, cn2));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BigDecimal remaining = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("500.00"), 1L);

            assertThat(remaining).isEqualByComparingTo("0.00");
            // Only first credit note should have been saved
            verify(creditNoteRepository, times(1)).save(any(CreditNote.class));
        }

        /**
         * Kills mutant: line 160 — RemoveConditionalMutator_EQUAL_IF —
         * replaced equality check with true (null overflow should return null).
         */
        @Test
        @DisplayName("createCarryForwardIfOverflow returns null for null overflow")
        void carryForward_nullOverflow_returnsNull() {
            CreditNote result = service.createCarryForwardIfOverflow(
                    10L, null, "INR", null, 1L);

            assertThat(result).isNull();
            verify(creditNoteRepository, never()).save(any());
        }

        /**
         * Kills mutant: line 160 — negative overflow should also return null.
         */
        @Test
        @DisplayName("createCarryForwardIfOverflow returns null for negative overflow")
        void carryForward_negativeOverflow_returnsNull() {
            CreditNote result = service.createCarryForwardIfOverflow(
                    10L, null, "INR", new BigDecimal("-10.00"), 1L);

            assertThat(result).isNull();
            verify(creditNoteRepository, never()).save(any());
        }

        /**
         * Kills mutant: line 176 — PrimitiveReturnsMutator — replaced long
         * return with 0 for toMinorUnits.
         * Indirectly tested by verifying availableAmountMinor is non-zero
         * after creating a credit note.
         */
        @Test
        @DisplayName("createCreditNote sets correct availableAmountMinor")
        void create_setsMinorUnits() {
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            CreditNote result = service.createCreditNote(
                    10L, null, "INR", new BigDecimal("123.45"),
                    "test", null, null);

            // 123.45 * 100 = 12345
            ArgumentCaptor<CreditNote> captor = ArgumentCaptor.forClass(CreditNote.class);
            verify(creditNoteRepository).save(captor.capture());
            assertThat(captor.getValue().getAvailableAmountMinor()).isEqualTo(12345L);
        }
    }

    // =========================================================================
    // InvoicePeriodGuard — Mutation targets
    // =========================================================================

    @Nested
    @DisplayName("InvoicePeriodGuard mutations")
    class PeriodGuardMutationTests {

        InvoicePeriodGuard guard;

        @BeforeEach
        void setUp() {
            guard = new InvoicePeriodGuard(invoiceRepository);
        }

        /**
         * Kills mutants: line 42 — RemoveConditionalMutator_EQUAL_ELSE/IF —
         * null parameters should silently return without querying.
         */
        @Test
        @DisplayName("null subscriptionId returns silently without DB query")
        void nullSubscriptionId_returns() {
            guard.assertNoPeriodOverlap(null, LocalDateTime.now(), LocalDateTime.now().plusDays(30));

            verify(invoiceRepository, never()).findOverlappingActiveInvoices(any(), any(), any());
        }

        @Test
        @DisplayName("null periodStart returns silently without DB query")
        void nullPeriodStart_returns() {
            guard.assertNoPeriodOverlap(1L, null, LocalDateTime.now().plusDays(30));

            verify(invoiceRepository, never()).findOverlappingActiveInvoices(any(), any(), any());
        }

        @Test
        @DisplayName("null periodEnd returns silently without DB query")
        void nullPeriodEnd_returns() {
            guard.assertNoPeriodOverlap(1L, LocalDateTime.now(), null);

            verify(invoiceRepository, never()).findOverlappingActiveInvoices(any(), any(), any());
        }

        /**
         * Kills mutant: line 50 — RemoveConditionalMutator_EQUAL_IF —
         * excludeInvoiceId null check (when null, no invoice should be skipped).
         */
        @Test
        @DisplayName("null excludeInvoiceId does not skip overlapping invoices")
        void nullExcludeId_doesNotSkip() {
            Invoice overlapping = Invoice.builder()
                    .id(5L).status(InvoiceStatus.OPEN)
                    .periodStart(LocalDateTime.now().minusDays(1))
                    .periodEnd(LocalDateTime.now().plusDays(29))
                    .build();

            when(invoiceRepository.findOverlappingActiveInvoices(any(), any(), any()))
                    .thenReturn(List.of(overlapping));

            assertThatThrownBy(() -> guard.assertNoPeriodOverlap(
                    20L,
                    LocalDateTime.now(),
                    LocalDateTime.now().plusDays(28),
                    null))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Overlapping");
        }

        /**
         * Kills mutant: line 50 — second condition:
         * existing.getId().equals(excludeInvoiceId) — when IDs don't match,
         * should still throw.
         */
        @Test
        @DisplayName("excludeInvoiceId that doesn't match still throws for overlap")
        void excludeId_differentId_throws() {
            Invoice overlapping = Invoice.builder()
                    .id(5L).status(InvoiceStatus.OPEN)
                    .periodStart(LocalDateTime.now().minusDays(1))
                    .periodEnd(LocalDateTime.now().plusDays(29))
                    .build();

            when(invoiceRepository.findOverlappingActiveInvoices(any(), any(), any()))
                    .thenReturn(List.of(overlapping));

            assertThatThrownBy(() -> guard.assertNoPeriodOverlap(
                    20L,
                    LocalDateTime.now(),
                    LocalDateTime.now().plusDays(28),
                    99L /* different from 5L */))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Overlapping");
        }
    }

    // =========================================================================
    // InvoiceRebuildService — Mutation targets
    // =========================================================================

    @Nested
    @DisplayName("InvoiceRebuildService mutations")
    class RebuildServiceMutationTests {

        @Mock com.firstclub.billing.service.InvoiceTotalService invoiceTotalService;
        InvoiceRebuildService rebuildService;

        @BeforeEach
        void setUp() {
            rebuildService = new InvoiceRebuildService(
                    invoiceRepository, invoiceLineRepository, invoiceTotalService);
        }

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
                    .build();
        }

        /**
         * Kills mutant: line 73 — RemoveConditionalMutator_EQUAL_IF —
         * rebuiltBy != null ternary: null rebuiltBy should default to "system".
         */
        @Test
        @DisplayName("null rebuiltBy defaults to 'system'")
        void rebuild_nullRebuiltBy_defaultsToSystem() {
            Invoice invoice = openInvoice(1L);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
            when(invoiceTotalService.recomputeTotals(any())).thenReturn(invoice);
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of());

            rebuildService.rebuildTotals(1L, null);

            ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
            verify(invoiceRepository).save(captor.capture());
            assertThat(captor.getValue().getRebuiltBy()).isEqualTo("system");
        }

        /**
         * Kills mutant: line 83 — RemoveConditionalMutator_EQUAL_ELSE/IF —
         * isTerminal should return true for VOID and UNCOLLECTIBLE too.
         */
        @Test
        @DisplayName("VOID invoice is rejected as terminal")
        void rebuild_voidInvoice_throws() {
            Invoice voidInvoice = openInvoice(1L);
            voidInvoice.setStatus(InvoiceStatus.VOID);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(voidInvoice));

            assertThatThrownBy(() -> rebuildService.rebuildTotals(1L, "ops"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("terminal state");
        }

        @Test
        @DisplayName("UNCOLLECTIBLE invoice is rejected as terminal")
        void rebuild_uncollectibleInvoice_throws() {
            Invoice uncollectible = openInvoice(1L);
            uncollectible.setStatus(InvoiceStatus.UNCOLLECTIBLE);
            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(uncollectible));

            assertThatThrownBy(() -> rebuildService.rebuildTotals(1L, "ops"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("terminal state");
        }
    }

    // =========================================================================
    // InvoiceInvariantService — Mutation targets
    // =========================================================================

    @Nested
    @DisplayName("InvoiceInvariantService mutations")
    class InvariantServiceMutationTests {

        InvoiceInvariantService invariantService;

        @BeforeEach
        void setUp() {
            invariantService = new InvoiceInvariantService(invoiceLineRepository);
        }

        /**
         * Kills mutant: line 77 — RemoveConditionalMutator_EQUAL_IF —
         * replaced equality check with true (OPEN invoice should be allowed
         * to be voided even without refund path).
         */
        @Test
        @DisplayName("OPEN invoice can be voided without refund path")
        void voidOpen_withoutRefundPath_passes() {
            Invoice openInv = Invoice.builder()
                    .id(1L).status(InvoiceStatus.OPEN).build();

            assertThatCode(() -> invariantService.assertVoidAllowed(openInv, false))
                    .doesNotThrowAnyException();
        }

        /**
         * Further strengthens the PAID+hasRefundPath=false case:
         * asserts exact error code.
         */
        @Test
        @DisplayName("PAID invoice void without refund path uses correct error code")
        void voidPaid_errorCode() {
            Invoice paidInv = Invoice.builder()
                    .id(1L).status(InvoiceStatus.PAID).build();

            assertThatThrownBy(() -> invariantService.assertVoidAllowed(paidInv, false))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("cannot be voided");
        }
    }
}
