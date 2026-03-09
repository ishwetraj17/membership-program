package com.firstclub.customer.service;

import com.firstclub.customer.dto.CustomerNoteCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteResponseDTO;

import java.util.List;

/**
 * Business logic surface for customer notes.
 *
 * <p>Notes are immutable once written.  The service enforces tenant isolation:
 * the customer must belong to the supplied merchantId path variable.
 */
public interface CustomerNoteService {

    /**
     * Add an immutable note to a customer, authored by the given platform user.
     *
     * @param merchantId  owning merchant (tenant boundary)
     * @param customerId  target customer
     * @param authorUserId platform User (operator/admin) writing the note
     * @param request      note content and visibility
     */
    CustomerNoteResponseDTO addNote(Long merchantId, Long customerId,
                                    Long authorUserId,
                                    CustomerNoteCreateRequestDTO request);

    /**
     * List all notes for a customer, newest first.
     *
     * @param merchantId owning merchant (tenant boundary, validated)
     * @param customerId target customer
     */
    List<CustomerNoteResponseDTO> listNotesForCustomer(Long merchantId, Long customerId);
}
