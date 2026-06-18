package com.firstclub.membership.repository;

import com.firstclub.membership.entity.BenefitRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BenefitRuleRepository extends JpaRepository<BenefitRule, Long> {

    /** Active rules for a tier, highest priority first — the engine's evaluation input. */
    List<BenefitRule> findByTierIdAndActiveTrueOrderByPriorityDesc(Long tierId);

    /** All rules for a tier (admin listing), highest priority first. */
    List<BenefitRule> findByTierIdOrderByPriorityDesc(Long tierId);
}
