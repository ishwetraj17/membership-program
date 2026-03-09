package com.firstclub.billing;

import com.firstclub.billing.dto.ApplyDiscountRequestDTO;
import com.firstclub.billing.dto.DiscountCreateRequestDTO;
import com.firstclub.billing.dto.DiscountResponseDTO;
import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.DiscountRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.billing.controller.InvoiceDiscountController}.
 *
 * <p>Each test method creates its own isolated invoice and discount to prevent
 * inter-test dependencies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvoiceDiscountControllerTest extends PostgresIntegrationTestBase {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private DiscountRepository discountRepository;

    private static final long MERCHANT_ID = 2001L;
    private static final long USER_ID     = 9001L;
    private static final long CUSTOMER_ID = 8001L;

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice createOpenInvoice(String amount) {
        return invoiceRepository.save(Invoice.builder()
                .userId(USER_ID).merchantId(MERCHANT_ID)
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(new BigDecimal(amount))
                .subtotal(new BigDecimal(amount))
                .discountTotal(BigDecimal.ZERO).creditTotal(BigDecimal.ZERO)
                .taxTotal(BigDecimal.ZERO).grandTotal(new BigDecimal(amount))
                .dueDate(LocalDateTime.now().plusDays(7))
                .build());
    }

    private Discount createActiveFixedDiscount(String code, String value) {
        return discountRepository.save(Discount.builder()
                .merchantId(MERCHANT_ID).code(code)
                .discountType(DiscountType.FIXED)
                .value(new BigDecimal(value)).currency("INR")
                .status(DiscountStatus.ACTIVE)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build());
    }

    private String invoiceUrl(Long invoiceId) {
        return "/api/v2/merchants/" + MERCHANT_ID + "/invoices/" + invoiceId;
    }

    // ── Test 1: apply discount on OPEN invoice → 200 with updated summary ─────

    @Test
    @Order(1)
    @DisplayName("POST /apply-discount — FIXED discount on OPEN invoice returns updated summary")
    void applyDiscount_openInvoice_returns200WithSummary() {
        Invoice invoice = createOpenInvoice("1000.00");
        createActiveFixedDiscount("SAVE100", "100.00");

        ApplyDiscountRequestDTO req = ApplyDiscountRequestDTO.builder()
                .code("SAVE100").customerId(CUSTOMER_ID).build();

        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.postForEntity(
                invoiceUrl(invoice.getId()) + "/apply-discount", req, InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getDiscountTotal())
                .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(resp.getBody().getGrandTotal())
                .isEqualByComparingTo(new BigDecimal("900.00"));
    }

    // ── Test 2: apply discount on PAID invoice → error ───────────────────────

    @Test
    @Order(2)
    @DisplayName("POST /apply-discount — discount on PAID invoice returns error")
    void applyDiscount_paidInvoice_returnsError() {
        Invoice invoice = createOpenInvoice("500.00");
        invoice.setStatus(InvoiceStatus.PAID);
        invoiceRepository.save(invoice);

        createActiveFixedDiscount("PAID_TEST", "50.00");

        ApplyDiscountRequestDTO req = ApplyDiscountRequestDTO.builder()
                .code("PAID_TEST").customerId(CUSTOMER_ID).build();

        ResponseEntity<String> resp = restTemplate.postForEntity(
                invoiceUrl(invoice.getId()) + "/apply-discount", req, String.class);

        assertThat(resp.getStatusCode().is4xxClientError()
                || resp.getStatusCode().is5xxServerError()).isTrue();
    }

    // ── Test 3: applying same discount twice → error ──────────────────────────

    @Test
    @Order(3)
    @DisplayName("POST /apply-discount — same code applied twice returns error")
    void applyDiscount_twiceOnSameInvoice_returnsError() {
        Invoice invoice = createOpenInvoice("2000.00");
        createActiveFixedDiscount("ONCE_ONLY", "200.00");

        ApplyDiscountRequestDTO req = ApplyDiscountRequestDTO.builder()
                .code("ONCE_ONLY").customerId(CUSTOMER_ID).build();

        // First application — must succeed
        ResponseEntity<InvoiceSummaryDTO> first = restTemplate.postForEntity(
                invoiceUrl(invoice.getId()) + "/apply-discount", req, InvoiceSummaryDTO.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second application — must fail
        ResponseEntity<String> second = restTemplate.postForEntity(
                invoiceUrl(invoice.getId()) + "/apply-discount", req, String.class);
        assertThat(second.getStatusCode().is4xxClientError()
                || second.getStatusCode().is5xxServerError()).isTrue();
    }

    // ── Test 4: GET /summary returns full breakdown ───────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /summary — returns invoice with all breakdown fields")
    void getSummary_openInvoice_returnsFullBreakdown() {
        Invoice invoice = createOpenInvoice("1500.00");

        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.getForEntity(
                invoiceUrl(invoice.getId()) + "/summary", InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getId()).isEqualTo(invoice.getId());
        assertThat(resp.getBody().getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(resp.getBody().getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(resp.getBody().getSubtotal()).isNotNull();
        assertThat(resp.getBody().getGrandTotal()).isNotNull();
    }

    // ── Test 5: GET /summary for wrong merchant → error ──────────────────────

    @Test
    @Order(5)
    @DisplayName("GET /summary — invoice belonging to different merchant returns error")
    void getSummary_wrongMerchant_returnsError() {
        Invoice invoice = createOpenInvoice("800.00"); // belongs to MERCHANT_ID=2001

        // Try with a different merchantId
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v2/merchants/9999/invoices/" + invoice.getId() + "/summary",
                String.class);

        assertThat(resp.getStatusCode().is4xxClientError()
                || resp.getStatusCode().is5xxServerError()).isTrue();
    }

    // ── Test 6: PERCENTAGE discount applied correctly ─────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /apply-discount — PERCENTAGE 10% on 1000.00 gives grandTotal 900.00")
    void applyDiscount_percentage10pct_grandTotalCorrect() {
        Invoice invoice = createOpenInvoice("1000.00");
        discountRepository.save(Discount.builder()
                .merchantId(MERCHANT_ID).code("PCT10_TEST")
                .discountType(DiscountType.PERCENTAGE)
                .value(new BigDecimal("10"))
                .status(DiscountStatus.ACTIVE)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build());

        ApplyDiscountRequestDTO req = ApplyDiscountRequestDTO.builder()
                .code("PCT10_TEST").customerId(CUSTOMER_ID).build();

        ResponseEntity<InvoiceSummaryDTO> resp = restTemplate.postForEntity(
                invoiceUrl(invoice.getId()) + "/apply-discount", req, InvoiceSummaryDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getGrandTotal())
                .isEqualByComparingTo(new BigDecimal("900.0000"));
        assertThat(resp.getBody().getDiscountTotal())
                .isEqualByComparingTo(new BigDecimal("100.0000"));
    }
}
