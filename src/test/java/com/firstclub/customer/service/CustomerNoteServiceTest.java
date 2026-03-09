package com.firstclub.customer.service;

import com.firstclub.customer.dto.CustomerNoteCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerNote;
import com.firstclub.customer.entity.CustomerNoteVisibility;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.mapper.CustomerNoteMapper;
import com.firstclub.customer.repository.CustomerNoteRepository;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.customer.service.impl.CustomerNoteServiceImpl;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.repository.UserRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerNoteServiceImpl Unit Tests")
class CustomerNoteServiceTest {

    @Mock private CustomerNoteRepository customerNoteRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private CustomerNoteMapper customerNoteMapper;
    @InjectMocks private CustomerNoteServiceImpl customerNoteService;

    private MerchantAccount merchant;
    private Customer customer;
    private User operator;
    private CustomerNote savedNote;
    private CustomerNoteResponseDTO noteResponse;

    @BeforeEach
    void setUp() {
        merchant = MerchantAccount.builder()
                .id(1L).merchantCode("M1").legalName("Merchant One")
                .displayName("M1").supportEmail("m1@test.com")
                .status(MerchantStatus.ACTIVE).build();

        customer = Customer.builder()
                .id(10L).merchant(merchant)
                .email("alice@example.com").fullName("Alice Smith")
                .status(CustomerStatus.ACTIVE).build();

        operator = User.builder()
                .id(5L).email("ops@firstclub.com").name("Ops User")
                .password("hashed").build();

        savedNote = CustomerNote.builder()
                .id(100L).customer(customer).author(operator)
                .noteText("This is a test note.")
                .visibility(CustomerNoteVisibility.INTERNAL_ONLY)
                .createdAt(LocalDateTime.now())
                .build();

        noteResponse = CustomerNoteResponseDTO.builder()
                .id(100L).customerId(10L).authorUserId(5L)
                .authorName("Ops User").noteText("This is a test note.")
                .visibility(CustomerNoteVisibility.INTERNAL_ONLY)
                .build();
    }

    @Nested
    @DisplayName("addNote")
    class AddNoteTests {

        @Test
        @DisplayName("Should add note successfully with valid customer and author")
        void shouldAddNoteSuccessfully() {
            CustomerNoteCreateRequestDTO request = CustomerNoteCreateRequestDTO.builder()
                    .noteText("This is a test note.")
                    .visibility(CustomerNoteVisibility.INTERNAL_ONLY)
                    .build();

            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(customer));
            when(userRepository.findById(5L)).thenReturn(Optional.of(operator));
            when(customerNoteRepository.save(any(CustomerNote.class))).thenReturn(savedNote);
            when(customerNoteMapper.toResponseDTO(savedNote)).thenReturn(noteResponse);

            CustomerNoteResponseDTO result =
                    customerNoteService.addNote(1L, 10L, 5L, request);

            assertThat(result.getNoteText()).isEqualTo("This is a test note.");
            assertThat(result.getAuthorUserId()).isEqualTo(5L);
            verify(customerNoteRepository).save(any(CustomerNote.class));
        }

        @Test
        @DisplayName("Should reject note when author user does not exist")
        void shouldRejectNoteWithInvalidAuthor() {
            CustomerNoteCreateRequestDTO request = CustomerNoteCreateRequestDTO.builder()
                    .noteText("Note.").visibility(CustomerNoteVisibility.INTERNAL_ONLY).build();

            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(customer));
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerNoteService.addNote(1L, 10L, 99L, request))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("NOTE_AUTHOR_NOT_FOUND");
        }

        @Test
        @DisplayName("Should reject note when customer does not belong to the merchant")
        void shouldRejectNoteForWrongMerchant() {
            CustomerNoteCreateRequestDTO request = CustomerNoteCreateRequestDTO.builder()
                    .noteText("Note.").visibility(CustomerNoteVisibility.INTERNAL_ONLY).build();

            // Customer 10 not found under merchant 2
            when(customerRepository.findByMerchantIdAndId(2L, 10L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerNoteService.addNote(2L, 10L, 5L, request))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("CUSTOMER_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("listNotesForCustomer")
    class ListNotesTests {

        @Test
        @DisplayName("Should return notes in descending order of creation")
        void shouldReturnNotesInOrder() {
            when(customerRepository.findByMerchantIdAndId(1L, 10L))
                    .thenReturn(Optional.of(customer));
            when(customerNoteRepository.findByCustomerIdOrderByCreatedAtDesc(10L))
                    .thenReturn(List.of(savedNote));
            when(customerNoteMapper.toResponseDTO(savedNote)).thenReturn(noteResponse);

            List<CustomerNoteResponseDTO> result =
                    customerNoteService.listNotesForCustomer(1L, 10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should enforce tenant isolation when listing notes")
        void shouldEnforceTenantIsolationOnList() {
            when(customerRepository.findByMerchantIdAndId(2L, 10L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerNoteService.listNotesForCustomer(2L, 10L))
                    .isInstanceOf(CustomerException.class)
                    .extracting("errorCode").isEqualTo("CUSTOMER_NOT_FOUND");
        }
    }
}
