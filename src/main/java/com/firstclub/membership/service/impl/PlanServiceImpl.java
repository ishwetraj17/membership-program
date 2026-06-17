package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.CreatePlanRequest;
import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.service.AuditService;
import com.firstclub.membership.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlanServiceImpl implements PlanService {

    private final MembershipTierRepository tierRepository;
    private final MembershipPlanRepository planRepository;
    private final AuditService auditService;

    @Override
    public List<MembershipPlanDTO> getAllPlans() {
        return convertPlans(planRepository.findAll());
    }

    @Override
    @Cacheable("plans")
    public List<MembershipPlanDTO> getActivePlans() {
        return convertPlans(planRepository.findByIsActiveTrue());
    }

    @Override
    public List<MembershipPlanDTO> getPlansByTier(String tierName) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
                .orElseThrow(() -> MembershipException.tierNotFound(tierName));
        return convertPlans(planRepository.findByTierAndIsActiveTrue(tier));
    }

    @Override
    public List<MembershipPlanDTO> getPlansByTierId(Long tierId) {
        MembershipTier tier = tierRepository.findById(tierId)
                .orElseThrow(() -> MembershipException.tierNotFound("ID: " + tierId));
        return convertPlans(planRepository.findByTierAndIsActiveTrue(tier));
    }

    @Override
    public List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type) {
        List<MembershipPlan> filtered = planRepository.findByTypeAndIsActiveTrue(type);
        // Savings are computed relative to the monthly baseline for each tier.
        // When the result set contains no MONTHLY plans (e.g. type=YEARLY), fetch
        // monthly plans separately so the price map is populated correctly.
        Map<Long, BigDecimal> monthlyPriceByTierId = buildMonthlyPriceMap(filtered);
        if (monthlyPriceByTierId.isEmpty() && type != MembershipPlan.PlanType.MONTHLY) {
            monthlyPriceByTierId = buildMonthlyPriceMap(
                    planRepository.findByTypeAndIsActiveTrue(MembershipPlan.PlanType.MONTHLY));
        }
        final Map<Long, BigDecimal> priceMap = monthlyPriceByTierId;
        return filtered.stream()
                .map(p -> convertPlanToDTO(p, priceMap))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<MembershipPlanDTO> getPlanById(Long id) {
        return planRepository.findById(id).map(plan -> {
            // Fetch all tier plans to accurately compute savings vs. monthly baseline
            List<MembershipPlan> tierPlans = planRepository.findByTierAndIsActiveTrue(plan.getTier());
            return convertPlanToDTO(plan, buildMonthlyPriceMap(tierPlans));
        });
    }

    // ─── Admin ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    @CacheEvict(value = "plans", allEntries = true)
    public MembershipPlanDTO createPlan(CreatePlanRequest request) {
        MembershipTier tier = tierRepository.findByName(request.getTierName().toUpperCase())
                .orElseThrow(() -> MembershipException.tierNotFound(request.getTierName()));
        MembershipPlan saved = planRepository.save(MembershipPlan.builder()
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .price(request.getPrice())
                .durationInMonths(request.getDurationInMonths())
                .tier(tier)
                .isActive(true)
                .build());
        auditService.record("PLAN_CREATED", saved.getName());
        return convertPlanToDTO(saved, buildMonthlyPriceMap(
                planRepository.findByTierAndIsActiveTrue(tier)));
    }

    @Override
    @Transactional
    @CacheEvict(value = "plans", allEntries = true)
    public MembershipPlanDTO deactivatePlan(Long id) {
        MembershipPlan plan = planRepository.findById(id)
                .orElseThrow(() -> MembershipException.planNotFound(id));
        plan.setIsActive(false);
        MembershipPlan saved = planRepository.save(plan);
        return convertPlanToDTO(saved, buildMonthlyPriceMap(
                planRepository.findByTierAndIsActiveTrue(plan.getTier())));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<MembershipPlanDTO> convertPlans(List<MembershipPlan> plans) {
        Map<Long, BigDecimal> monthlyPriceByTierId = buildMonthlyPriceMap(plans);
        // Immutable — getActivePlans() is @Cacheable, so the returned list is shared across
        // callers; an unmodifiable copy prevents one caller from mutating the cache entry.
        return plans.stream()
                .map(p -> convertPlanToDTO(p, monthlyPriceByTierId))
                .toList();
    }

    private Map<Long, BigDecimal> buildMonthlyPriceMap(List<MembershipPlan> plans) {
        return plans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY)
                .collect(Collectors.toMap(
                        p -> p.getTier().getId(),
                        MembershipPlan::getPrice,
                        (a, b) -> a));
    }

    MembershipPlanDTO convertPlanToDTO(MembershipPlan plan, Map<Long, BigDecimal> monthlyPriceByTierId) {
        BigDecimal savings = BigDecimal.ZERO;
        if (plan.getType() != MembershipPlan.PlanType.MONTHLY) {
            BigDecimal monthlyPrice = monthlyPriceByTierId.get(plan.getTier().getId());
            if (monthlyPrice != null) {
                savings = plan.calculateSavings(monthlyPrice);
            }
        }
        return MembershipPlanDTO.builder()
                .id(plan.getId())
                .name(plan.getName())
                .description(plan.getDescription())
                .type(plan.getType())
                .price(plan.getPrice())
                .durationInMonths(plan.getDurationInMonths())
                .tier(plan.getTier().getName())
                .tierLevel(plan.getTier().getLevel())
                .discountPercentage(plan.getTier().getDiscountPercentage())
                .freeDelivery(plan.getTier().getFreeDelivery())
                .exclusiveDeals(plan.getTier().getExclusiveDeals())
                .earlyAccess(plan.getTier().getEarlyAccess())
                .prioritySupport(plan.getTier().getPrioritySupport())
                .maxCouponsPerMonth(plan.getTier().getMaxCouponsPerMonth())
                .deliveryDays(plan.getTier().getDeliveryDays())
                .additionalBenefits(plan.getTier().getAdditionalBenefits())
                .monthlyPrice(plan.getMonthlyPrice())
                .savings(savings)
                .isActive(plan.getIsActive())
                .build();
    }
}
