package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {

    // JOIN FETCH the tier on every list query — convertPlanToDTO reads ~10 tier fields,
    // so without the fetch each plan would trigger a lazy load (N+1).
    @Query("SELECT p FROM MembershipPlan p JOIN FETCH p.tier WHERE p.isActive = true")
    List<MembershipPlan> findByIsActiveTrue();

    @Query("SELECT p FROM MembershipPlan p JOIN FETCH p.tier WHERE p.tier = :tier AND p.isActive = true")
    List<MembershipPlan> findByTierAndIsActiveTrue(@Param("tier") MembershipTier tier);

    @Query("SELECT p FROM MembershipPlan p JOIN FETCH p.tier WHERE p.type = :type AND p.isActive = true")
    List<MembershipPlan> findByTypeAndIsActiveTrue(@Param("type") MembershipPlan.PlanType type);
}