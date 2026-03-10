package com.firstclub.billing;

import com.firstclub.billing.credit.CreditCarryForwardService;
import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.guard.InvoiceInvariantService;
import com.firstclub.billing.guard.InvoicePeriodGuard;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.rebuild.InvoiceRebuildService;
import com.firstclub.billing.repository.CreditNoteRepository;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Phase 17 — billing guardrails unit tests.
 *
 * Covered:
 *  1. InvoiceRebuildService — rebuild fixes corrupted totals
 *  2. InvoiceRebuildService — terminal-state invoice is blocked
 *  3. InvoiceInvariantService — total-matches-lines passes
 *  4. InvoiceInvariantService — total-mismatch throws
 *  5. InvoiceInvariantService — paid-void blocked without refund path
 *  6. InvoiceInvariantService — credit-exceeds-balance throws
 *  7. InvoicePeriodGuard     — no overlap passes
 *  8. InvoicePeriodGuard     — overlapping active invoice rejected
 *  9. CreditCarryForwardService — apply credit returns remaining balance
 * 10. CreditCarryForwardService — carry-forward note created on overflow
 * 11. CreditCarryForwardService — expired credit note not applied
 * 12. CreditCarryForwardService — create credit note with valid amount
 * 13. CreditCarryForwardService — create credit note rejects zero amount
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 17 — Billing Guardrails Tests")
class Phase17BillingGuardsTests {

    // ─── shared mocks ────────────────────────────────────────────────────────

    @Mock InvoiceRepository      invoiceRepository;
    @Mock InvoiceLineRepository  invoiceLineRepository;
    @Mock CreditNoteRepository   creditNoteRepository;
    @Mock InvoiceTotalService    invoiceTotalService;

    // ─── helpers ─────────────────────────────────────────────────────────────

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

    private InvoiceLine line(long invoiceId, InvoiceLineType type, String amount) {
        return InvoiceLine.builder()
                .id(1L).invoiceId(invoiceId).lineType(type)
                .description(type.name()).amount(new BigDecimal(amount))
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

    // =========================================================================
    // InvoiceRebuildService
    // =========================================================================

    @Nested
    @DisplayName("InvoiceRebuildService")
    class RebuildServiceTests {

        InvoiceRebuildService rebuildService;

        @BeforeEach
        void setUp() {
            rebuildService = new InvoiceRebuildService(
                    invoiceRepository, invoiceLineRepository, invoiceTotalService);
        }

        @Test
        @DisplayName("rebuild fixes corrupted totals and stamps audit fields")
        void rebuild_fixesCorruptedTotals() {
            Invoice corruptInvoice = openInvoice(1L);
            // Simulate corrupted grandTotal (should be 900 after 100 credit)
            corruptInvoice.setGrandTotal(new BigDecimal("999.99"));

            Invoice fixedInvoice = openInvoice(1L);
            fixedInvoice.setGrandTotal(new BigDecimal("900.00"));

            when(invoiceRepository.findById(1L)).thenReturn(Optional.of(corruptInvoice));
            when(invoiceTotalService.recomputeTotals(corruptInvoice)).thenReturn(fixedInvoice);
            when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of());

            var dto = rebuildService.rebuildTotals(1L, "ops-user");

            assertThat(dto.getGrandTotal()).isEqualByComparingTo("900.00");
            // Audit fields stamped on the saved invoice
            verify(invoiceRepository, atLeastOnce()).save(argThat(inv ->
                    "ops-user".equals(inv.getRebuiltBy()) && inv.getRebuiltAt() != null));
        }

        @Test
        @DisplayName("rebuild of PAID invoice throws INVOICE_TERMINAL_STATE")
        void rebuild_paidInvoice_throws() {
            Invoice paidInvoice = openInvoice(2L);
            paidInvoice.setStatus(InvoiceStatus.PAID);

            when(invoiceRepository.findById(2L)).thenReturn(Optional.of(paidInvoice));

            assertThatThrownBy(() -> rebuildService.rebuildTotals(2L, "ops"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("terminal state");
        }

        @Test
        @DisplayName("rebuild of non-existent invoice throws INVOICE_NOT_FOUND")
        void rebuild_notFound_throws() {
            when(invoiceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> rebuildService.rebuildTotals(99L, "ops"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }
    }

    // =========================================================================
    // InvoiceInvariantService
    // =========================================================================

    @Nested
    @DisplayName("InvoiceInvariantService")
    class InvariantServiceTests {

        InvoiceInvariantService invariantService;

        @BeforeEach
        void setUp() {
            invariantService = new InvoiceInvariantService(invoiceLineRepository);
        }

        @Test
        @DisplayName("assertTotalMatchesLines passes when grandTotal equals sum-of-lines")
        void totalMatchesLines_passes() {
            Invoice inv = openInvoice(1L);
            inv.setGrandTotal(new BigDecimal("1000.00"));

            when(invoiceLineRepository.findByInvoiceId(1L))
                    .thenReturn(List.of(line(1L, InvoiceLineType.PLAN_CHARGE, "1000.00")));

            assertThatCode(() -> invariantService.assertTotalMatchesLines(inv))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertTotalMatchesLines throws INVOICE_TOTAL_MISMATCH when totals diverge")
        void totalMismatch_throws() {
            Invoice inv = openInvoice(1L);
            inv.setGrandTotal(new BigDecimal("999.00")); // wrong

            when(invoiceLineRepository.findByInvoiceId(1L))
                    .thenReturn(List.of(line(1L, InvoiceLineType.PLAN_CHARGE, "1000.00")));

            assertThatThrownBy(() -> invariantService.assertTotalMatchesLines(inv))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("mismatch");
        }

        @Test
        @DisplayName("assertVoidAllowed blocks PAID invoice without refund path")
        void voidPaid_withoutRefundPath_throws() {
            Invoice paidInv = openInvoice(1L);
            paidInv.setStatus(InvoiceStatus.PAID);

            assertThatThrownBy(() -> invariantService.assertVoidAllowed(paidInv, false))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("PAID");
        }

        @Test
        @DisplayName("assertVoidAllowed allows PAID invoice when refund path exists")
        void voidPaid_withRefundPath_passes() {
            Invoice paidInv = openInvoice(1L);
            paidInv.setStatus(InvoiceStatus.PAID);

            assertThatCode(() -> invariantService.assertVoidAllowed(paidInv, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("assertCreditWithinBalance throws when credit exceeds balance")
        void creditExceedsBalance_throws() {
            assertThatThrownBy(() ->
                    invariantService.assertCreditWithinBalance(
                            new BigDecimal("500"), new BigDecimal("200"), 5L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("exceeds available balance");
        }

        @Test
        @DisplayName("assertCreditWithinBalance passes when credit equals balance")
        void creditEqualsBalance_passes() {
            assertThatCode(() ->
                    invariantService.assertCreditWithinBalance(
                            new BigDecimal("200"), new BigDecimal("200"), 5L))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // InvoicePeriodGuard
    // =========================================================================

    @Nested
    @DisplayName("InvoicePeriodGuard")
    class PeriodGuardTests {

        InvoicePeriodGuard guard;

        @BeforeEach
        void setUp() {
            guard = new InvoicePeriodGuard(invoiceRepository);
        }

        @Test
        @DisplayName("no overlap passes silently")
        void noOverlap_passes() {
            when(invoiceRepository.findOverlappingActiveInvoices(any(), any(), any()))
                    .thenReturn(List.of());

            assertThatCode(() -> guard.assertNoPeriodOverlap(
                    20L,
                    LocalDateTime.now().plusDays(30),
                    LocalDateTime.now().plusDays(60)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("overlapping active invoice throws OVERLAPPING_INVOICE_PERIOD")
        void overlap_throws() {
            Invoice existing = openInvoice(5L);
            when(invoiceRepository.findOverlappingActiveInvoices(any(), any(), any()))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> guard.assertNoPeriodOverlap(
                    20L,
                    LocalDateTime.now().minusDays(1),
                    LocalDateTime.now().plusDays(29)))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Overlapping active invoice");
        }

        @Test
        @DisplayName("excludeInvoiceId skips the excluded invoice")
        void overlap_withExclude_passes() {
            Invoice existing = openInvoice(5L); // same id as excluded
            when(invoiceRepository.findOverlappingActiveInvoices(any(), any(), any()))
                    .thenReturn(List.of(existing));

            assertThatCode(() -> guard.assertNoPeriodOverlap(
                    20L,
                    LocalDateTime.now().minusDays(1),
                    LocalDateTime.now().plusDays(29),
                    5L /* excludeInvoiceId */))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // CreditCarryForwardService
    // =========================================================================

    @Nested
    @DisplayName("CreditCarryForwardService")
    class CreditCarryForwardTests {

        CreditCarryForwardService service;

        @BeforeEach
        void setUp() {
            service = new CreditCarryForwardService(creditNoteRepository);
        }

        @Test
        @DisplayName("applyCreditsToInvoice returns zero when credit fully covers amount")
        void apply_fullyCovered_returnsZero() {
            CreditNote cn = credit(1L, "1500.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BigDecimal remaining = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("1000.00"), 1L);

            assertThat(remaining).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("applyCreditsToInvoice returns positive remainder when credit insufficient")
        void apply_partial_returnsRemainder() {
            CreditNote cn = credit(1L, "300.00", "0.00");
            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(cn));
            when(creditNoteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            BigDecimal remaining = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("1000.00"), 1L);

            assertThat(remaining).isEqualByComparingTo("700.00");
        }

        @Test
        @DisplayName("expired credit note is not applied")
        void apply_expired_notConsumed() {
            CreditNote expired = credit(2L, "500.00", "0.00");
            expired.setExpiresAt(LocalDateTime.now().minusDays(1)); // already expired

            when(creditNoteRepository.findAvailableByUserId(10L)).thenReturn(List.of(expired));

            BigDecimal remaining = service.applyCreditsToInvoice(
                    10L, null, "INR", new BigDecimal("500.00"), 1L);

            assertThat(remaining).isEqualByComparingTo("500.00"); // nothing applied
            verify(creditNoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("createCarryForwardIfOverflow creates a new credit note for overflow")
        void carryForward_createsNewNote() {
            CreditNote saved = credit(10L, "200.00", "0.00");
            when(creditNoteRepository.save(any())).thenReturn(saved);

            CreditNote result = service.createCarryForwardIfOverflow(
                    10L, null, "INR", new BigDecimal("200.00"), 1L);

            assertThat(result).isNotNull();
            verify(creditNoteRepository).save(argThat(cn ->
                    cn.getReason().contains("Carry-forward")));
        }

        @Test
        @DisplayName("createCarryForwardIfOverflow returns null for zero overflow")
        void carryForward_zeroOverflow_returnsNull() {
            CreditNote result = service.createCarryForwardIfOverflow(
                    10L, null, "INR", BigDecimal.ZERO, 1L);

            assertThat(result).isNull();
            verify(creditNoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("createCreditNote rejects zero amount")
        void create_zeroAmount_throws() {
            assertThatThrownBy(() ->
                    service.createCreditNote(10L, null, "INR", BigDecimal.ZERO,
                            "test", null, null))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("createCreditNote persists and returns saved credit note")
        void create_valid_savedAndReturned() {
            CreditNote saved = credit(5L, "500.00", "0.00");
            when(creditNoteRepository.save(any())).thenReturn(saved);

            CreditNote result = service.createCreditNote(
                    10L, null, "INR", new BigDecimal("500.00"),
                    "test refund", 1L, null);

            assertThat(result).isNotNull();
            assertThat(result.getAmount()).isEqualByComparingTo("500.00");
        }
    }
}
