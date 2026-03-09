package com.firstclub.membership.repository;

import com.firstclub.membership.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for User entity operations.
 * All queries automatically exclude soft-deleted users (isDeleted = false).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailAndIsDeletedFalse(String email);

    boolean existsByEmailAndIsDeletedFalse(String email);

    Optional<User> findByIdAndIsDeletedFalse(Long id);

    Page<User> findByIsDeletedFalse(Pageable pageable);

    /**
     * Ownership check used by SecurityService: verifies that the given user ID
     * belongs to the account with {@code email} and has not been soft-deleted.
     * Single lightweight query — no entity loading.
     */
    boolean existsByIdAndEmailIgnoreCaseAndIsDeletedFalse(Long id, String email);

    /**
     * Returns only the numeric user ID for the given email.
     * Used by SecurityAuditContext to avoid loading the full entity/DTO
     * on every write operation.
     */
    @Query("SELECT u.id FROM User u WHERE u.email = :email AND u.isDeleted = false")
    Optional<Long> findIdByEmailAndIsDeletedFalse(@Param("email") String email);
}