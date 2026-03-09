package com.firstclub.customer.service.impl;

import com.firstclub.customer.dto.CustomerNoteCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerNote;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.mapper.CustomerNoteMapper;
import com.firstclub.customer.repository.CustomerNoteRepository;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.customer.service.CustomerNoteService;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of {@link CustomerNoteService}.
 *
 * Business rules:
 * - Note author must be a valid platform User (operator/admin).
 * - Customer must belong to the given merchantId (tenant isolation).
 * - Notes are immutable after creation — no update/delete operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerNoteServiceImpl implements CustomerNoteService {

    private final CustomerNoteRepository customerNoteRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final CustomerNoteMapper customerNoteMapper;

    @Override
    @Transactional
    public CustomerNoteResponseDTO addNote(Long merchantId, Long customerId,
                                           Long authorUserId,
                                           CustomerNoteCreateRequestDTO request) {
        log.info("Adding note to customer id={} by user id={}", customerId, authorUserId);

        Customer customer = loadCustomerOrThrow(merchantId, customerId);
        User author = userRepository.findById(authorUserId)
                .orElseThrow(() -> CustomerException.authorNotFound(authorUserId));

        CustomerNote note = CustomerNote.builder()
                .customer(customer)
                .author(author)
                .noteText(request.getNoteText())
                .visibility(request.getVisibility())
                .build();

        CustomerNote saved = customerNoteRepository.save(note);
        log.info("Note id={} created for customer id={}", saved.getId(), customerId);
        return customerNoteMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerNoteResponseDTO> listNotesForCustomer(Long merchantId, Long customerId) {
        // Validate tenant isolation
        loadCustomerOrThrow(merchantId, customerId);

        return customerNoteRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(customerNoteMapper::toResponseDTO)
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Customer loadCustomerOrThrow(Long merchantId, Long customerId) {
        return customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));
    }
}
