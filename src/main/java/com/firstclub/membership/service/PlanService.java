package com.firstclub.membership.service;

import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;

import java.util.List;
import java.util.Optional;

/**
 * Service for membership plan operations.
 *
 * Separated from MembershipService to keep responsibilities focused.
 * Plan data rarely changes — reads are Cacheable.
 */
public interface PlanService {

    List<MembershipPlanDTO> getAllPlans();

    List<MembershipPlanDTO> getActivePlans();

    List<MembershipPlanDTO> getPlansByTier(String tierName);

    List<MembershipPlanDTO> getPlansByTierId(Long tierId);

    List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type);

    Optional<MembershipPlanDTO> getPlanById(Long id);
}
