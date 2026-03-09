package com.firstclub.platform.repair;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.platform.repair.actions.InvoiceRecomputeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for billing-domain repair actions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Billing Repair Actions — Unit Tests")
class BillingRepairActionTest {

    // ── InvoiceRecomputeAction ────────────────────────────────────────────────

    @Nested
    @DisplayName("InvoiceRecomputeAction")
    class InvoiceRecomputeTests {

        @Mock private InvoiceRepository  invoiceRepository;
        @Mock private InvoiceTotalService invoiceTotalService;
        @Mock private ObjectMapper       objectMapper;
        @InjectMocks private InvoiceRecomputeAction action;

        @Test
        @DisplayName("repair key, target type, dry-run support")
        void metadata() {
            assertThat(action.getRepairKey()).isEqualTo("repair.invoice.recompute_totals");
            assertThat(action.getTargetType()).isEqualTo("INVOICE");
            assertThat(action.supportsDryRun()).isTrue();
        }

        @Test
        @DisplayName("EXECUTE — saves updated invoice and returns success result")
        void execute_savesInvoice() throws Exception {
            Invoice original = invoice(42L, new BigDecimal("100.00"));
            Invoice updated  = invoice(42L, new BigDecimal("120.00"));
            when(invoiceRepository.findById(42L)).thenReturn(Optional.of(original));
            when(invoiceTotalService.recomputeTotals(original)).thenReturn(updated);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "42", Map.of(), false, 1L, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isDryRun()).isFalse();
            verify(invoiceRepository).save(updated);
        }

        @Test
        @DisplayName("DRY-RUN — does not save, computes new totals")
        void execute_dryRun_doesNotSave() throws Exception {
            Invoice original = invoice(10L, new BigDecimal("200.00"));
            Invoice computed = invoice(10L, new BigDecimal("210.00"));
            when(invoiceRepository.findById(10L)).thenReturn(Optional.of(original));
            when(invoiceTotalService.recomputeTotals(original)).thenReturn(computed);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "10", Map.of(), true, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.isDryRun()).isTrue();
            verify(invoiceRepository, never()).save(any());
        }

        @Test
        @DisplayName("FAIL — throws when invoice not found")
        void execute_throwsWhenNotFound() {
            when(invoiceRepository.findById(99L)).thenReturn(Optional.empty());

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "99", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invoice not found");
        }

        @Test
        @DisplayName("FAIL — throws on non-numeric targetId")
        void execute_throwsOnBadId() {
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "not-a-number", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static Invoice invoice(Long id, BigDecimal grandTotal) {
        return Invoice.builder()
                .id(id)
                .status(InvoiceStatus.OPEN)
                .grandTotal(grandTotal)
                .subtotal(grandTotal)
                .discountTotal(BigDecimal.ZERO)
                .creditTotal(BigDecimal.ZERO)
                .taxTotal(BigDecimal.ZERO)
                .build();
    }
}
