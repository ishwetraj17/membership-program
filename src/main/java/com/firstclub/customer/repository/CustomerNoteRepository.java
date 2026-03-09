package com.firstclub.customer.repository;

import com.firstclub.customer.entity.CustomerNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link CustomerNote}.
 */
@Repository
public interface CustomerNoteRepository extends JpaRepository<CustomerNote, Long> {

    /** All notes for a customer, newest first. */
    List<CustomerNote> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
