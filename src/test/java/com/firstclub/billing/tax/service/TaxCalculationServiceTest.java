package com.firstclub.billing.tax.service;

import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.billing.tax.dto.InvoiceTaxBreakdownDTO;
import com.firstclub.billing.tax.entity.CustomerEntityType;
import com.firstclub.billing.tax.entity.CustomerTaxProfile;
import com.firstclub.billing.tax.entity.TaxMode;
import com.firstclub.billing.tax.entity.TaxProfile;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.billing.tax.service.impl.TaxCalculationServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxCalculationServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceLineRepository invoiceLineRepository;
    @Mock private TaxProfileRepository taxProfileRepository;
    @Mock private CustomerTaxProfileRepository customerTaxProfileRepository;
    @Mock private InvoiceTotalService invoiceTotalService;

    @InjectMocks
    private TaxCalculationServiceImpl service;

    private static final Long MERCHANT_ID  = 1L;
    private static final Long INVOICE_ID   = 100L;
    private static final Long CUSTOMER_ID  = 200L;

    private Invoice openInvoice;
    private TaxProfile merchantProfile;
    private CustomerTaxProfile customerProfile;
    private InvoiceLine planLine;

    @BeforeEach
    void setUp() {
        openInvoice = Invoice.builder()
                .id(INVOICE_ID).merchantId(MERCHANT_ID).userId(9L)
                .status(InvoiceStatus.OPEN).currency("INR")
                .totalAmount(BigDecimal.ZERO)
                .subtotal(BigDecimal.ZERO).discountTotal(BigDecimal.ZERO)
                .creditTotal(BigDecimal.ZERO).taxTotal(BigDecimal.ZERO)
                .grandTotal(BigDecimal.ZERO)
                .dueDate(LocalDateTime.now().plusDays(7))
                .build();

        merchantProfile = TaxProfile.builder()
                .id(1L).merchantId(MERCHANT_ID)
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("First Club Pvt Ltd")
                .taxMode(TaxMode.B2B)
                .build();

        customerProfile = CustomerTaxProfile.builder()
                .id(1L).customerId(CUSTOMER_ID)
                .stateCode("MH").entityType(CustomerEntityType.BUSINESS)
                .taxExempt(false)
                .build();

        planLine = InvoiceLine.builder()
                .id(10L).invoiceId(INVOICE_ID)
                .lineType(InvoiceLineType.PLAN_CHARGE)
                .description("Plan charge").amount(new BigDecimal("1000.00"))
                .build();
    }

    // ── getTaxBreakdown — intra-state ─────────────────────────────────────────

    @Test
    @DisplayName("getTaxBreakdown: intra-state → CGST 9% + SGST 9%")
    void taxBreakdown_intraState_cgstAndSgst() {
        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(planLine));

        InvoiceTaxBreakdownDTO dto = service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        assertThat(dto.getCgst()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(dto.getSgst()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(dto.getIgst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getTaxTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(dto.isIntraState()).isTrue();
        assertThat(dto.isTaxExempt()).isFalse();
        assertThat(dto.getTaxLines()).hasSize(2);
    }

    // ── getTaxBreakdown — inter-state ─────────────────────────────────────────

    @Test
    @DisplayName("getTaxBreakdown: inter-state → IGST 18%")
    void taxBreakdown_interState_igst() {
        customerProfile.setStateCode("KA");  // different from merchant MH

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(planLine));

        InvoiceTaxBreakdownDTO dto = service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        assertThat(dto.getCgst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getSgst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.getIgst()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(dto.getTaxTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(dto.isIntraState()).isFalse();
        assertThat(dto.getTaxLines()).hasSize(1);
    }

    // ── getTaxBreakdown — tax exempt ──────────────────────────────────────────

    @Test
    @DisplayName("getTaxBreakdown: tax-exempt customer → zero tax, no lines")
    void taxBreakdown_exemptCustomer_noTaxLines() {
        customerProfile.setTaxExempt(true);

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(planLine));

        InvoiceTaxBreakdownDTO dto = service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        assertThat(dto.getTaxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.isTaxExempt()).isTrue();
        assertThat(dto.getTaxLines()).isEmpty();
    }

    // ── getTaxBreakdown — discount reduces taxable base ───────────────────────

    @Test
    @DisplayName("getTaxBreakdown: discount reduces taxable base before GST")
    void taxBreakdown_withDiscount_reducedBase() {
        InvoiceLine discountLine = InvoiceLine.builder()
                .id(11L).invoiceId(INVOICE_ID)
                .lineType(InvoiceLineType.DISCOUNT)
                .description("100 off").amount(new BigDecimal("-100.00"))
                .build();

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(planLine, discountLine));

        InvoiceTaxBreakdownDTO dto = service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        // taxable base = 1000 - 100 = 900; CGST = 9% * 900 = 81
        assertThat(dto.getTaxableBase()).isEqualByComparingTo(new BigDecimal("900.00"));
        assertThat(dto.getCgst()).isEqualByComparingTo(new BigDecimal("81.00"));
        assertThat(dto.getSgst()).isEqualByComparingTo(new BigDecimal("81.00"));
    }

    // ── recalculateTax — intra-state ──────────────────────────────────────────

    @Test
    @DisplayName("recalculateTax: OPEN invoice intra-state → CGST + SGST saved")
    void recalculateTax_intraState_savesCgstSgst() {
        Invoice updatedInvoice = Invoice.builder()
                .id(INVOICE_ID).merchantId(MERCHANT_ID).userId(9L)
                .status(InvoiceStatus.OPEN).currency("INR")
                .totalAmount(new BigDecimal("1180.00"))
                .subtotal(new BigDecimal("1000.00")).discountTotal(BigDecimal.ZERO)
                .creditTotal(BigDecimal.ZERO).taxTotal(new BigDecimal("180.00"))
                .grandTotal(new BigDecimal("1180.00"))
                .build();

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(planLine));
        when(invoiceTotalService.recomputeTotals(openInvoice)).thenReturn(updatedInvoice);

        InvoiceSummaryDTO result = service.recalculateTax(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        verify(invoiceLineRepository).deleteByInvoiceIdAndLineTypeIn(eq(INVOICE_ID),
                eq(EnumSet.of(InvoiceLineType.CGST, InvoiceLineType.SGST, InvoiceLineType.IGST)));
        verify(invoiceLineRepository, times(2)).save(any(InvoiceLine.class)); // CGST + SGST
        verify(invoiceTotalService).recomputeTotals(openInvoice);

        assertThat(result.getTaxTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(result.getGrandTotal()).isEqualByComparingTo(new BigDecimal("1180.00"));
    }

    // ── recalculateTax — terminal invoice blocked ─────────────────────────────

    @Test
    @DisplayName("recalculateTax: PAID invoice → MembershipException thrown")
    void recalculateTax_paidInvoice_throwsException() {
        openInvoice.setStatus(InvoiceStatus.PAID);

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));

        assertThatThrownBy(() -> service.recalculateTax(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("PAID");

        verify(invoiceLineRepository, never()).deleteByInvoiceIdAndLineTypeIn(any(), any());
    }

    // ── missing merchant tax profile ──────────────────────────────────────────

    @Test
    @DisplayName("getTaxBreakdown: missing merchant profile → MembershipException")
    void taxBreakdown_noMerchantProfile_throws() {
        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("merchant");
    }

    // ── rounding: HALF_UP ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getTaxBreakdown: rounding HALF_UP applied on fractional taxable base")
    void taxBreakdown_fractionalBase_halfUpRounding() {
        InvoiceLine oddLine = InvoiceLine.builder()
                .id(12L).invoiceId(INVOICE_ID)
                .lineType(InvoiceLineType.PLAN_CHARGE)
                .description("Plan").amount(new BigDecimal("333.33"))
                .build();

        when(invoiceRepository.findByIdAndMerchantId(INVOICE_ID, MERCHANT_ID))
                .thenReturn(Optional.of(openInvoice));
        when(taxProfileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(merchantProfile));
        when(customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID))
                .thenReturn(Optional.of(customerProfile));
        when(invoiceLineRepository.findByInvoiceId(INVOICE_ID))
                .thenReturn(List.of(oddLine));

        InvoiceTaxBreakdownDTO dto = service.getTaxBreakdown(MERCHANT_ID, INVOICE_ID, CUSTOMER_ID);

        // 9% of 333.33 = 29.9997  → HALF_UP → 30.00
        assertThat(dto.getCgst()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(dto.getSgst()).isEqualByComparingTo(new BigDecimal("30.00"));
    }
}
