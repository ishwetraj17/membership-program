package com.firstclub.membership.repository;

import com.firstclub.membership.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for User entity operations
 * 
 * Standard JPA repository with custom methods for email lookup.
 * Email uniqueness is handled at database level.
 * 
 * Implemented by Shwet Raj
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * Find user by email address
     * 
     * @param email user's email
     * @return Optional containing user if found
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Check if email already exists
     * 
     * Used for validation during user registration.
     * 
     * @param email email to check
     * @return true if email exists
     */
    boolean existsByEmail(String email);
}