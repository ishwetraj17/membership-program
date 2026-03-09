package com.firstclub.subscription.service;

import com.firstclub.catalog.entity.BillingIntervalUnit;
import com.firstclub.catalog.entity.BillingType;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.entity.ProductStatus;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.exception.SubscriptionException;
import com.firstclub.subscription.mapper.SubscriptionV2Mapper;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.subscription.service.impl.SubscriptionV2ServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionV2ServiceImpl Unit Tests")
class SubscriptionV2ServiceTest {

    @Mock private SubscriptionV2Repository subscriptionRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProductRepository productRepository;
    @Mock private PriceRepository priceRepository;
    @Mock private PriceVersionRepository priceVersionRepository;
    @Mock private SubscriptionV2Mapper mapper;

    @InjectMocks
    private SubscriptionV2ServiceImpl service;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private MerchantAccount merchant;
    private Customer customer;
    private Product product;
    private Price price;
    private Price trialPrice;
    private PriceVersion priceVersion;
    private SubscriptionV2 activeSubscription;
    private SubscriptionResponseDTO responseDTO;

    private static final Set<SubscriptionStatusV2> BLOCKING = EnumSet.of(
            SubscriptionStatusV2.INCOMPLETE, SubscriptionStatusV2.TRIALING,
            SubscriptionStatusV2.ACTIVE, SubscriptionStatusV2.PAST_DUE,
            SubscriptionStatusV2.PAUSED, SubscriptionStatusV2.SUSPENDED);

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder().id(1L).merchantCode("M1")
                .legalName("Merchant One").status(MerchantStatus.ACTIVE).build();

        customer = Customer.builder().id(5L).merchant(merchant)
                .email("c@test.com").status(CustomerStatus.ACTIVE).build();

        product = Product.builder().id(10L).merchant(merchant)
                .productCode("GOLD").name("Gold").status(ProductStatus.ACTIVE).build();

        priceVersion = PriceVersion.builder().id(100L)
                .amount(new BigDecimal("499")).currency("INR")
                .effectiveFrom(LocalDateTime.now().minusDays(1))
                .build();

        price = Price.builder().id(20L).merchant(merchant).product(product)
                .priceCode("GOLD_M").billingType(BillingType.RECURRING).currency("INR")
                .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                .billingIntervalCount(1).trialDays(0).active(true).build();

        trialPrice = Price.builder().id(21L).merchant(merchant).product(product)
                .priceCode("GOLD_TRIAL").billingType(BillingType.RECURRING).currency("INR")
                .amount(new BigDecimal("499")).billingIntervalUnit(BillingIntervalUnit.MONTH)
                .billingIntervalCount(1).trialDays(14).active(true).build();

        activeSubscription = SubscriptionV2.builder().id(50L).merchant(merchant)
                .customer(customer).product(product).price(price).priceVersion(priceVersion)
                .status(SubscriptionStatusV2.ACTIVE).billingAnchorAt(LocalDateTime.now())
                .build();

        responseDTO = SubscriptionResponseDTO.builder().id(50L).merchantId(1L)
                .customerId(5L).productId(10L).priceId(20L)
                .status(SubscriptionStatusV2.ACTIVE).build();
    }

    // ── CreateSubscriptionTests ───────────────────────────────────────────────

    @Nested
    @DisplayName("createSubscription")
    class CreateSubscriptionTests {

        @Test
        @DisplayName("creates INCOMPLETE subscription when price has no trial")
        void createIncomplete() {
            SubscriptionCreateRequestDTO req = SubscriptionCreateRequestDTO.builder()
                    .customerId(5L).productId(10L).priceId(20L).build();

            SubscriptionV2 incompleteStub = SubscriptionV2.builder().id(51L)
                    .status(SubscriptionStatusV2.INCOMPLETE).merchant(merchant).customer(customer)
                    .product(product).price(price).priceVersion(priceVersion)
                    .billingAnchorAt(LocalDateTime.now()).build();
            SubscriptionResponseDTO incompleteDTO = SubscriptionResponseDTO.builder()
                    .id(51L).status(SubscriptionStatusV2.INCOMPLETE).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(customer));
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(product));
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            when(subscriptionRepository.existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn(
                    1L, 5L, 10L, BLOCKING)).thenReturn(false);
            when(priceVersionRepository.findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    eq(20L), any())).thenReturn(Optional.of(priceVersion));
            when(subscriptionRepository.save(any())).thenReturn(incompleteStub);
            when(mapper.toResponseDTO(incompleteStub)).thenReturn(incompleteDTO);

            SubscriptionResponseDTO result = service.createSubscription(1L, req);
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatusV2.INCOMPLETE);
        }

        @Test
        @DisplayName("creates TRIALING subscription when price has trialDays > 0")
        void createTrialing() {
            SubscriptionCreateRequestDTO req = SubscriptionCreateRequestDTO.builder()
                    .customerId(5L).productId(10L).priceId(21L).build();

            SubscriptionV2 trialSub = SubscriptionV2.builder().id(52L)
                    .status(SubscriptionStatusV2.TRIALING).merchant(merchant).customer(customer)
                    .product(product).price(trialPrice).priceVersion(priceVersion)
                    .billingAnchorAt(LocalDateTime.now())
                    .trialEndsAt(LocalDateTime.now().plusDays(14)).build();
            SubscriptionResponseDTO trialDTO = SubscriptionResponseDTO.builder()
                    .id(52L).status(SubscriptionStatusV2.TRIALING).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(customer));
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(product));
            when(priceRepository.findByMerchantIdAndId(1L, 21L)).thenReturn(Optional.of(trialPrice));
            when(subscriptionRepository.existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn(
                    1L, 5L, 10L, BLOCKING)).thenReturn(false);
            when(priceVersionRepository.findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    eq(21L), any())).thenReturn(Optional.of(priceVersion));
            when(subscriptionRepository.save(any())).thenReturn(trialSub);
            when(mapper.toResponseDTO(trialSub)).thenReturn(trialDTO);

            SubscriptionResponseDTO result = service.createSubscription(1L, req);
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatusV2.TRIALING);
        }

        @Test
        @DisplayName("duplicate active subscription → 409")
        void duplicateActiveSubscription() {
            SubscriptionCreateRequestDTO req = SubscriptionCreateRequestDTO.builder()
                    .customerId(5L).productId(10L).priceId(20L).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(customer));
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(product));
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            // price version is resolved BEFORE the duplicate check — it must succeed to reach the guard
            when(priceVersionRepository.findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    eq(20L), any())).thenReturn(Optional.of(priceVersion));
            when(subscriptionRepository.existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn(
                    1L, 5L, 10L, BLOCKING)).thenReturn(true);

            assertThatThrownBy(() -> service.createSubscription(1L, req))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("DUPLICATE_ACTIVE_SUBSCRIPTION");
        }

        @Test
        @DisplayName("no effective price version → 400")
        void noPriceVersion() {
            SubscriptionCreateRequestDTO req = SubscriptionCreateRequestDTO.builder()
                    .customerId(5L).productId(10L).priceId(20L).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(customer));
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(product));
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(price));
            // price version lookup returns empty — NO_PRICE_VERSION_AVAILABLE thrown before duplicate check
            when(priceVersionRepository.findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                    eq(20L), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createSubscription(1L, req))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("NO_PRICE_VERSION_AVAILABLE");
        }

        @Test
        @DisplayName("price from different product → 404")
        void priceBelongsToDifferentProduct() {
            Product otherProduct = Product.builder().id(99L).merchant(merchant)
                    .productCode("OTHER").name("Other").status(ProductStatus.ACTIVE).build();
            Price priceBelongingToOther = Price.builder().id(20L).merchant(merchant)
                    .product(otherProduct).priceCode("OTHER_P").billingType(BillingType.RECURRING)
                    .currency("INR").amount(new BigDecimal("99"))
                    .billingIntervalUnit(BillingIntervalUnit.MONTH).billingIntervalCount(1)
                    .trialDays(0).active(true).build();

            SubscriptionCreateRequestDTO req = SubscriptionCreateRequestDTO.builder()
                    .customerId(5L).productId(10L).priceId(20L).build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(1L, 5L)).thenReturn(Optional.of(customer));
            when(productRepository.findByMerchantIdAndId(1L, 10L)).thenReturn(Optional.of(product));
            when(priceRepository.findByMerchantIdAndId(1L, 20L)).thenReturn(Optional.of(priceBelongingToOther));

            assertThatThrownBy(() -> service.createSubscription(1L, req))
                    .isInstanceOf(CatalogException.class)
                    .extracting("errorCode").isEqualTo("PRICE_NOT_FOUND");
        }
    }

    // ── CancelSubscriptionTests ───────────────────────────────────────────────

    @Nested
    @DisplayName("cancelSubscription")
    class CancelSubscriptionTests {

        @Test
        @DisplayName("immediate cancel — status → CANCELLED")
        void cancelImmediately() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSubscription));
            when(subscriptionRepository.save(any())).thenReturn(activeSubscription);
            SubscriptionResponseDTO cancelDTO = SubscriptionResponseDTO.builder()
                    .id(50L).status(SubscriptionStatusV2.CANCELLED).build();
            when(mapper.toResponseDTO(any())).thenReturn(cancelDTO);

            SubscriptionResponseDTO result = service.cancelSubscription(1L, 50L, false);
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatusV2.CANCELLED);
        }

        @Test
        @DisplayName("cancel at period end — status stays ACTIVE, flag set")
        void cancelAtPeriodEnd() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSubscription));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(mapper.toResponseDTO(any())).thenReturn(responseDTO);

            service.cancelSubscription(1L, 50L, true);

            verify(subscriptionRepository).save(argThat(s -> s.isCancelAtPeriodEnd()));
        }

        @Test
        @DisplayName("cancel already-cancelled subscription → 400")
        void cancelTerminal() {
            SubscriptionV2 cancelled = SubscriptionV2.builder().id(50L)
                    .status(SubscriptionStatusV2.CANCELLED).merchant(merchant).build();
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(cancelled));

            assertThatThrownBy(() -> service.cancelSubscription(1L, 50L, false))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_ALREADY_CANCELLED");
        }
    }

    // ── PauseResumeTests ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("pause and resume")
    class PauseResumeTests {

        @Test
        @DisplayName("pause ACTIVE → PAUSED")
        void pauseActive() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSubscription));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionResponseDTO pausedDTO = SubscriptionResponseDTO.builder()
                    .id(50L).status(SubscriptionStatusV2.PAUSED).build();
            when(mapper.toResponseDTO(any())).thenReturn(pausedDTO);

            SubscriptionResponseDTO result = service.pauseSubscription(1L, 50L);
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatusV2.PAUSED);
        }

        @Test
        @DisplayName("pause already-PAUSED → 400")
        void pauseAlreadyPaused() {
            SubscriptionV2 paused = SubscriptionV2.builder().id(50L)
                    .status(SubscriptionStatusV2.PAUSED).merchant(merchant).build();
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(paused));

            assertThatThrownBy(() -> service.pauseSubscription(1L, 50L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_ALREADY_PAUSED");
        }

        @Test
        @DisplayName("resume PAUSED → ACTIVE")
        void resumePaused() {
            SubscriptionV2 paused = SubscriptionV2.builder().id(50L)
                    .status(SubscriptionStatusV2.PAUSED).merchant(merchant)
                    .customer(customer).product(product).price(price).priceVersion(priceVersion)
                    .billingAnchorAt(LocalDateTime.now()).build();
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(paused));
            when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SubscriptionResponseDTO activeDTO = SubscriptionResponseDTO.builder()
                    .id(50L).status(SubscriptionStatusV2.ACTIVE).build();
            when(mapper.toResponseDTO(any())).thenReturn(activeDTO);

            SubscriptionResponseDTO result = service.resumeSubscription(1L, 50L);
            assertThat(result.getStatus()).isEqualTo(SubscriptionStatusV2.ACTIVE);
        }

        @Test
        @DisplayName("resume non-PAUSED subscription → 400")
        void resumeNonPaused() {
            when(subscriptionRepository.findByMerchantIdAndId(1L, 50L))
                    .thenReturn(Optional.of(activeSubscription));

            assertThatThrownBy(() -> service.resumeSubscription(1L, 50L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_NOT_PAUSED");
        }
    }

    // ── TenantIsolationTests ──────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("cross-merchant subscription lookup → 404")
        void crossMerchantGet() {
            when(subscriptionRepository.findByMerchantIdAndId(2L, 50L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSubscriptionById(2L, 50L))
                    .isInstanceOf(SubscriptionException.class)
                    .extracting("errorCode").isEqualTo("SUBSCRIPTION_NOT_FOUND");
        }
    }
}
