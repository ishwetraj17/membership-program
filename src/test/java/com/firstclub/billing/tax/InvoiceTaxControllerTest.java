package com.firstclub.billing.tax;

import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.tax.dto.*;
import com.firstclub.billing.tax.entity.*;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvoiceTaxControllerTest extends PostgresIntegrationTestBase {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private InvoiceLineRepository invoiceLineRepository;
    @Autowired private TaxProfileRepository taxProfileRepository;
    @Autowired private CustomerTaxProfileRepository customerTaxProfileRepository;

    private static final long MERCHANT_ID  = 6001L;
    private static final long USER_ID      = 7001L;
    private static final long CUSTOMER_ID  = 8001L;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice createInvoice(String subtotal) {
        BigDecimal amount = new BigDecimal(subtotal);
        return invoiceRepository.save(Invoice.builder()
                .userId(USER_ID).merchantId(MERCHANT_ID)
                .status(InvoiceStatus.OPEN).currency("INR")
                .totalAmount(amount).subtotal(amount)
                .discountTotal(BigDecimal.ZERO).creditTotal(BigDecimal.ZERO)
                .taxTotal(BigDecimal.ZERO).grandTotal(amount)
                .dueDate(LocalDateTime.now().plusDays(7))
                .build());
    }

    private InvoiceLine addLine(Long invoiceId, InvoiceLineType type, String amount) {
        return invoiceLineRepository.save(InvoiceLine.builder()
                .invoiceId(invoiceId).lineType(type)
                .description(type.name()).amount(new BigDecimal(amount))
                .build());
    }

    private TaxProfile createMerchantProfile(String stateCode) {
        taxProfileRepository.findByMerchantId(MERCHANT_ID).ifPresent(taxProfileRepository::delete);
        return taxProfileRepository.save(TaxProfile.builder()
                .merchantId(MERCHANT_ID).gstin("27AAAAA0000A1Z5")
                .legalStateCode(stateCode).registeredBusinessName("Test Merchant")
                .taxMode(TaxMode.B2B).build());
    }

    private CustomerTaxProfile createCustomerProfile(String stateCode, boolean exempt) {
        customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID)
                .ifPresent(customerTaxProfileRepository::delete);
        return customerTaxProfileRepository.save(CustomerTaxProfile.builder()
                .customerId(CUSTOMER_ID).stateCode(stateCode)
                .entityType(CustomerEntityType.BUSINESS).taxExempt(exempt).build());
    }

    private String taxUrl(Long invoiceId, String suffix) {
        return "/api/v2/merchants/" + MERCHANT_ID + "/invoices/" + invoiceId + suffix;
    }

    // ── Test 1: GET /tax-breakdown intra-state ────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /tax-breakdown — intra-state invoice returns CGST + SGST breakdown")
    void taxBreakdown_intraState_returnsCgstSgst() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        ResponseEntity<InvoiceTaxBreakdownDTO> resp = restTemplate.getForEntity(
                taxUrl(inv.getId(), "/tax-breakdown?customerId=" + CUSTOMER_ID),
                InvoiceTaxBreakdownDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCgst()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(resp.getBody().getSgst()).isEqualByComparingTo(new BigDecimal("90.00"));
        assertThat(resp.getBody().getIgst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.getBody().getTaxTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(resp.getBody().isIntraState()).isTrue();
    }

    // ── Test 2: GET /tax-breakdown inter-state ────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("GET /tax-breakdown — inter-state invoice returns IGST breakdown")
    void taxBreakdown_interState_returnsIgst() {
        createMerchantProfile("MH");
        createCustomerProfile("KA", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        ResponseEntity<InvoiceTaxBreakdownDTO> resp = restTemplate.getForEntity(
                taxUrl(inv.getId(), "/tax-breakdown?customerId=" + CUSTOMER_ID),
                InvoiceTaxBreakdownDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getIgst()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(resp.getBody().getCgst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.getBody().isIntraState()).isFalse();
    }

    // ── Test 3: GET /tax-breakdown exempt customer ────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /tax-breakdown — tax-exempt customer returns zero tax")
    void taxBreakdown_exemptCustomer_zeroTax() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", true);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        ResponseEntity<InvoiceTaxBreakdownDTO> resp = restTemplate.getForEntity(
                taxUrl(inv.getId(), "/tax-breakdown?customerId=" + CUSTOMER_ID),
                InvoiceTaxBreakdownDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTaxTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resp.getBody().isTaxExempt()).isTrue();
        assertThat(resp.getBody().getTaxLines()).isEmpty();
    }

    // ── Test 4: POST /recalculate-tax intra-state ─────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /recalculate-tax — intra-state OPEN invoice → CGST+SGST lines saved")
    void recalculateTax_intraState_savesTaxLinesAndUpdatesTotals() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.postForEntity(
                taxUrl(inv.getId(), "/recalculate-tax"), req, InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTaxTotal()).isEqualByComparingTo(new BigDecimal("180.00"));
        assertThat(resp.getBody().getGrandTotal()).isEqualByComparingTo(new BigDecimal("1180.00"));

        long gstLines = invoiceLineRepository.findByInvoiceId(inv.getId()).stream()
                .filter(l -> l.getLineType() == InvoiceLineType.CGST
                          || l.getLineType() == InvoiceLineType.SGST)
                .count();
        assertThat(gstLines).isEqualTo(2);
    }

    // ── Test 5: POST /recalculate-tax inter-state ─────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /recalculate-tax — inter-state OPEN invoice → single IGST line")
    void recalculateTax_interState_savesIgstLine() {
        createMerchantProfile("MH");
        createCustomerProfile("KA", false);
        Invoice inv = createInvoice("500.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "500.00");

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.postForEntity(
                taxUrl(inv.getId(), "/recalculate-tax"), req, InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // IGST = 18% of 500 = 90
        assertThat(resp.getBody().getTaxTotal()).isEqualByComparingTo(new BigDecimal("90.00"));

        long igstLines = invoiceLineRepository.findByInvoiceId(inv.getId()).stream()
                .filter(l -> l.getLineType() == InvoiceLineType.IGST).count();
        assertThat(igstLines).isEqualTo(1);
    }

    // ── Test 6: POST /recalculate-tax strips old lines before recalc ──────────

    @Test
    @Order(6)
    @DisplayName("POST /recalculate-tax — old GST lines removed before recalculating")
    void recalculateTax_calledTwice_noLineDuplication() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        restTemplate.postForEntity(taxUrl(inv.getId(), "/recalculate-tax"), req, InvoiceSummaryDTO.class);
        restTemplate.postForEntity(taxUrl(inv.getId(), "/recalculate-tax"), req, InvoiceSummaryDTO.class);

        long gstLines = invoiceLineRepository.findByInvoiceId(inv.getId()).stream()
                .filter(l -> l.getLineType() == InvoiceLineType.CGST
                          || l.getLineType() == InvoiceLineType.SGST
                          || l.getLineType() == InvoiceLineType.IGST)
                .count();
        assertThat(gstLines).isEqualTo(2);  // exactly CGST + SGST, no duplicates
    }

    // ── Test 7: POST /recalculate-tax on PAID invoice → 422 ──────────────────

    @Test
    @Order(7)
    @DisplayName("POST /recalculate-tax — PAID invoice returns 4xx error")
    void recalculateTax_paidInvoice_returns4xx() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");
        inv.setStatus(InvoiceStatus.PAID);
        invoiceRepository.save(inv);

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        ResponseEntity<String> resp = restTemplate.postForEntity(
                taxUrl(inv.getId(), "/recalculate-tax"), req, String.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── Test 8: POST /recalculate-tax no merchant tax profile → 4xx ──────────

    @Test
    @Order(8)
    @DisplayName("POST /recalculate-tax — missing merchant profile returns 4xx")
    void recalculateTax_noMerchantProfile_returns4xx() {
        taxProfileRepository.findByMerchantId(MERCHANT_ID).ifPresent(taxProfileRepository::delete);
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        ResponseEntity<String> resp = restTemplate.postForEntity(
                taxUrl(inv.getId(), "/recalculate-tax"), req, String.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── Test 9: tax base reduced by discount ─────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("POST /recalculate-tax — discount reduces taxable base before GST")
    void recalculateTax_withDiscount_reducedTaxBase() {
        createMerchantProfile("MH");
        createCustomerProfile("MH", false);
        Invoice inv = createInvoice("1000.00");
        addLine(inv.getId(), InvoiceLineType.PLAN_CHARGE, "1000.00");
        addLine(inv.getId(), InvoiceLineType.DISCOUNT, "-100.00");

        RecalculateTaxRequestDTO req = RecalculateTaxRequestDTO.builder()
                .customerId(CUSTOMER_ID).build();
        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.postForEntity(
                taxUrl(inv.getId(), "/recalculate-tax"), req, InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // taxable base = 1000 - 100 = 900; CGST+SGST = 162
        assertThat(resp.getBody().getTaxTotal()).isEqualByComparingTo(new BigDecimal("162.00"));
    }
}
