package com.firstclub.payments.service;

import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.entity.MandateStatus;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodMandate;
import com.firstclub.payments.entity.PaymentMethodStatus;
import com.firstclub.payments.entity.PaymentMethodType;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.mapper.PaymentMethodMandateMapper;
import com.firstclub.payments.repository.PaymentMethodMandateRepository;
import com.firstclub.payments.repository.PaymentMethodRepository;
import com.firstclub.payments.service.impl.PaymentMethodMandateServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentMethodMandateServiceImpl Unit Tests")
class PaymentMethodMandateServiceTest {

    @Mock private PaymentMethodMandateRepository mandateRepository;
    @Mock private PaymentMethodRepository paymentMethodRepository;
    @Mock private PaymentMethodMandateMapper mandateMapper;

    @InjectMocks
    private PaymentMethodMandateServiceImpl service;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static final Long MERCHANT_ID = 1L;
    private static final Long CUSTOMER_ID = 5L;
    private static final Long PM_ID = 10L;
    private static final Long MANDATE_ID = 20L;

    private MerchantAccount merchant;
    private Customer customer;
    private PaymentMethod cardPaymentMethod;
    private PaymentMethod upiPaymentMethod;
    private PaymentMethodMandate mandate;
    private PaymentMethodMandateCreateRequestDTO createRequest;
    private PaymentMethodMandateResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(MERCHANT_ID).merchantCode("M1")
                .legalName("Test Merchant").status(MerchantStatus.ACTIVE).build();

        customer = Customer.builder()
                .id(CUSTOMER_ID).merchant(merchant)
                .email("c@test.com").status(CustomerStatus.ACTIVE).build();

        cardPaymentMethod = PaymentMethod.builder()
                .id(PM_ID).merchant(merchant).customer(customer)
                .methodType(PaymentMethodType.CARD)
                .providerToken("tok_card_001").provider("razorpay")
                .status(PaymentMethodStatus.ACTIVE).build();

        upiPaymentMethod = PaymentMethod.builder()
                .id(PM_ID).merchant(merchant).customer(customer)
                .methodType(PaymentMethodType.UPI)
                .providerToken("upi_tok_001").provider("razorpay")
                .status(PaymentMethodStatus.ACTIVE).build();

        mandate = PaymentMethodMandate.builder()
                .id(MANDATE_ID)
                .paymentMethod(cardPaymentMethod)
                .mandateReference("NACH_REF_001")
                .status(MandateStatus.PENDING)
                .maxAmount(new BigDecimal("5000.00"))
                .currency("INR")
                .build();

        createRequest = PaymentMethodMandateCreateRequestDTO.builder()
                .mandateReference("NACH_REF_001")
                .maxAmount(new BigDecimal("5000.00"))
                .currency("INR")
                .build();

        responseDTO = PaymentMethodMandateResponseDTO.builder()
                .id(MANDATE_ID)
                .paymentMethodId(PM_ID)
                .mandateReference("NACH_REF_001")
                .status(MandateStatus.PENDING)
                .maxAmount(new BigDecimal("5000.00"))
                .currency("INR")
                .build();
    }

    // ── CreateMandateTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("createMandate")
    class CreateMandateTests {

        @Test
        @DisplayName("CARD type -> PENDING mandate created")
        void cardTypeSuccess() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));
            when(mandateMapper.toEntity(createRequest)).thenReturn(mandate);
            when(mandateRepository.save(any(PaymentMethodMandate.class))).thenReturn(mandate);
            when(mandateMapper.toResponseDTO(mandate)).thenReturn(responseDTO);

            PaymentMethodMandateResponseDTO result =
                    service.createMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, createRequest);

            assertThat(result).isEqualTo(responseDTO);
            assertThat(mandate.getStatus()).isEqualTo(MandateStatus.PENDING);
            verify(mandateRepository).save(mandate);
        }

        @Test
        @DisplayName("UPI type does not support mandates -> 400 UNSUPPORTED_MANDATE_METHOD_TYPE")
        void upiTypeNotSupported() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(upiPaymentMethod));

            assertThatThrownBy(() ->
                    service.createMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, createRequest))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("UNSUPPORTED_MANDATE_METHOD_TYPE");
        }

        @Test
        @DisplayName("REVOKED payment method -> 400 PAYMENT_METHOD_NOT_USABLE")
        void revokedMethodCannotHaveMandate() {
            cardPaymentMethod.setStatus(PaymentMethodStatus.REVOKED);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));

            assertThatThrownBy(() ->
                    service.createMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, createRequest))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("PAYMENT_METHOD_NOT_USABLE");
        }

        @Test
        @DisplayName("payment method not found -> 404 PAYMENT_METHOD_NOT_FOUND")
        void paymentMethodNotFound() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.createMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, createRequest))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("PAYMENT_METHOD_NOT_FOUND");
        }
    }

    // ── RevokeMandateTests ────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeMandate")
    class RevokeMandateTests {

        @Test
        @DisplayName("active mandate -> sets REVOKED and revokedAt")
        void success() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));
            when(mandateRepository.findByIdAndPaymentMethodId(MANDATE_ID, PM_ID))
                    .thenReturn(Optional.of(mandate));
            when(mandateRepository.save(mandate)).thenReturn(mandate);
            when(mandateMapper.toResponseDTO(mandate)).thenReturn(responseDTO);

            service.revokeMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, MANDATE_ID);

            assertThat(mandate.getStatus()).isEqualTo(MandateStatus.REVOKED);
            assertThat(mandate.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("already revoked -> 400 MANDATE_ALREADY_REVOKED")
        void alreadyRevoked() {
            mandate.setStatus(MandateStatus.REVOKED);

            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));
            when(mandateRepository.findByIdAndPaymentMethodId(MANDATE_ID, PM_ID))
                    .thenReturn(Optional.of(mandate));

            assertThatThrownBy(() ->
                    service.revokeMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, MANDATE_ID))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("MANDATE_ALREADY_REVOKED");
        }

        @Test
        @DisplayName("mandate not found -> 404 MANDATE_NOT_FOUND")
        void mandateNotFound() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));
            when(mandateRepository.findByIdAndPaymentMethodId(MANDATE_ID, PM_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.revokeMandate(MERCHANT_ID, CUSTOMER_ID, PM_ID, MANDATE_ID))
                    .isInstanceOf(PaymentMethodException.class)
                    .extracting("errorCode")
                    .isEqualTo("MANDATE_NOT_FOUND");
        }
    }

    // ── ListMandatesTests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("listMandates")
    class ListMandatesTests {

        @Test
        @DisplayName("returns mandates ordered by createdAt desc")
        void listSuccess() {
            when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndId(
                    MERCHANT_ID, CUSTOMER_ID, PM_ID)).thenReturn(Optional.of(cardPaymentMethod));
            when(mandateRepository.findByPaymentMethodIdOrderByCreatedAtDesc(PM_ID))
                    .thenReturn(List.of(mandate));
            when(mandateMapper.toResponseDTO(mandate)).thenReturn(responseDTO);

            List<PaymentMethodMandateResponseDTO> result =
                    service.listMandates(MERCHANT_ID, CUSTOMER_ID, PM_ID);

            assertThat(result).hasSize(1).containsExactly(responseDTO);
        }
    }
}
