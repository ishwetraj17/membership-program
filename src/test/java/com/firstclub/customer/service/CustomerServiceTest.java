package com.firstclub.customer.service;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.mapper.CustomerMapper;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.customer.service.impl.CustomerServiceImpl;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerServiceImpl Unit Tests")
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private MerchantAccountRepository merchantAccountRepository;
    @Mock private CustomerMapper customerMapper;
    @InjectMocks private CustomerServiceImpl customerService;

    private MerchantAccount merchant1;
    private MerchantAccount merchant2;
    private Customer activeCustomer;
    private Customer blockedCustomer;
    private CustomerCreateRequestDTO createRequest;
    private CustomerResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        merchant1 = MerchantAccount.builder()
                .id(1L).merchantCode("M1").legalName("Merchant One")
                .displayName("M1").supportEmail("m1@test.com")
                .status(MerchantStatus.ACTIVE).build();

        merchant2 = MerchantAccount.builder()
                .id(2L).merchantCode("M2").legalName("Merchant Two")
                .displayName("M2").supportEmail("m2@test.com")
                .status(MerchantStatus.ACTIVE).build();

        activeCustomer = Customer.builder()
                .id(10L).merchant(merchant1)
                .email("alice@example.com").fullName("Alice Smith")
                .status(CustomerStatus.ACTIVE).build();

        blockedCustomer = Customer.builder()
                .id(11L).merchant(merchant1)
                .email("blocked@example.com").fullName("Bob Blocked")
                .status(CustomerStatus.BLOCKED).build();

        createRequest = CustomerCreateRequestDTO.builder()
                .fullName("Alice Smith")
                .email("alice@example.com")
                .build();

        responseDTO = CustomerResponseDTO.builder()
                .id(10L).merchantId(1L)
                .email("alice@example.com").fullName("Alice Smith")
                .status(CustomerStatus.ACTIVE).build();
    }

    @Nested
    @DisplayName("createCustomer")
    class CreateCustomerTests {

        @Test
        @DisplayName("Should create customer successfully for a valid merchant")
        void shouldCreateCustomerSuccessfully() {
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant1));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(1L, "alice@example.com"))
                    .thenReturn(false);
            when(customerMapper.toEntity(createRequest)).thenReturn(activeCustomer);
            when(customerRepository.save(any(Customer.class))).thenReturn(activeCustomer);
            when(customerMapper.toResponseDTO(activeCustomer)).thenReturn(responseDTO);

            CustomerResponseDTO result = customerService.createCustomer(1L, createRequest);

            assertThat(result.getEmail()).isEqualTo("alice@example.com");
            assertThat(result.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
            verify(customerRepository).save(any(Customer.class));
        }

        @Test
        @DisplayName("Should reject duplicate email within same merchant")
        void shouldRejectDuplicateEmailWithinMerchant() {
            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant1));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(1L, "alice@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> customerService.createCustomer(1L, createRequest))
                    .isInstanceOf(CustomerException.class)
                    .hasMessageContaining("alice@example.com")
                    .extracting("errorCode").isEqualTo("DUPLICATE_CUSTOMER_EMAIL");
        }

        @Test
        @DisplayName("Should allow same email across different merchants (tenant isolation)")
        void shouldAllowSameEmailAcrossDifferentMerchants() {
            // Merchant 1: email already exists
            // Merchant 2: email does NOT exist → creation should succeed
            when(merchantAccountRepository.findById(2L)).thenReturn(Optional.of(merchant2));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(2L, "alice@example.com"))
                    .thenReturn(false);

            Customer customerForM2 = Customer.builder()
                    .id(20L).merchant(merchant2)
                    .email("alice@example.com").fullName("Alice Smith")
                    .status(CustomerStatus.ACTIVE).build();
            CustomerResponseDTO responseForM2 = CustomerResponseDTO.builder()
                    .id(20L).merchantId(2L).email("alice@example.com")
                    .status(CustomerStatus.ACTIVE).build();

            when(customerMapper.toEntity(createRequest)).thenReturn(customerForM2);
            when(customerRepository.save(any(Customer.class))).thenReturn(customerForM2);
            when(customerMapper.toResponseDTO(customerForM2)).thenReturn(responseForM2);

            CustomerResponseDTO result = customerService.createCustomer(2L, createRequest);

            assertThat(result.getMerchantId()).isEqualTo(2L);
            assertThat(result.getEmail()).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("Should normalise email to lower-case before persistence")
        void shouldNormaliseEmailToLowerCase() {
            CustomerCreateRequestDTO mixedCaseRequest = CustomerCreateRequestDTO.builder()
                    .fullName("Alice Smith").email("ALICE@EXAMPLE.COM").build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant1));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(1L, "alice@example.com"))
                    .thenReturn(false);
            when(customerMapper.toEntity(mixedCaseRequest)).thenReturn(activeCustomer);
            when(customerRepository.save(any(Customer.class))).thenReturn(activeCustomer);
            when(customerMapper.toResponseDTO(activeCustomer)).thenReturn(responseDTO);

            customerService.createCustomer(1L, mixedCaseRequest);

            verify(customerRepository).existsByMerchantIdAndEmailIgnoreCase(1L, "alice@example.com");
        }

        @Test
        @DisplayName("Should reject duplicate externalCustomerId within same merchant")
        void shouldRejectDuplicateExternalCustomerId() {
            CustomerCreateRequestDTO requestWithExtId = CustomerCreateRequestDTO.builder()
                    .fullName("Alice Smith").email("new@example.com")
                    .externalCustomerId("EXT-001").build();

            when(merchantAccountRepository.findById(1L)).thenReturn(Optional.of(merchant1));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(1L, "new@example.com"))
                    .thenReturn(false);
            when(customerRepository.existsByMerchantIdAndExternalCustomerId(1L, "EXT-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> customerService.createCustomer(1L, requestWithExtId))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("DUPLICATE_EXTERNAL_CUSTOMER_ID");
        }

        @Test
        @DisplayName("Should throw 404 when merchant does not exist")
        void shouldThrowWhenMerchantNotFound() {
            when(merchantAccountRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.createCustomer(99L, createRequest))
                    .isInstanceOf(MerchantException.class)
                    .extracting("errorCode").isEqualTo("MERCHANT_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("updateCustomer")
    class UpdateCustomerTests {

        @Test
        @DisplayName("Should update customer fields successfully")
        void shouldUpdateCustomerSuccessfully() {
            CustomerUpdateRequestDTO update = CustomerUpdateRequestDTO.builder()
                    .fullName("Alice Updated").build();

            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(activeCustomer));
            when(customerRepository.save(any(Customer.class))).thenReturn(activeCustomer);
            when(customerMapper.toResponseDTO(activeCustomer)).thenReturn(responseDTO);

            CustomerResponseDTO result = customerService.updateCustomer(1L, 10L, update);

            assertThat(result).isNotNull();
            verify(customerMapper).updateEntityFromDTO(update, activeCustomer);
        }

        @Test
        @DisplayName("Should reject email update that conflicts with existing customer in same merchant")
        void shouldRejectConflictingEmailUpdate() {
            CustomerUpdateRequestDTO update = CustomerUpdateRequestDTO.builder()
                    .email("existing@example.com").build();

            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(activeCustomer));
            when(customerRepository.existsByMerchantIdAndEmailIgnoreCase(1L, "existing@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() -> customerService.updateCustomer(1L, 10L, update))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("DUPLICATE_CUSTOMER_EMAIL");
        }
    }

    @Nested
    @DisplayName("blockCustomer / activateCustomer")
    class StatusTransitionTests {

        @Test
        @DisplayName("Should block an ACTIVE customer")
        void shouldBlockActiveCustomer() {
            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(activeCustomer));
            when(customerRepository.save(any(Customer.class))).thenReturn(activeCustomer);
            when(customerMapper.toResponseDTO(activeCustomer)).thenReturn(responseDTO);

            customerService.blockCustomer(1L, 10L);

            assertThat(activeCustomer.getStatus()).isEqualTo(CustomerStatus.BLOCKED);
            verify(customerRepository).save(activeCustomer);
        }

        @Test
        @DisplayName("Should be idempotent when blocking an already-BLOCKED customer")
        void shouldBeIdempotentForAlreadyBlockedCustomer() {
            when(customerRepository.findByMerchantIdAndId(1L, 11L))
                    .thenReturn(Optional.of(blockedCustomer));
            when(customerMapper.toResponseDTO(blockedCustomer)).thenReturn(responseDTO);

            customerService.blockCustomer(1L, 11L);

            verify(customerRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should activate a BLOCKED customer")
        void shouldActivateBlockedCustomer() {
            when(customerRepository.findByMerchantIdAndId(1L, 11L))
                    .thenReturn(Optional.of(blockedCustomer));
            when(customerRepository.save(any(Customer.class))).thenReturn(blockedCustomer);
            when(customerMapper.toResponseDTO(blockedCustomer)).thenReturn(responseDTO);

            customerService.activateCustomer(1L, 11L);

            assertThat(blockedCustomer.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("ensureCustomerActive")
    class EnsureActiveTests {

        @Test
        @DisplayName("Should pass for ACTIVE customer")
        void shouldPassForActiveCustomer() {
            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(activeCustomer));

            assertThatCode(() -> customerService.ensureCustomerActive(1L, 10L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should throw for BLOCKED customer")
        void shouldThrowForBlockedCustomer() {
            when(customerRepository.findByMerchantIdAndId(1L, 11L))
                    .thenReturn(Optional.of(blockedCustomer));

            assertThatThrownBy(() -> customerService.ensureCustomerActive(1L, 11L))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("CUSTOMER_NOT_ACTIVE");
        }
    }

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolationTests {

        @Test
        @DisplayName("Should return 404 when customerId exists but belongs to different merchant")
        void shouldReturn404ForCrossmerchantRead() {
            // Customer 10 belongs to merchant 1; querying with merchant 2 should yield empty
            when(customerRepository.findByMerchantIdAndId(2L, 10L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(2L, 10L))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("CUSTOMER_NOT_FOUND");
        }
    }
}
