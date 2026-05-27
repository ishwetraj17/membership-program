package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.TierDTO;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipServiceImpl implements MembershipService {

    private final MembershipTierRepository tierRepository;

    @Override
    @Cacheable("tiers")
    public List<TierDTO> getAllTiers() {
        return tierRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<TierDTO> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase()).map(this::convertToDTO);
    }

    @Override
    public Optional<TierDTO> getTierById(Long id) {
        return tierRepository.findById(id).map(this::convertToDTO);
    }

    private TierDTO convertToDTO(MembershipTier tier) {
        List<String> benefits = new ArrayList<>();
        if (Boolean.TRUE.equals(tier.getFreeDelivery()))    benefits.add("Free delivery");
        if (Boolean.TRUE.equals(tier.getExclusiveDeals()))  benefits.add("Exclusive deals");
        if (Boolean.TRUE.equals(tier.getEarlyAccess()))     benefits.add("Early sale access");
        if (Boolean.TRUE.equals(tier.getPrioritySupport())) benefits.add("Priority support");
        benefits.add(tier.getMaxCouponsPerMonth() + " coupons/month");
        benefits.add(tier.getDeliveryDays() + "-day delivery SLA");

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
                .build();
    }
}
