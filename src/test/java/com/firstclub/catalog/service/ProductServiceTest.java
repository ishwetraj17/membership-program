package com.firstclub.catalog.service;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.entity.ProductStatus;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.mapper.ProductMapper;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.catalog.service.impl.ProductServiceImpl;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductServiceImpl Unit Tests")
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private ProductMapper productMapper;
    @InjectMocks private ProductServiceImpl productService;

    private MerchantAccount merchant;
    private Product activeProduct;
    private ProductResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(1L).merchantCode("M1").legalName("Merchant One")
                .status(MerchantStatus.ACTIVE).build();

        activeProduct = Product.builder()
                .id(10L).merchant(merchant).productCode("GOLD_PLAN")
                .name("Gold Plan").status(ProductStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();

        responseDTO = ProductResponseDTO.builder()
                .id(10L).merchantId(1L).productCode("GOLD_PLAN")
                .name("Gold Plan").status(ProductStatus.ACTIVE).build();
    }

    // ── CreateProductTests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("createProduct")
    class CreateProductTests {

        @Test
        @DisplayName("success — returns 201 response")
        void success() {
            ProductCreateRequestDTO req = new ProductCreateRequestDTO("GOLD_PLAN", "Gold Plan", null);
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(productRepository.existsByMerchantIdAndProductCode(1L, "GOLD_PLAN")).thenReturn(false);
            when(productMapper.toEntity(req)).thenReturn(activeProduct);
            when(productRepository.save(any())).thenReturn(activeProduct);
            when(productMapper.toResponseDTO(activeProduct)).thenReturn(responseDTO);

            ProductResponseDTO result = productService.createProduct(1L, req);

            assertThat(result.getProductCode()).isEqualTo("GOLD_PLAN");
            verify(productRepository).save(any());
        }

        @Test
        @DisplayName("duplicate productCode within merchant → 409 CONFLICT")
        void duplicateCode() {
            ProductCreateRequestDTO req = new ProductCreateRequestDTO("GOLD_PLAN", "Gold Plan", null);
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(productRepository.existsByMerchantIdAndProductCode(1L, "GOLD_PLAN")).thenReturn(true);

            assertThatThrownBy(() -> productService.createProduct(1L, req))
                    .isInstanceOf(CatalogException.class)
                    .hasMessageContaining("GOLD_PLAN")
                    .extracting("errorCode").isEqualTo("DUPLICATE_PRODUCT_CODE");
        }

        @Test
        @DisplayName("same code across different merchants is allowed")
        void sameCodeDifferentMerchants() {
            MerchantAccount merchant2 = MerchantAccount.builder().id(2L).merchantCode("M2")
                    .legalName("Merchant Two").status(MerchantStatus.ACTIVE).build();
            ProductCreateRequestDTO req = new ProductCreateRequestDTO("GOLD_PLAN", "Gold Plan", null);

            when(merchantAccountRepository.findById(2L)).thenReturn(Optional.of(merchant2));
            when(productRepository.existsByMerchantIdAndProductCode(2L, "GOLD_PLAN")).thenReturn(false);
            when(productMapper.toEntity(req)).thenReturn(activeProduct);
            when(productRepository.save(any())).thenReturn(activeProduct);
            when(productMapper.toResponseDTO(any())).thenReturn(responseDTO);

            assertThatNoException().isThrownBy(() -> productService.createProduct(2L, req));
        }

        @Test
        @DisplayName("merchant not found → 404")
        void merchantNotFound() {
            ProductCreateRequestDTO req = new ProductCreateRequestDTO("X", "X", null);
            when(merchantAccountRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.createProduct(99L, req))
                    .isInstanceOf(MerchantException.class);
        }
    }

    // ── ArchiveProductTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("archiveProduct")
    class ArchiveProductTests {

        @Test
        @DisplayName("ACTIVE → ARCHIVED succeeds")
        void archiveActive() {
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(activeProduct));
            when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(productMapper.toResponseDTO(any())).thenReturn(
                    ProductResponseDTO.builder().status(ProductStatus.ARCHIVED).build());

            ProductResponseDTO result = productService.archiveProduct(1L, 10L);
            assertThat(result.getStatus()).isEqualTo(ProductStatus.ARCHIVED);
        }

        @Test
        @DisplayName("already ARCHIVED → idempotent, no extra save")
        void idempotentArchive() {
            activeProduct.setStatus(ProductStatus.ARCHIVED);
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(activeProduct));
            when(productMapper.toResponseDTO(activeProduct)).thenReturn(responseDTO);

            productService.archiveProduct(1L, 10L);
            verify(productRepository, never()).save(any());
        }

        @Test
        @DisplayName("ensureProductActive throws for ARCHIVED")
        void ensureActiveThrowsForArchived() {
            activeProduct.setStatus(ProductStatus.ARCHIVED);
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(activeProduct));

            assertThatThrownBy(() -> productService.ensureProductActive(1L, 10L))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRODUCT_ARCHIVED");
        }
    }

    // ── TenantIsolationTests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("tenantIsolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("cross-merchant read returns 404")
        void crossMerchantRead() {
            when(productRepository.findByMerchantIdAndId(2L, 10L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> productService.getProductById(2L, 10L))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRODUCT_NOT_FOUND");
        }
    }

    // ── ListProductsTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("listProducts")
    class ListProductsTests {

        @Test
        @DisplayName("with no filter — returns all by merchant")
        void noFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Product> page = new PageImpl<>(List.of(activeProduct));
            when(productRepository.findAllByMerchantId(1L, pageable)).thenReturn(page);
            when(productMapper.toResponseDTO(activeProduct)).thenReturn(responseDTO);

            Page<ProductResponseDTO> result = productService.listProducts(1L, null, pageable);
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("with ACTIVE filter — delegates to status-filtered query")
        void withStatusFilter() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<Product> page = new PageImpl<>(List.of(activeProduct));
            when(productRepository.findAllByMerchantIdAndStatus(1L, ProductStatus.ACTIVE, pageable))
                    .thenReturn(page);
            when(productMapper.toResponseDTO(activeProduct)).thenReturn(responseDTO);

            Page<ProductResponseDTO> result = productService.listProducts(1L, ProductStatus.ACTIVE, pageable);
            assertThat(result.getContent()).hasSize(1);
        }
    }
}
