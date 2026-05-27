package com.firstclub.membership.repository;

import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, Long> {
    List<MembershipPlan> findByIsActiveTrue();
    List<MembershipPlan> findByTierAndIsActiveTrue(MembershipTier tier);
    List<MembershipPlan> findByTypeAndIsActiveTrue(MembershipPlan.PlanType type);
}