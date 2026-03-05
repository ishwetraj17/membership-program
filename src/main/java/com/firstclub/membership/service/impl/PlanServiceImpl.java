package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.MembershipPlanMapper;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.service.PlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Plan service implementation.
 *
 * Delegated from MembershipServiceImpl to keep that class focused
 * on subscription lifecycle only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class PlanServiceImpl implements PlanService {

    private final MembershipPlanRepository planRepository;
    private final MembershipTierRepository tierRepository;
    private final MembershipPlanMapper planMapper;

    @Override
    @Cacheable("plans")
    public List<MembershipPlanDTO> getAllPlans() {
        List<MembershipPlan> plans = planRepository.findAll();
        Map<Long, List<MembershipPlan>> byTier = plans.stream()
            .collect(Collectors.groupingBy(p -> p.getTier().getId()));
        return plans.stream()
            .map(p -> convertPlanToDTO(p, byTier.get(p.getTier().getId())))
            .collect(Collectors.toList());
    }

    @Override
    @Cacheable("plans")
    public List<MembershipPlanDTO> getActivePlans() {
        List<MembershipPlan> plans = planRepository.findByIsActiveTrue();
        Map<Long, List<MembershipPlan>> byTier = plans.stream()
            .collect(Collectors.groupingBy(p -> p.getTier().getId()));
        return plans.stream()
            .map(p -> convertPlanToDTO(p, byTier.get(p.getTier().getId())))
            .collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "plansByTier", key = "#tierName.toUpperCase()")
    public List<MembershipPlanDTO> getPlansByTier(String tierName) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
            .orElseThrow(() -> MembershipException.tierNotFound(tierName));
        List<MembershipPlan> plans = planRepository.findByTierAndIsActiveTrue(tier);
        return plans.stream()
            .map(p -> convertPlanToDTO(p, plans))
            .collect(Collectors.toList());
    }

    @Override
    public List<MembershipPlanDTO> getPlansByTierId(Long tierId) {
        MembershipTier tier = tierRepository.findById(tierId)
            .orElseThrow(() -> MembershipException.tierNotFound("ID: " + tierId));
        List<MembershipPlan> plans = planRepository.findByTierAndIsActiveTrue(tier);
        return plans.stream()
            .map(p -> convertPlanToDTO(p, plans))
            .collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "plansByType", key = "#type.name()")
    public List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type) {
        List<MembershipPlan> allActive = planRepository.findByIsActiveTrue();
        Map<Long, List<MembershipPlan>> byTier = allActive.stream()
            .collect(Collectors.groupingBy(p -> p.getTier().getId()));
        return allActive.stream()
            .filter(p -> p.getType() == type)
            .map(p -> convertPlanToDTO(p, byTier.get(p.getTier().getId())))
            .collect(Collectors.toList());
    }

    @Override
    public Optional<MembershipPlanDTO> getPlanById(Long id) {
        return planRepository.findById(id).map(plan -> {
            List<MembershipPlan> sameTier = planRepository.findByTierAndIsActiveTrue(plan.getTier());
            return convertPlanToDTO(plan, sameTier);
        });
    }

    private MembershipPlanDTO convertPlanToDTO(MembershipPlan plan, List<MembershipPlan> sameTierPlans) {
        MembershipPlanDTO dto = planMapper.toDTO(plan);
        dto.setMonthlyPrice(plan.getMonthlyPrice());

        BigDecimal savings = BigDecimal.ZERO;
        if (plan.getType() != MembershipPlan.PlanType.MONTHLY && sameTierPlans != null) {
            savings = sameTierPlans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY && p.getIsActive())
                .findFirst()
                .map(monthly -> plan.calculateSavings(monthly.getPrice()))
                .orElse(BigDecimal.ZERO);
        }
        dto.setSavings(savings);
        return dto;
    }
}
