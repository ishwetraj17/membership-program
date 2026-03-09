package com.firstclub.customer.service;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.CustomerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Business logic surface for the customer domain.
 *
 * <p>All operations are merchant-scoped: the {@code merchantId} path variable is
 * included in every method signature so that tenant isolation is enforced at the
 * service layer, not only at the controller.
 */
public interface CustomerService {

    /** Create a new customer under the given merchant. */
    CustomerResponseDTO createCustomer(Long merchantId, CustomerCreateRequestDTO request);

    /** Update mutable fields of an existing customer (patch semantics). */
    CustomerResponseDTO updateCustomer(Long merchantId, Long customerId, CustomerUpdateRequestDTO request);

    /** Fetch a single customer; 404 if not found or belongs to a different merchant. */
    CustomerResponseDTO getCustomerById(Long merchantId, Long customerId);

    /**
     * Page all customers of a merchant.
     * When {@code status} is non-null the result is filtered by that status.
     */
    Page<CustomerResponseDTO> getCustomersByMerchant(Long merchantId, CustomerStatus status, Pageable pageable);

    /** Transition customer to {@code BLOCKED}. */
    CustomerResponseDTO blockCustomer(Long merchantId, Long customerId);

    /** Transition customer to {@code ACTIVE} from {@code INACTIVE} or {@code BLOCKED}. */
    CustomerResponseDTO activateCustomer(Long merchantId, Long customerId);

    /**
     * Verify that {@code customerId} belongs to {@code merchantId}.
     * Throws 404 if the customer doesn't exist or belongs to another merchant.
     */
    void ensureCustomerBelongsToMerchant(Long merchantId, Long customerId);

    /**
     * Verify that the customer is {@code ACTIVE}.
     * Throws {@link com.firstclub.customer.exception.CustomerException} (400) if not.
     */
    void ensureCustomerActive(Long merchantId, Long customerId);
}
