package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.BenefitRuleDTO;
import com.firstclub.membership.dto.BenefitRuleRequest;
import com.firstclub.membership.entity.BenefitRule;
import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.BenefitRuleRepository;
import com.firstclub.membership.repository.MembershipTierRepository;
import com.firstclub.membership.service.BenefitRuleService;
import com.firstclub.membership.service.EntitlementsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BenefitRuleServiceImpl implements BenefitRuleService {

    private final BenefitRuleRepository benefitRuleRepository;
    private final MembershipTierRepository tierRepository;
    private final EntitlementsService entitlementsService;

    @Override
    @Transactional
    public BenefitRuleDTO create(BenefitRuleRequest request) {
        MembershipTier tier = tier(request.getTierId());
        validate(request);

        BenefitRule rule = BenefitRule.builder()
                .tier(tier)
                .benefitType(request.getBenefitType())
                .productCategory(request.getBenefitType().isDiscount() ? request.getProductCategory() : null)
                .minCartValue(request.getMinCartValue())
                .discountPercentage(request.getBenefitType().isDiscount() ? request.getDiscountPercentage() : null)
                .maxDiscountAmount(request.getBenefitType().isDiscount() ? request.getMaxDiscountAmount() : null)
                .priority(request.getPriority() != null ? request.getPriority() : 0)
                .active(request.getActive() == null || request.getActive())
                .build();

        BenefitRule saved = benefitRuleRepository.save(rule);
        entitlementsService.invalidateAll();
        return toDTO(saved);
    }

    @Override
    @Transactional
    public BenefitRuleDTO update(Long id, BenefitRuleRequest request) {
        BenefitRule rule = find(id);
        MembershipTier tier = tier(request.getTierId());
        validate(request);

        rule.setTier(tier);
        rule.setBenefitType(request.getBenefitType());
        rule.setProductCategory(request.getBenefitType().isDiscount() ? request.getProductCategory() : null);
        rule.setMinCartValue(request.getMinCartValue());
        rule.setDiscountPercentage(request.getBenefitType().isDiscount() ? request.getDiscountPercentage() : null);
        rule.setMaxDiscountAmount(request.getBenefitType().isDiscount() ? request.getMaxDiscountAmount() : null);
        rule.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        rule.setActive(request.getActive() == null || request.getActive());

        BenefitRule saved = benefitRuleRepository.save(rule);
        entitlementsService.invalidateAll();
        return toDTO(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        BenefitRule rule = find(id);
        benefitRuleRepository.delete(rule);
        entitlementsService.invalidateAll();
    }

    @Override
    @Transactional(readOnly = true)
    public BenefitRuleDTO get(Long id) {
        return toDTO(find(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BenefitRuleDTO> listByTier(Long tierId) {
        return benefitRuleRepository.findByTierIdOrderByPriorityDesc(tierId).stream().map(this::toDTO).toList();
    }

    private void validate(BenefitRuleRequest request) {
        if (request.getBenefitType() == BenefitType.PERCENTAGE_DISCOUNT
                && request.getDiscountPercentage() == null) {
            throw new MembershipException(
                    "discountPercentage is required for PERCENTAGE_DISCOUNT rules",
                    "INVALID_BENEFIT_RULE");
        }
    }

    private BenefitRule find(Long id) {
        return benefitRuleRepository.findById(id).orElseThrow(() -> new MembershipException(
                "Benefit rule " + id + " not found", "BENEFIT_RULE_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private MembershipTier tier(Long tierId) {
        return tierRepository.findById(tierId).orElseThrow(() -> new MembershipException(
                "Membership tier " + tierId + " not found", "TIER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private BenefitRuleDTO toDTO(BenefitRule rule) {
        return BenefitRuleDTO.builder()
                .id(rule.getId())
                .tierId(rule.getTier().getId())
                .tierName(rule.getTier().getName())
                .benefitType(rule.getBenefitType())
                .productCategory(rule.getProductCategory())
                .minCartValue(rule.getMinCartValue())
                .discountPercentage(rule.getDiscountPercentage())
                .maxDiscountAmount(rule.getMaxDiscountAmount())
                .priority(rule.getPriority())
                .active(rule.isActive())
                .build();
    }
}
