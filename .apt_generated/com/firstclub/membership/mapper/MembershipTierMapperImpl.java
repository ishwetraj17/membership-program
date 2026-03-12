package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.MembershipTierDTO;
import com.firstclub.membership.entity.MembershipTier;
import javax.annotation.processing.Generated;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-12T15:43:33+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
public class MembershipTierMapperImpl implements MembershipTierMapper {

    @Override
    public MembershipTierDTO toDTO(MembershipTier tier) {
        if ( tier == null ) {
            return null;
        }

        MembershipTierDTO.MembershipTierDTOBuilder membershipTierDTO = MembershipTierDTO.builder();

        membershipTierDTO.additionalBenefits( tier.getAdditionalBenefits() );
        membershipTierDTO.deliveryDays( tier.getDeliveryDays() );
        membershipTierDTO.description( tier.getDescription() );
        membershipTierDTO.discountPercentage( tier.getDiscountPercentage() );
        membershipTierDTO.earlyAccess( tier.getEarlyAccess() );
        membershipTierDTO.exclusiveDeals( tier.getExclusiveDeals() );
        membershipTierDTO.freeDelivery( tier.getFreeDelivery() );
        membershipTierDTO.id( tier.getId() );
        membershipTierDTO.level( tier.getLevel() );
        membershipTierDTO.maxCouponsPerMonth( tier.getMaxCouponsPerMonth() );
        membershipTierDTO.name( tier.getName() );
        membershipTierDTO.prioritySupport( tier.getPrioritySupport() );

        return membershipTierDTO.build();
    }
}
