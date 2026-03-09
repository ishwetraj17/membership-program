package com.firstclub.payments.service;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.mapper.PaymentMethodMapper;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.impl.PaymentMethodServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodServiceImpl Unit Tests")
class PaymentMethodServiceTest {

    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private PaymentMethodMapper paymentMethodMapper;

    @InjectMocks
    private PaymentMethodServiceImpl service;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static final Long MERCHANT_ID = 1L;
    private static final Long CUSTOMER_ID = 5L;
    private static final Long PM_ID = 10L;

    private MerchantAccount merchant;
    private Customer customer;
    private PaymentMethod paymentMethod;
    private PaymentMethodCreateRequestDTO createRequest;
    private PaymentMethodResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(MERCHANT_ID)
                .merchantCode("M1")
                .legalName("Test Merchant")
                .status(MerchantStatus.ACTIVE)
                .build();

        customer = Customer.builder()
                .id(CUSTOMER_ID)
                .merchant(merchant)
                .email("c@test.com")
                .status(CustomerStatus.ACTIVE)
                .build();

        paymentMethod = PaymentMethod.builder()
                .id(PM_ID)
                .merchant(merchant)
                .customer(customer)
                .methodType(PaymentMethodType.CARD)
                .providerToken("tok_visa_test_001")
                .provider("razorpay")
                .last4("4242")
                .brand("Visa")
                .status(PaymentMethodStatus.ACTIVE)
                .build();

        createRequest = PaymentMethodCreateRequestDTO.builder()
                .methodType(PaymentMethodType.CARD)
                .providerToken("tok_visa_test_001")
                .provider("razorpay")
                .last4("4242")
                .brand("Visa")
                .makeDefault(false)
                .build();

        responseDTO = PaymentMethodResponseDTO.builder()
                .id(PM_ID)
                .merchantId(MERCHANT_ID)
                .customerId(CUSTOMER_ID)
                .methodType(PaymentMethodType.CARD)
                .providerToken("tok_visa_test_001")
                .provider("razorpay")
                .status(PaymentMethodStatus.ACTIVE)
                .build();
    }

    // ── CreatePaymentMethodTests ──────────────────────────────────────────────

    @Nested
    @DisplayName("createPaymentMethod")
    class CreatePaymentMethodTests {

        @Test
        @DisplayName("first payment method -> automatically set as default")
        void firstMethodAutoDefault() {
            when(merchantAccountRepository.findById(MERCHANT_ID))
                    .thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken(anyString(), anyString()))
                    .thenReturn(false);
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Collections.emptyList());
            when(paymentMethodMapper.toEntity(createRequest)).thenReturn(paymentMethod);
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.empty());
            when(paymentMethodRepository.save(any(PaymentMethod.class))).thenReturn(paymentMethod);
            when(paymentMethodMapper.toResponseDTO(paymentMethod)).thenReturn(responseDTO);

            PaymentMethodResponseDTO result = service.createPaymentMethod(
                    MERCHANT_ID, CUSTOMER_ID, createRequest);

            assertThat(result).isEqualTo(responseDTO);
            verify(paymentMethodRepository).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("makeDefault=true clears previous default")
        void makeDefaultClearsPreviousDefault() {
            PaymentMethod oldDefault = PaymentMethod.builder()
                    .id(99L).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.UPI).providerToken("upi_tok_old")
                    .provider("razorpay").status(PaymentMethodStatus.ACTIVE)
                    .build();
            oldDefault.setDefault(true);

            PaymentMethodCreateRequestDTO makeDefaultRequest = PaymentMethodCreateRequestDTO.builder()
                    .methodType(PaymentMethodType.CARD)
                    .providerToken("tok_visa_new_002")
                    .provider("razorpay")
                    .makeDefault(true)
                    .build();

            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken(anyString(), anyString()))
                    .thenReturn(false);
            when(paymentMethodRepository.findByMerchantIdAndCustomerId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(List.of(oldDefault));
            when(paymentMethodMapper.toEntity(makeDefaultRequest)).thenReturn(paymentMethod);
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.of(oldDefault));
            when(paymentMethodRepository.save(any(PaymentMethod.class))).thenReturn(paymentMethod);
            when(paymentMethodMapper.toResponseDTO(paymentMethod)).thenReturn(responseDTO);

            service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, makeDefaultRequest);

            // old default should have been cleared
            assertThat(oldDefault.isDefault()).isFalse();
            verify(paymentMethodRepository, times(2)).save(any(PaymentMethod.class));
        }

        @Test
        @DisplayName("duplicate provider token -> CONFLICT 409")
        void duplicateProviderToken() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(customer));
            when(paymentMethodRepository.existsByProviderAndProviderToken("razorpay",
                    "tok_visa_test_001")).thenReturn(true);

            assertThatThrownBy(
                    () -> service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, createRequest))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("DUPLICATE_PROVIDER_TOKEN");
        }

        @Test
        @DisplayName("unknown merchant -> MerchantException 404")
        void unknownMerchant() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                    () -> service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, createRequest))
                    .isInstanceOf(MerchantException.class);
        }

        @Test
        @DisplayName("unknown customer -> CustomerException 404")
        void unknownCustomer() {
            when(merchantAccountRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant));
            when(customerRepository.findByMerchantIdAndId(MERCHANT_ID, CUSTOMER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(
                    () -> service.createPaymentMethod(MERCHANT_ID, CUSTOMER_ID, createRequest))
                    .isInstanceOf(CustomerException.class);
        }
    }

    // ── SetDefaultPaymentMethodTests ─────────────────────────────────────────

    @Nested
    @DisplayName("setDefaultPaymentMethod")
    class SetDefaultPaymentMethodTests {

        @Test
        @DisplayName("sets new default and clears previous")
        void success() {
            PaymentMethod oldDefault = PaymentMethod.builder()
                    .id(99L).merchant(merchant).customer(customer)
                    .methodType(PaymentMethodType.UPI)
                    .providerToken("upi_tok_old").provider("razorpay")
                    .status(PaymentMethodStatus.ACTIVE).build();
            oldDefault.setDefault(true);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(paymentMethodRepository.findByCustomerIdAndIsDefaultTrue(CUSTOMER_ID))
                    .thenReturn(Optional.of(oldDefault));
            when(paymentMethodRepository.save(any(PaymentMethod.class))).thenReturn(paymentMethod);
            when(paymentMethodMapper.toResponseDTO(paymentMethod)).thenReturn(responseDTO);

            PaymentMethodResponseDTO result =
                    service.setDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            assertThat(result).isEqualTo(responseDTO);
            assertThat(oldDefault.isDefault()).isFalse();
            assertThat(paymentMethod.isDefault()).isTrue();
        }

        @Test
        @DisplayName("revoked payment method -> 400 PAYMENT_METHOD_NOT_USABLE")
        void revokedMethodCannotBeDefault() {
            paymentMethod.setStatus(PaymentMethodStatus.REVOKED);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));

            assertThatThrownBy(
                    () -> service.setDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("PAYMENT_METHOD_NOT_USABLE");
        }

        @Test
        @DisplayName("payment method not found -> 404 PAYMENT_METHOD_NOT_FOUND")
        void notFound() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                    () -> service.setDefaultPaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
        }
    }

    // ── RevokePaymentMethodTests ──────────────────────────────────────────────

    @Nested
    @DisplayName("revokePaymentMethod")
    class RevokePaymentMethodTests {

        @Test
        @DisplayName("revoking active non-default method -> sets REVOKED")
        void revokeActive() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(paymentMethodRepository.save(paymentMethod)).thenReturn(paymentMethod);
            when(paymentMethodMapper.toResponseDTO(paymentMethod)).thenReturn(responseDTO);

            service.revokePaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            assertThat(paymentMethod.getStatus()).isEqualTo(PaymentMethodStatus.REVOKED);
        }

        @Test
        @DisplayName("revoking the current default -> clears isDefault flag")
        void revokeClearsDefault() {
            paymentMethod.setDefault(true);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(paymentMethod));
            when(paymentMethodRepository.save(paymentMethod)).thenReturn(paymentMethod);
            when(paymentMethodMapper.toResponseDTO(paymentMethod)).thenReturn(responseDTO);

            service.revokePaymentMethod(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            assertThat(paymentMethod.getStatus()).isEqualTo(PaymentMethodStatus.REVOKED);
            assertThat(paymentMethod.isDefault()).isFalse();
        }

        @Test
        @DisplayName("cross-merchant access -> 404 PAYMENT_METHOD_NOT_FOUND")
        void tenantIsolation() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    99L, CUSTOMER_ID, PM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(
                    () -> service.revokePaymentMethod(99L, CUSTOMER_ID, PM_ID))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
        }
    }
}
