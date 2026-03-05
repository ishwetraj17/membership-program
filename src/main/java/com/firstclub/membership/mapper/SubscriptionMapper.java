package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.TierBenefitsDTO;
import com.firstclub.membership.entity.Subscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for Subscription -> SubscriptionDTO.
 *
 * Dot-notation source paths traverse the object graph automatically.
 * Tier benefit fields are nested inside TierBenefitsDTO.
 * isActive and daysRemaining are computed from entity methods.
 */
@Mapper
public interface SubscriptionMapper {

    @Mapping(target = "userId",    source = "user.id")
    @Mapping(target = "userName",  source = "user.name")
    @Mapping(target = "userEmail", source = "user.email")

    @Mapping(target = "planId",    source = "plan.id")
    @Mapping(target = "planName",  source = "plan.name")
    @Mapping(target = "planType",  expression = "java(subscription.getPlan().getType().name())")

    @Mapping(target = "tier",      source = "plan.tier.name")
    @Mapping(target = "tierLevel", source = "plan.tier.level")

    // Nested TierBenefitsDTO populated from plan.tier fields
    @Mapping(target = "tierBenefits.discountPercentage", source = "plan.tier.discountPercentage")
    @Mapping(target = "tierBenefits.freeDelivery",       source = "plan.tier.freeDelivery")
    @Mapping(target = "tierBenefits.exclusiveDeals",     source = "plan.tier.exclusiveDeals")
    @Mapping(target = "tierBenefits.earlyAccess",        source = "plan.tier.earlyAccess")
    @Mapping(target = "tierBenefits.prioritySupport",    source = "plan.tier.prioritySupport")
    @Mapping(target = "tierBenefits.maxCouponsPerMonth", source = "plan.tier.maxCouponsPerMonth")
    @Mapping(target = "tierBenefits.deliveryDays",       source = "plan.tier.deliveryDays")
    @Mapping(target = "tierBenefits.additionalBenefits", source = "plan.tier.additionalBenefits")

    // Computed / derived fields
    @Mapping(target = "daysRemaining", expression = "java(subscription.getDaysRemaining())")
    @Mapping(target = "isActive",      expression = "java(subscription.isActive())")
    SubscriptionDTO toDTO(Subscription subscription);

    /**
     * Maps a MembershipTier directly to a TierBenefitsDTO (reused internally).
     */
    @Mapping(target = "discountPercentage", source = "discountPercentage")
    @Mapping(target = "freeDelivery",       source = "freeDelivery")
    @Mapping(target = "exclusiveDeals",     source = "exclusiveDeals")
    @Mapping(target = "earlyAccess",        source = "earlyAccess")
    @Mapping(target = "prioritySupport",    source = "prioritySupport")
    @Mapping(target = "maxCouponsPerMonth", source = "maxCouponsPerMonth")
    @Mapping(target = "deliveryDays",       source = "deliveryDays")
    @Mapping(target = "additionalBenefits", source = "additionalBenefits")
    TierBenefitsDTO tierToDTO(com.firstclub.membership.entity.MembershipTier tier);
}
