package com.firstclub.billing.service;

import com.firstclub.billing.dto.ApplyDiscountRequestDTO;
import com.firstclub.billing.dto.DiscountCreateRequestDTO;
import com.firstclub.billing.dto.DiscountResponseDTO;
import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.*;
import com.firstclub.billing.service.impl.DiscountServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DiscountService — createDiscount / applyDiscountToInvoice")
class DiscountServiceTest {

    @Mock private DiscountRepository discountRepository;
    @Mock private DiscountRedemptionRepository redemptionRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceLineRepository invoiceLineRepository;
    @Mock private InvoiceTotalService invoiceTotalService;

    private DiscountService service;

    @BeforeEach
    void setUp() {
        service = new DiscountServiceImpl(
                discountRepository, redemptionRepository,
                invoiceRepository, invoiceLineRepository,
                invoiceTotalService);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DiscountCreateRequestDTO validFixedRequest() {
        return DiscountCreateRequestDTO.builder()
                .code("SAVE50").discountType(DiscountType.FIXED)
                .value(new BigDecimal("50.00")).currency("INR")
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();
    }

    private Discount activeFixedDiscount(Long id, long merchantId) {
        return Discount.builder()
                .id(id).merchantId(merchantId)
                .code("SAVE50").discountType(DiscountType.FIXED)
                .value(new BigDecimal("50.00")).currency("INR")
                .status(DiscountStatus.ACTIVE)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();
    }

    private Invoice openInvoice(Long invoiceId, Long merchantId, String subtotal) {
        return Invoice.builder()
                .id(invoiceId).merchantId(merchantId).userId(1L)
                .status(InvoiceStatus.OPEN)
                .currency("INR")
                .totalAmount(new BigDecimal(subtotal))
                .subtotal(new BigDecimal(subtotal))
                .discountTotal(BigDecimal.ZERO).creditTotal(BigDecimal.ZERO)
                .taxTotal(BigDecimal.ZERO).grandTotal(new BigDecimal(subtotal))
                .dueDate(LocalDateTime.now().plusDays(7))
                .build();
    }

    // ── Create tests ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("createDiscount: saves and returns DTO with ACTIVE status")
    void createDiscount_validRequest_returnsDtoWithActiveStatus() {
        when(discountRepository.existsByMerchantIdAndCodeIgnoreCase(1L, "SAVE50")).thenReturn(false);
        when(discountRepository.save(any())).thenAnswer(inv -> {
            Discount d = inv.getArgument(0);
            d = Discount.builder().id(10L).merchantId(d.getMerchantId())
                    .code(d.getCode()).discountType(d.getDiscountType())
                    .value(d.getValue()).currency(d.getCurrency())
                    .status(d.getStatus()).validFrom(d.getValidFrom()).validTo(d.getValidTo())
                    .build();
            return d;
        });

        DiscountResponseDTO result = service.createDiscount(1L, validFixedRequest());

        assertThat(result.getStatus()).isEqualTo(DiscountStatus.ACTIVE);
        assertThat(result.getCode()).isEqualTo("SAVE50");
    }

    @Test
    @DisplayName("createDiscount: duplicate code throws MembershipException")
    void createDiscount_duplicateCode_throws() {
        when(discountRepository.existsByMerchantIdAndCodeIgnoreCase(1L, "SAVE50")).thenReturn(true);

        assertThatThrownBy(() -> service.createDiscount(1L, validFixedRequest()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createDiscount: PERCENTAGE > 100 throws MembershipException")
    void createDiscount_percentageOver100_throws() {
        DiscountCreateRequestDTO req = DiscountCreateRequestDTO.builder()
                .code("BIG").discountType(DiscountType.PERCENTAGE)
                .value(new BigDecimal("110")).currency(null)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();

        assertThatThrownBy(() -> service.createDiscount(1L, req))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("createDiscount: validFrom after validTo throws MembershipException")
    void createDiscount_validFromAfterValidTo_throws() {
        DiscountCreateRequestDTO req = DiscountCreateRequestDTO.builder()
                .code("BADWINDOW")
                .discountType(DiscountType.FIXED)
                .value(new BigDecimal("50.00"))
                .currency("INR")
                .validFrom(LocalDateTime.now().plusDays(10))
                .validTo(LocalDateTime.now().plusDays(5))
                .build();

        assertThatThrownBy(() -> service.createDiscount(1L, req))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("validFrom");
    }

    // ── Apply tests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("applyDiscount FIXED: creates DISCOUNT line, records redemption, updates invoice")
    void applyDiscount_fixed_createsLineAndRedemption() {
        Discount discount = activeFixedDiscount(10L, 1L);
        Invoice invoice = openInvoice(100L, 1L, "1000.00");

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));
        when(invoiceRepository.findByIdAndMerchantId(100L, 1L)).thenReturn(Optional.of(invoice));
        when(redemptionRepository.existsByDiscountIdAndInvoiceId(10L, 100L)).thenReturn(false);
        when(invoiceLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(redemptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceLineRepository.findByInvoiceId(100L)).thenReturn(List.of());

        InvoiceSummaryDTO result = service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build());

        // Verify DISCOUNT line saved with negative amount
        ArgumentCaptor<InvoiceLine> lineCaptor = ArgumentCaptor.forClass(InvoiceLine.class);
        verify(invoiceLineRepository).save(lineCaptor.capture());
        assertThat(lineCaptor.getValue().getLineType()).isEqualTo(InvoiceLineType.DISCOUNT);
        assertThat(lineCaptor.getValue().getAmount()).isEqualByComparingTo("-50.00");

        // Verify redemption saved
        verify(redemptionRepository).save(any(DiscountRedemption.class));

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("applyDiscount PERCENTAGE: discount amount = subtotal * (value/100)")
    void applyDiscount_percentage_amountComputedCorrectly() {
        Discount discount = Discount.builder()
                .id(20L).merchantId(1L).code("SAVE10")
                .discountType(DiscountType.PERCENTAGE).value(new BigDecimal("10"))
                .status(DiscountStatus.ACTIVE)
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();
        Invoice invoice = openInvoice(200L, 1L, "1000.00");

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE10"))
                .thenReturn(Optional.of(discount));
        when(invoiceRepository.findByIdAndMerchantId(200L, 1L)).thenReturn(Optional.of(invoice));
        when(redemptionRepository.existsByDiscountIdAndInvoiceId(20L, 200L)).thenReturn(false);
        when(invoiceLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(redemptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceLineRepository.findByInvoiceId(200L)).thenReturn(List.of());

        service.applyDiscountToInvoice(1L, 200L,
                ApplyDiscountRequestDTO.builder().code("SAVE10").customerId(5L).build());

        ArgumentCaptor<InvoiceLine> lineCaptor = ArgumentCaptor.forClass(InvoiceLine.class);
        verify(invoiceLineRepository).save(lineCaptor.capture());
        // 10% of 1000 = 100
        assertThat(lineCaptor.getValue().getAmount()).isEqualByComparingTo("-100.0000");
    }

    @Test
    @DisplayName("applyDiscount PAID invoice: throws MembershipException")
    void applyDiscount_paidInvoice_throws() {
        Discount discount = activeFixedDiscount(10L, 1L);
        Invoice paidInvoice = openInvoice(100L, 1L, "1000.00");
        paidInvoice.setStatus(InvoiceStatus.PAID);

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));
        when(invoiceRepository.findByIdAndMerchantId(100L, 1L)).thenReturn(Optional.of(paidInvoice));

        assertThatThrownBy(() -> service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    @DisplayName("applyDiscount duplicate: discount already on invoice throws")
    void applyDiscount_alreadyApplied_throws() {
        Discount discount = activeFixedDiscount(10L, 1L);
        Invoice invoice = openInvoice(100L, 1L, "1000.00");

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));
        when(invoiceRepository.findByIdAndMerchantId(100L, 1L)).thenReturn(Optional.of(invoice));
        when(redemptionRepository.existsByDiscountIdAndInvoiceId(10L, 100L)).thenReturn(true);

        assertThatThrownBy(() -> service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("already applied");
    }

    @Test
    @DisplayName("applyDiscount maxRedemptions exhausted: throws")
    void applyDiscount_maxRedemptionsExhausted_throws() {
        Discount discount = activeFixedDiscount(10L, 1L);
        discount.setMaxRedemptions(5);

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));
        when(redemptionRepository.countByDiscountId(10L)).thenReturn(5L);

        assertThatThrownBy(() -> service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("maximum redemptions");
    }

    @Test
    @DisplayName("applyDiscount INACTIVE discount: throws")
    void applyDiscount_inactiveDiscount_throws() {
        Discount discount = activeFixedDiscount(10L, 1L);
        discount.setStatus(DiscountStatus.INACTIVE);

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));

        assertThatThrownBy(() -> service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("applyDiscount expired discount: throws")
    void applyDiscount_expiredDiscount_throws() {
        Discount discount = activeFixedDiscount(10L, 1L);
        discount.setValidTo(LocalDateTime.now().minusDays(1)); // expired

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));

        assertThatThrownBy(() -> service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build()))
                .isInstanceOf(MembershipException.class)
                .hasMessageContaining("validity window");
    }

    @Test
    @DisplayName("applyDiscount FIXED capped at subtotal when value > subtotal")
    void applyDiscount_fixedCappedAtSubtotal() {
        Discount discount = activeFixedDiscount(10L, 1L);
        discount.setValue(new BigDecimal("2000.00")); // larger than subtotal
        Invoice invoice = openInvoice(100L, 1L, "500.00"); // subtotal = 500

        when(discountRepository.findByMerchantIdAndCodeIgnoreCase(1L, "SAVE50"))
                .thenReturn(Optional.of(discount));
        when(invoiceRepository.findByIdAndMerchantId(100L, 1L)).thenReturn(Optional.of(invoice));
        when(redemptionRepository.existsByDiscountIdAndInvoiceId(10L, 100L)).thenReturn(false);
        when(invoiceLineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(redemptionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceTotalService.recomputeTotals(any())).thenAnswer(i -> i.getArgument(0));
        when(invoiceLineRepository.findByInvoiceId(100L)).thenReturn(List.of());

        service.applyDiscountToInvoice(1L, 100L,
                ApplyDiscountRequestDTO.builder().code("SAVE50").customerId(5L).build());

        ArgumentCaptor<InvoiceLine> lineCaptor = ArgumentCaptor.forClass(InvoiceLine.class);
        verify(invoiceLineRepository).save(lineCaptor.capture());
        // Capped at subtotal = 500
        assertThat(lineCaptor.getValue().getAmount()).isEqualByComparingTo("-500.00");
    }
}
