package com.firstclub.customer.service.impl;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.mapper.CustomerMapper;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.customer.service.CustomerService;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link CustomerService}.
 *
 * Business rules:
 * - Customer belongs to exactly one merchant; merchantId is the tenant boundary.
 * - Email is normalised to lower-case before persistence and uniqueness checks.
 * - Same email is permitted across different merchants (per-merchant uniqueness only).
 * - Duplicate email within the same merchant is rejected with CONFLICT.
 * - externalCustomerId, when provided, must be unique within the merchant.
 * - Status transitions: ACTIVE ↔ INACTIVE ↔ BLOCKED (no dead-ends except BLOCKED→ACTIVE allowed).
 * - BLOCKED customers cannot be used for subscriptions/payments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final CustomerMapper customerMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CustomerResponseDTO createCustomer(Long merchantId, CustomerCreateRequestDTO request) {
        log.info("Creating customer for merchantId={}, email={}", merchantId, request.getEmail());

        MerchantAccount merchant = loadMerchantOrThrow(merchantId);

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (customerRepository.existsByMerchantIdAndEmailIgnoreCase(merchantId, normalizedEmail)) {
            throw CustomerException.duplicateEmail(merchantId, normalizedEmail);
        }

        if (request.getExternalCustomerId() != null && !request.getExternalCustomerId().isBlank()) {
            if (customerRepository.existsByMerchantIdAndExternalCustomerId(
                    merchantId, request.getExternalCustomerId())) {
                throw CustomerException.duplicateExternalId(merchantId, request.getExternalCustomerId());
            }
        }

        Customer customer = customerMapper.toEntity(request);
        customer.setEmail(normalizedEmail);
        customer.setMerchant(merchant);
        customer.setStatus(CustomerStatus.ACTIVE);

        Customer saved = customerRepository.save(customer);
        log.info("Customer created: id={}, merchantId={}", saved.getId(), merchantId);
        return customerMapper.toResponseDTO(saved);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CustomerResponseDTO updateCustomer(Long merchantId, Long customerId,
                                              CustomerUpdateRequestDTO request) {
        log.info("Updating customer id={} for merchantId={}", customerId, merchantId);

        Customer customer = loadOrThrow(merchantId, customerId);

        // Validate new email uniqueness if email is being changed
        if (request.getEmail() != null) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(customer.getEmail())) {
                if (customerRepository.existsByMerchantIdAndEmailIgnoreCase(merchantId, newEmail)) {
                    throw CustomerException.duplicateEmail(merchantId, newEmail);
                }
            }
            request.setEmail(newEmail);
        }

        // Validate new externalCustomerId uniqueness if being changed
        if (request.getExternalCustomerId() != null && !request.getExternalCustomerId().isBlank()) {
            if (!request.getExternalCustomerId().equals(customer.getExternalCustomerId())) {
                if (customerRepository.existsByMerchantIdAndExternalCustomerId(
                        merchantId, request.getExternalCustomerId())) {
                    throw CustomerException.duplicateExternalId(merchantId, request.getExternalCustomerId());
                }
            }
        }

        customerMapper.updateEntityFromDTO(request, customer);
        customerRepository.save(customer);

        return customerMapper.toResponseDTO(customer);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CustomerResponseDTO getCustomerById(Long merchantId, Long customerId) {
        return customerMapper.toResponseDTO(loadOrThrow(merchantId, customerId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CustomerResponseDTO> getCustomersByMerchant(Long merchantId,
                                                             CustomerStatus status,
                                                             Pageable pageable) {
        if (status != null) {
            return customerRepository
                    .findAllByMerchantIdAndStatus(merchantId, status, pageable)
                    .map(customerMapper::toResponseDTO);
        }
        return customerRepository
                .findAllByMerchantId(merchantId, pageable)
                .map(customerMapper::toResponseDTO);
    }

    // ── Status transitions ────────────────────────────────────────────────────

    @Override
    @Transactional
    public CustomerResponseDTO blockCustomer(Long merchantId, Long customerId) {
        log.info("Blocking customer id={} for merchantId={}", customerId, merchantId);

        Customer customer = loadOrThrow(merchantId, customerId);
        if (customer.getStatus() == CustomerStatus.BLOCKED) {
            return customerMapper.toResponseDTO(customer); // idempotent
        }
        customer.setStatus(CustomerStatus.BLOCKED);
        customerRepository.save(customer);
        return customerMapper.toResponseDTO(customer);
    }

    @Override
    @Transactional
    public CustomerResponseDTO activateCustomer(Long merchantId, Long customerId) {
        log.info("Activating customer id={} for merchantId={}", customerId, merchantId);

        Customer customer = loadOrThrow(merchantId, customerId);
        if (customer.getStatus() == CustomerStatus.ACTIVE) {
            return customerMapper.toResponseDTO(customer); // idempotent
        }
        customer.setStatus(CustomerStatus.ACTIVE);
        customerRepository.save(customer);
        return customerMapper.toResponseDTO(customer);
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public void ensureCustomerBelongsToMerchant(Long merchantId, Long customerId) {
        loadOrThrow(merchantId, customerId);
    }

    @Override
    @Transactional(readOnly = true)
    public void ensureCustomerActive(Long merchantId, Long customerId) {
        Customer customer = loadOrThrow(merchantId, customerId);
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw CustomerException.customerNotActive(customerId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Customer loadOrThrow(Long merchantId, Long customerId) {
        return customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));
    }

    private MerchantAccount loadMerchantOrThrow(Long merchantId) {
        return merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
    }
}
