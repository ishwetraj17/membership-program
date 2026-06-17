package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.BenefitDTO;
import com.firstclub.membership.dto.TierDTO;
import com.firstclub.membership.entity.Benefit;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.TierBenefit;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.BenefitRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.repository.TierBenefitRepository;
import com.firstclub.membership.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipServiceImpl implements MembershipService {

    private final MembershipTierRepository tierRepository;
    private final BenefitRepository benefitRepository;
    private final TierBenefitRepository tierBenefitRepository;

    @Override
    @Cacheable("tiers")
    public List<TierDTO> getAllTiers() {
        // Immutable — this list is cached and shared across callers.
        return tierRepository.findAll().stream()
                .map(this::convertToDTO)
                .toList();
    }

    @Override
    public Optional<TierDTO> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase()).map(this::convertToDTO);
    }

    @Override
    public Optional<TierDTO> getTierById(Long id) {
        return tierRepository.findById(id).map(this::convertToDTO);
    }

    @Override
    public List<BenefitDTO> getBenefitCatalog() {
        return benefitRepository.findAll().stream()
                .map(b -> BenefitDTO.builder()
                        .code(b.getCode()).name(b.getName())
                        .description(b.getDescription()).category(b.getCategory())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public BenefitDTO createBenefit(BenefitDTO request) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw new MembershipException("Benefit code is required", "INVALID_BENEFIT");
        }
        String code = request.getCode().toUpperCase();
        if (benefitRepository.existsByCode(code)) {
            throw new MembershipException("Benefit '" + code + "' already exists", "BENEFIT_EXISTS",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
        Benefit saved = benefitRepository.save(Benefit.builder()
                .code(code)
                .name(request.getName())
                .description(request.getDescription())
                .category(request.getCategory())
                .build());
        return BenefitDTO.builder()
                .code(saved.getCode()).name(saved.getName())
                .description(saved.getDescription()).category(saved.getCategory())
                .build();
    }

    @Override
    @Transactional
    @CacheEvict(value = "tiers", allEntries = true)
    public TierDTO removeBenefitFromTier(String tierName, String benefitCode) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
                .orElseThrow(() -> MembershipException.tierNotFound(tierName));
        TierBenefit link = tierBenefitRepository.findByTierAndBenefit_Code(tier, benefitCode.toUpperCase())
                .orElseThrow(() -> new MembershipException(
                        "Benefit '" + benefitCode + "' is not attached to tier " + tierName,
                        "BENEFIT_NOT_ON_TIER", org.springframework.http.HttpStatus.NOT_FOUND));
        tierBenefitRepository.delete(link);
        return convertToDTO(tier);
    }

    @Override
    @Transactional
    @CacheEvict(value = "tiers", allEntries = true)
    public TierDTO assignBenefitToTier(String tierName, String benefitCode, String value) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
                .orElseThrow(() -> MembershipException.tierNotFound(tierName));
        Benefit benefit = benefitRepository.findByCode(benefitCode.toUpperCase())
                .orElseThrow(() -> new MembershipException(
                        "Unknown benefit code: " + benefitCode, "BENEFIT_NOT_FOUND",
                        org.springframework.http.HttpStatus.NOT_FOUND));

        TierBenefit link = tierBenefitRepository.findByTierAndBenefit_Code(tier, benefit.getCode())
                .orElseGet(() -> TierBenefit.builder().tier(tier).benefit(benefit).build());
        link.setValue(value);
        tierBenefitRepository.save(link);

        return convertToDTO(tier);
    }

    private TierDTO convertToDTO(MembershipTier tier) {
        List<String> benefits = new ArrayList<>();
        if (Boolean.TRUE.equals(tier.getFreeDelivery()))    benefits.add("Free delivery");
        if (Boolean.TRUE.equals(tier.getExclusiveDeals()))  benefits.add("Exclusive deals");
        if (Boolean.TRUE.equals(tier.getEarlyAccess()))     benefits.add("Early sale access");
        if (Boolean.TRUE.equals(tier.getPrioritySupport())) benefits.add("Priority support");
        benefits.add(tier.getMaxCouponsPerMonth() + " coupons/month");
        benefits.add(tier.getDeliveryDays() + "-day delivery SLA");

        List<BenefitDTO> configuredBenefits = tierBenefitRepository.findByTierIdFetchBenefit(tier.getId()).stream()
                .map(tb -> BenefitDTO.builder()
                        .code(tb.getBenefit().getCode())
                        .name(tb.getBenefit().getName())
                        .description(tb.getBenefit().getDescription())
                        .category(tb.getBenefit().getCategory())
                        .value(tb.getValue())
                        .build())
                .toList();

        return TierDTO.builder()
                .id(tier.getId())
                .name(tier.getName())
                .description(tier.getDescription())
                .level(tier.getLevel())
                .discountPercentage(tier.getDiscountPercentage())
                .freeDelivery(tier.getFreeDelivery())
                .exclusiveDeals(tier.getExclusiveDeals())
                .earlyAccess(tier.getEarlyAccess())
                .prioritySupport(tier.getPrioritySupport())
                .maxCouponsPerMonth(tier.getMaxCouponsPerMonth())
                .deliveryDays(tier.getDeliveryDays())
                .additionalBenefits(tier.getAdditionalBenefits())
                .benefits(benefits)
                .configuredBenefits(configuredBenefits)
                .build();
    }
}
