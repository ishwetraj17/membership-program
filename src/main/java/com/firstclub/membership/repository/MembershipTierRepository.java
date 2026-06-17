package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MembershipTierRepository extends JpaRepository<MembershipTier, Long> {
    
    /**
     * Find tier by name (SILVER, GOLD, PLATINUM)
     * 
     * @param name tier name
     * @return Optional containing tier if found
     */
    Optional<MembershipTier> findByName(String name);
}