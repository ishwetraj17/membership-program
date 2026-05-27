package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for MembershipTier operations
 * 
 * Handles CRUD operations for membership tiers (Silver, Gold, Platinum).
 * 
 * Implemented by Shwet Raj
 */
@Repository
public interface MembershipTierRepository extends JpaRepository<MembershipTier, Long> {
    
    /**
     * Find tier by name (SILVER, GOLD, PLATINUM)
     * 
     * @param name tier name
     * @return Optional containing tier if found
     */
    Optional<MembershipTier> findByName(String name);
}