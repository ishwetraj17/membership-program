package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for MembershipPlan operations
 * 
 * Handles queries for membership plans with filtering by tier and type.
 * 
 * Implemented by Shwet Raj
 */
@Repository
public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {
    
    /**
     * Find all active plans
     * 
     * @return List of active plans
     */
    List<MembershipPlan> findByIsActiveTrue();
    
    /**
     * Find active plans by tier
     * 
     * @param tier membership tier
     * @return List of active plans for the tier
     */
    List<MembershipPlan> findByTierAndIsActiveTrue(MembershipTier tier);
    
    /**
     * Find active plans by type (MONTHLY, QUARTERLY, YEARLY)
     * 
     * @param type plan type
     * @return List of active plans of the specified type
     */
    List<MembershipPlan> findByTypeAndIsActiveTrue(MembershipPlan.PlanType type);
}