package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for MembershipPlan -> MembershipPlanDTO.
 *
 * monthlyPrice and savings are computed values (require DB queries or arithmetic)
 * so they are left unset here and filled in by MembershipServiceImpl after mapping.
 */
@Mapper
public interface MembershipPlanMapper {

    @Mapping(target = "tier",               source = "tier.name")
    @Mapping(target = "tierLevel",          source = "tier.level")
    @Mapping(target = "discountPercentage", source = "tier.discountPercentage")
    @Mapping(target = "freeDelivery",       source = "tier.freeDelivery")
    @Mapping(target = "exclusiveDeals",     source = "tier.exclusiveDeals")
    @Mapping(target = "earlyAccess",        source = "tier.earlyAccess")
    @Mapping(target = "prioritySupport",    source = "tier.prioritySupport")
    @Mapping(target = "maxCouponsPerMonth", source = "tier.maxCouponsPerMonth")
    @Mapping(target = "deliveryDays",       source = "tier.deliveryDays")
    @Mapping(target = "additionalBenefits", source = "tier.additionalBenefits")
    @Mapping(target = "monthlyPrice",       ignore = true)
    @Mapping(target = "savings",            ignore = true)
    MembershipPlanDTO toDTO(MembershipPlan plan);
}
