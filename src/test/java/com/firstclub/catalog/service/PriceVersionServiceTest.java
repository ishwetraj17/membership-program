package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.entity.*;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.PriceVersionMapper;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.service.impl.PriceVersionServiceImpl;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceVersionServiceImpl Unit Tests")
class PriceVersionServiceTest {

    @Mock private PriceRepository priceRepository;
    @Mock private PriceVersionRepository priceVersionRepository;
    @Mock private PriceVersionMapper priceVersionMapper;
    @InjectMocks private PriceVersionServiceImpl priceVersionService;

    private MerchantAccount merchant;
    private Product product;
    private Price price;
    private PriceVersion v1;
    private PriceVersionResponseDTO v1Response;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(1L).merchantCode("M1").legalName("Merchant One")
                .status(MerchantStatus.ACTIVE).build();

        product = Product.builder().id(5L).merchant(merchant)
                .productCode("GOLD").name("Gold").status(ProductStatus.ACTIVE).build();

        price = Price.builder()
                .id(20L).merchant(merchant).product(product)
                .priceCode("GOLD_M").billingType(BillingType.RECURRING)
                .currency("INR").amount(new BigDecimal("499")).active(true).build();

        v1 = PriceVersion.builder()
                .id(100L).price(price)
                .effectiveFrom(LocalDateTime.now().minusDays(30))
                .effectiveTo(null)
                .amount(new BigDecimal("499")).currency("INR")
                .grandfatherExistingSubscriptions(false)
                .createdAt(LocalDateTime.now().minusDays(30))
                .build();

        v1Response = PriceVersionResponseDTO.builder()
                .id(100L).priceId(20L)
                .effectiveFrom(v1.getEffectiveFrom())
                .amount(new BigDecimal("499")).currency("INR").build();
    }

    // ── CreatePriceVersionTests ────────────────────────────────────────────────

    @Nested
    @DisplayName("createPriceVersion")
    class CreatePriceVersionTests {

        @Test
        @DisplayName("creates first version (no previous open version)")
        void firstVersion() {
            LocalDateTime future = LocalDateTime.now().plusDays(1);
            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(future).amount(new BigDecimal("599")).currency("INR").build();

            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(eq(20L), any()))
                    .thenReturn(Optional.empty());
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveToIsNullOrderByEffectiveFromDesc(20L))
                    .thenReturn(Optional.empty());

            PriceVersion newVersion = PriceVersion.builder().id(101L).price(price)
                    .effectiveFrom(future).amount(new BigDecimal("599")).currency("INR").build();
            PriceVersionResponseDTO newResponse = PriceVersionResponseDTO.builder()
                    .id(101L).priceId(20L).effectiveFrom(future)
                    .amount(new BigDecimal("599")).currency("INR").build();

            when(priceVersionMapper.toEntity(req)).thenReturn(newVersion);
            when(priceVersionRepository.save(any())).thenReturn(newVersion);
            when(priceVersionMapper.toResponseDTO(newVersion)).thenReturn(newResponse);

            PriceVersionResponseDTO result = priceVersionService.createPriceVersion(1L, 20L, req);
            assertThat(result.getAmount()).isEqualByComparingTo("599");
            verify(priceVersionRepository, never()).save(argThat(v -> v.getId() != null && v.getId().equals(100L)));
        }

        @Test
        @DisplayName("creates version and closes previous open-ended version")
        void closesOpenEndedVersion() {
            LocalDateTime future = LocalDateTime.now().plusDays(1);
            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(future).amount(new BigDecimal("599")).currency("INR").build();

            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(eq(20L), any()))
                    .thenReturn(Optional.empty());
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveToIsNullOrderByEffectiveFromDesc(20L))
                    .thenReturn(Optional.of(v1));

            PriceVersion newVersion = PriceVersion.builder().id(101L).price(price)
                    .effectiveFrom(future).amount(new BigDecimal("599")).currency("INR").build();
            when(priceVersionMapper.toEntity(req)).thenReturn(newVersion);
            when(priceVersionRepository.save(any())).thenReturn(newVersion);
            when(priceVersionMapper.toResponseDTO(newVersion)).thenReturn(v1Response);

            priceVersionService.createPriceVersion(1L, 20L, req);

            // v1 should have been saved with effectiveTo set
            assertThat(v1.getEffectiveTo()).isEqualTo(future);
            verify(priceVersionRepository, atLeast(2)).save(any()); // once for v1, once for newVersion
        }

        @Test
        @DisplayName("effectiveFrom in the past → 400 EFFECTIVE_FROM_IN_PAST")
        void pastEffectiveFrom() {
            LocalDateTime past = LocalDateTime.now().minusDays(2);
            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(past).amount(new BigDecimal("599")).currency("INR").build();

            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));

            assertThatThrownBy(() -> priceVersionService.createPriceVersion(1L, 20L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("EFFECTIVE_FROM_IN_PAST");
        }

        @Test
        @DisplayName("overlapping version window → 409 OVERLAPPING_PRICE_VERSION")
        void overlappingVersion() {
            LocalDateTime future = LocalDateTime.now().plusDays(2);
            PriceVersionCreateRequestDTO req = PriceVersionCreateRequestDTO.builder()
                    .effectiveFrom(future).amount(new BigDecimal("599")).currency("INR").build();

            PriceVersion laterVersion = PriceVersion.builder().id(102L).price(price)
                    .effectiveFrom(LocalDateTime.now().plusDays(3)).build();

            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromGreaterThanOrderByEffectiveFromAsc(eq(20L), any()))
                    .thenReturn(Optional.of(laterVersion));

            assertThatThrownBy(() -> priceVersionService.createPriceVersion(1L, 20L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("OVERLAPPING_PRICE_VERSION");
        }
    }

    // ── ResolveEffectiveVersionTests ───────────────────────────────────────────

    @Nested
    @DisplayName("resolveEffectiveVersionForTimestamp")
    class ResolveEffectiveVersionTests {

        @Test
        @DisplayName("returns version effective at given timestamp")
        void returnsEffectiveVersion() {
            LocalDateTime queryTime = LocalDateTime.now();
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(20L, queryTime))
                    .thenReturn(Optional.of(v1));
            when(priceVersionMapper.toResponseDTO(v1)).thenReturn(v1Response);

            Optional<PriceVersionResponseDTO> result =
                    priceVersionService.resolveEffectiveVersionForTimestamp(20L, queryTime);

            assertThat(result).isPresent();
            assertThat(result.get().getAmount()).isEqualByComparingTo("499");
        }

        @Test
        @DisplayName("future version not yet effective → empty result")
        void futureVersionNotYetEffective() {
            // v1's effectiveFrom is 30 days in the future relative to our query time (yesterday)
            LocalDateTime queryTime = LocalDateTime.now().minusDays(60);
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(20L, queryTime))
                    .thenReturn(Optional.empty());

            Optional<PriceVersionResponseDTO> result =
                    priceVersionService.resolveEffectiveVersionForTimestamp(20L, queryTime);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("selects most recent version when multiple exist")
        void selectsMostRecentVersion() {
            PriceVersion v2 = PriceVersion.builder().id(101L).price(price)
                    .effectiveFrom(LocalDateTime.now().minusDays(5))
                    .effectiveTo(null).amount(new BigDecimal("599")).currency("INR").build();
            PriceVersionResponseDTO v2Response = PriceVersionResponseDTO.builder()
                    .id(101L).priceId(20L).amount(new BigDecimal("599")).currency("INR").build();

            LocalDateTime queryTime = LocalDateTime.now();
            when(priceVersionRepository
                    .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(20L, queryTime))
                    .thenReturn(Optional.of(v2));
            when(priceVersionMapper.toResponseDTO(v2)).thenReturn(v2Response);

            Optional<PriceVersionResponseDTO> result =
                    priceVersionService.resolveEffectiveVersionForTimestamp(20L, queryTime);

            assertThat(result).isPresent();
            assertThat(result.get().getAmount()).isEqualByComparingTo("599");
        }
    }

    // ── ListVersionsTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("listVersions")
    class ListVersionsTests {

        @Test
        @DisplayName("returns versions newest-first")
        void returnsNewestFirst() {
            PriceVersion v2 = PriceVersion.builder().id(101L).price(price)
                    .effectiveFrom(LocalDateTime.now().minusDays(1)).build();

            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            when(priceVersionRepository.findByPriceIdOrderByEffectiveFromDesc(20L))
                    .thenReturn(List.of(v2, v1)); // newest first
            when(priceVersionMapper.toResponseDTO(any())).thenReturn(v1Response);

            List<PriceVersionResponseDTO> result = priceVersionService.listVersions(1L, 20L);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("tenant isolation — wrong merchant for price → 404")
        void tenantIsolation() {
            when(priceRepository.findByMerchantIdAndId(2L, 20L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> priceVersionService.listVersions(2L, 20L))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRICE_NOT_FOUND");
        }
    }
}
