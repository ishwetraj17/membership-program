package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.MembershipTierDTO;
import com.firstclub.membership.entity.MembershipTier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for MembershipTier <-> MembershipTierDTO.
 * Stops the controller from returning entity objects directly.
 */
@Mapper
public interface MembershipTierMapper {

    /**
     * Entity → DTO. Excludes the plans collection (lazy-loaded, not needed in responses).
     */
    @Mapping(target = "id",                 source = "id")
    @Mapping(target = "name",               source = "name")
    @Mapping(target = "description",        source = "description")
    @Mapping(target = "level",              source = "level")
    @Mapping(target = "discountPercentage", source = "discountPercentage")
    @Mapping(target = "freeDelivery",       source = "freeDelivery")
    @Mapping(target = "exclusiveDeals",     source = "exclusiveDeals")
    @Mapping(target = "earlyAccess",        source = "earlyAccess")
    @Mapping(target = "prioritySupport",    source = "prioritySupport")
    @Mapping(target = "maxCouponsPerMonth", source = "maxCouponsPerMonth")
    @Mapping(target = "deliveryDays",       source = "deliveryDays")
    @Mapping(target = "additionalBenefits", source = "additionalBenefits")
    MembershipTierDTO toDTO(MembershipTier tier);
}
