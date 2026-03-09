package com.firstclub.billing.service;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.impl.InvoiceTotalServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceTotalService — recomputeTotals()")
class InvoiceTotalServiceTest {

    @Mock
    private InvoiceLineRepository invoiceLineRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    private InvoiceTotalService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceTotalServiceImpl(invoiceLineRepository, invoiceRepository);
    }

    private Invoice blankInvoice() {
        return Invoice.builder()
                .id(1L).userId(10L).status(InvoiceStatus.OPEN)
                .currency("INR").totalAmount(BigDecimal.ZERO)
                .dueDate(LocalDateTime.now().plusDays(7))
                .subtotal(BigDecimal.ZERO).discountTotal(BigDecimal.ZERO)
                .creditTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO)
                .grandTotal(BigDecimal.ZERO)
                .build();
    }

    private InvoiceLine line(InvoiceLineType type, String amount) {
        return InvoiceLine.builder()
                .id(1L).invoiceId(1L).lineType(type)
                .description(type.name()).amount(new BigDecimal(amount))
                .build();
    }

    // ── Test 1: only PLAN_CHARGE ──────────────────────────────────────────────

    @Test
    @DisplayName("plan charge only: subtotal = line amount, grandTotal = subtotal")
    void planChargeOnly_subtotalEqualsGrandTotal() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L))
                .thenReturn(List.of(line(InvoiceLineType.PLAN_CHARGE, "1200.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        assertThat(result.getSubtotal()).isEqualByComparingTo("1200.00");
        assertThat(result.getDiscountTotal()).isEqualByComparingTo("0");
        assertThat(result.getCreditTotal()).isEqualByComparingTo("0");
        assertThat(result.getTaxTotal()).isEqualByComparingTo("0");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("1200.00");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("1200.00");
    }

    // ── Test 2: PLAN_CHARGE + TAX ─────────────────────────────────────────────

    @Test
    @DisplayName("plan charge + tax: grandTotal = subtotal + taxTotal")
    void planChargeAndTax_grandTotalIncludesTax() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                line(InvoiceLineType.PLAN_CHARGE, "1000.00"),
                line(InvoiceLineType.TAX, "180.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        assertThat(result.getSubtotal()).isEqualByComparingTo("1000.00");
        assertThat(result.getTaxTotal()).isEqualByComparingTo("180.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("1180.00");
    }

    // ── Test 3: PLAN_CHARGE + DISCOUNT ───────────────────────────────────────

    @Test
    @DisplayName("plan charge + discount line: discountTotal is abs(negative line)")
    void planChargeAndDiscount_grandTotalReducedByDiscount() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                line(InvoiceLineType.PLAN_CHARGE, "1200.00"),
                line(InvoiceLineType.DISCOUNT, "-200.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        assertThat(result.getSubtotal()).isEqualByComparingTo("1200.00");
        assertThat(result.getDiscountTotal()).isEqualByComparingTo("200.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("1000.00");
    }

    // ── Test 4: PLAN_CHARGE + CREDIT_APPLIED ─────────────────────────────────

    @Test
    @DisplayName("plan charge + credit applied: creditTotal is abs(negative credit line)")
    void planChargeAndCredit_grandTotalReducedByCredit() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                line(InvoiceLineType.PLAN_CHARGE, "1000.00"),
                line(InvoiceLineType.CREDIT_APPLIED, "-300.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        assertThat(result.getCreditTotal()).isEqualByComparingTo("300.00");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("700.00");
    }

    // ── Test 5: full breakdown ────────────────────────────────────────────────

    @Test
    @DisplayName("all line types: grandTotal = subtotal - discount - credit + tax")
    void allLineTypes_grandTotalFormulaCorrect() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                line(InvoiceLineType.PLAN_CHARGE, "1000.00"),
                line(InvoiceLineType.PRORATION,   "-100.00"),  // proration refund
                line(InvoiceLineType.TAX,          "180.00"),
                line(InvoiceLineType.DISCOUNT,    "-150.00"),
                line(InvoiceLineType.CREDIT_APPLIED, "-50.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        // subtotal = PLAN_CHARGE + PRORATION = 1000 + (-100) = 900
        assertThat(result.getSubtotal()).isEqualByComparingTo("900.00");
        assertThat(result.getTaxTotal()).isEqualByComparingTo("180.00");
        assertThat(result.getDiscountTotal()).isEqualByComparingTo("150.00");
        assertThat(result.getCreditTotal()).isEqualByComparingTo("50.00");
        // grandTotal = 900 - 150 - 50 + 180 = 880
        assertThat(result.getGrandTotal()).isEqualByComparingTo("880.00");
    }

    // ── Test 6: floor at zero ─────────────────────────────────────────────────

    @Test
    @DisplayName("over-credited invoice: grandTotal floored at zero")
    void overCredited_grandTotalIsZero() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of(
                line(InvoiceLineType.PLAN_CHARGE, "100.00"),
                line(InvoiceLineType.DISCOUNT,   "-80.00"),
                line(InvoiceLineType.CREDIT_APPLIED, "-50.00")));
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        // 100 - 80 - 50 = -30 → floored to 0
        assertThat(result.getGrandTotal()).isEqualByComparingTo("0");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("0");
    }

    // ── Test 7: empty lines ───────────────────────────────────────────────────

    @Test
    @DisplayName("no invoice lines: all totals are zero")
    void noLines_allTotalsZero() {
        Invoice inv = blankInvoice();
        when(invoiceLineRepository.findByInvoiceId(1L)).thenReturn(List.of());
        when(invoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Invoice result = service.recomputeTotals(inv);

        assertThat(result.getSubtotal()).isEqualByComparingTo("0");
        assertThat(result.getGrandTotal()).isEqualByComparingTo("0");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("0");
    }
}
