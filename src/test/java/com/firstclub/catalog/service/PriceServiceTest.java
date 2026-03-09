package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.entity.*;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.PriceMapper;
import com.firstclub.catalog.mapper.PriceVersionMapper;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.catalog.service.impl.PriceServiceImpl;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceServiceImpl Unit Tests")
class PriceServiceTest {

    @Mock private PriceRepository priceRepository;
    @Mock private PriceVersionRepository priceVersionRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private PriceMapper priceMapper;
    @Mock private PriceVersionMapper priceVersionMapper;
    @InjectMocks private PriceServiceImpl priceService;

    private MerchantAccount merchant;
    private Product product;
    private Price activePrice;
    private PriceResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(1L).merchantCode("M1").legalName("Merchant One")
                .status(MerchantStatus.ACTIVE).build();

        product = Product.builder()
                .id(5L).merchant(merchant).productCode("GOLD_PLAN")
                .name("Gold Plan").status(ProductStatus.ACTIVE).build();

        activePrice = Price.builder()
                .id(20L).merchant(merchant).product(product)
                .priceCode("GOLD_MONTHLY").billingType(BillingType.RECURRING)
                .currency("INR").amount(new BigDecimal("499.0000"))
                .billingIntervalUnit(BillingIntervalUnit.MONTH).billingIntervalCount(1)
                .trialDays(0).active(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        responseDTO = PriceResponseDTO.builder()
                .id(20L).merchantId(1L).productId(5L).priceCode("GOLD_MONTHLY")
                .billingType(BillingType.RECURRING).currency("INR")
                .amount(new BigDecimal("499.0000")).active(true).build();
    }

    // ── CreatePriceTests ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("createPrice")
    class CreatePriceTests {

        @Test
        @DisplayName("success — RECURRING with valid interval")
        void success() {
            PriceCreateRequestDTO req = PriceCreateRequestDTO.builder()
                    .productId(5L).priceCode("GOLD_MONTHLY")
                    .billingType(BillingType.RECURRING).currency("INR")
                    .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                    .billingIntervalCount(1).trialDays(0).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(priceRepository.existsByMerchantIdAndPriceCode(1L, "GOLD_MONTHLY")).thenReturn(false);
            when(productRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(product));
            when(priceMapper.toEntity(req)).thenReturn(activePrice);
            when(priceRepository.save(any())).thenReturn(activePrice);
            when(priceMapper.toResponseDTO(activePrice)).thenReturn(responseDTO);

            PriceResponseDTO result = priceService.createPrice(1L, req);
            assertThat(result.getPriceCode()).isEqualTo("GOLD_MONTHLY");
            verify(priceRepository).save(any());
        }

        @Test
        @DisplayName("duplicate priceCode within merchant → 409")
        void duplicateCode() {
            PriceCreateRequestDTO req = PriceCreateRequestDTO.builder()
                    .productId(5L).priceCode("GOLD_MONTHLY")
                    .billingType(BillingType.RECURRING).currency("INR")
                    .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                    .billingIntervalCount(1).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(priceRepository.existsByMerchantIdAndPriceCode(1L, "GOLD_MONTHLY")).thenReturn(true);

            assertThatThrownBy(() -> priceService.createPrice(1L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("DUPLICATE_PRICE_CODE");
        }

        @Test
        @DisplayName("RECURRING without billingIntervalUnit → 400 INVALID_BILLING_INTERVAL")
        void recurringWithoutInterval() {
            PriceCreateRequestDTO req = PriceCreateRequestDTO.builder()
                    .productId(5L).priceCode("BAD_PRICE")
                    .billingType(BillingType.RECURRING).currency("INR")
                    .amount(new BigDecimal("499"))
                    // billingIntervalUnit intentionally omitted
                    .billingIntervalCount(1).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(priceRepository.existsByMerchantIdAndPriceCode(1L, "BAD_PRICE")).thenReturn(false);

            assertThatThrownBy(() -> priceService.createPrice(1L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("INVALID_BILLING_INTERVAL");
        }

        @Test
        @DisplayName("RECURRING with billingIntervalCount = 0 → 400")
        void recurringZeroIntervalCount() {
            PriceCreateRequestDTO req = PriceCreateRequestDTO.builder()
                    .productId(5L).priceCode("BAD_PRICE")
                    .billingType(BillingType.RECURRING).currency("INR")
                    .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                    .billingIntervalCount(0).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(priceRepository.existsByMerchantIdAndPriceCode(1L, "BAD_PRICE")).thenReturn(false);

            assertThatThrownBy(() -> priceService.createPrice(1L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("INVALID_BILLING_INTERVAL");
        }

        @Test
        @DisplayName("product belonging to different merchant → 404")
        void productNotBelongingToMerchant() {
            PriceCreateRequestDTO req = PriceCreateRequestDTO.builder()
                    .productId(5L).priceCode("X_PRICE")
                    .billingType(BillingType.ONE_TIME).currency("INR")
                    .amount(new BigDecimal("99")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                    .billingIntervalCount(1).build();

            when(merchantAccountRepository.findById(2L)).thenReturn(Optional.of(
                    MerchantAccount.builder().id(2L).merchantCode("M2").legalName("M2")
                            .status(MerchantStatus.ACTIVE).build()));
            when(priceRepository.existsByMerchantIdAndPriceCode(2L, "X_PRICE")).thenReturn(false);
            when(productRepository.findByMerchantIdAndId(2L, 5L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> priceService.createPrice(2L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRODUCT_NOT_FOUND");
        }
    }

    // ── DeactivatePriceTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("deactivatePrice")
    class DeactivatePriceTests {

        @Test
        @DisplayName("active → inactive")
        void deactivatesActivePrice() {
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(activePrice));
            when(priceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(priceMapper.toResponseDTO(any())).thenReturn(
                    PriceResponseDTO.builder().active(false).build());

            PriceResponseDTO result = priceService.deactivatePrice(1L, 20L);
            assertThat(result.isActive()).isFalse();
        }

        @Test
        @DisplayName("already inactive → idempotent, no extra save")
        void idempotentDeactivate() {
            activePrice.setActive(false);
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(activePrice));
            when(priceMapper.toResponseDTO(activePrice)).thenReturn(responseDTO);

            priceService.deactivatePrice(1L, 20L);
            verify(priceRepository, never()).save(any());
        }
    }

    // ── TenantIsolationTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("tenantIsolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("cross-merchant price read returns 404")
        void crossMerchantRead() {
            when(priceRepository.findByMerchantIdAndId(2L, 20L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> priceService.getPriceById(2L, 20L))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRICE_NOT_FOUND");
        }
    }

    // ── ListPricesTests ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listPrices")
    class ListPricesTests {

        @Test
        @DisplayName("no filter — returns all prices for merchant")
        void noFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Price> page = new PageImpl<>(List.of(activePrice));
            when(priceRepository.findAllByMerchantId(1L, pageable)).thenReturn(page);
            when(priceMapper.toResponseDTO(activePrice)).thenReturn(responseDTO);

            Page<PriceResponseDTO> result = priceService.listPrices(1L, null, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("active=true filter")
        void activeFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Price> page = new PageImpl<>(List.of(activePrice));
            when(priceRepository.findAllByMerchantIdAndActive(1L, true, pageable)).thenReturn(page);
            when(priceMapper.toResponseDTO(activePrice)).thenReturn(responseDTO);

            Page<PriceResponseDTO> result = priceService.listPrices(1L, true, pageable);
            assertThat(result.getContent()).hasSize(1);
        }
    }
}
