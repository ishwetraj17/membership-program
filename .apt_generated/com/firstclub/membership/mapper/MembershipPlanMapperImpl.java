package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.MembershipPlanDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-09T09:53:54+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
public class MembershipPlanMapperImpl implements MembershipPlanMapper {

    @Override
    public MembershipPlanDTO toDTO(MembershipPlan plan) {
        if ( plan == null ) {
            return null;
        }

        MembershipPlanDTO.MembershipPlanDTOBuilder membershipPlanDTO = MembershipPlanDTO.builder();

        membershipPlanDTO.tier( planTierName( plan ) );
        membershipPlanDTO.tierLevel( planTierLevel( plan ) );
        membershipPlanDTO.discountPercentage( planTierDiscountPercentage( plan ) );
        membershipPlanDTO.freeDelivery( planTierFreeDelivery( plan ) );
        membershipPlanDTO.exclusiveDeals( planTierExclusiveDeals( plan ) );
        membershipPlanDTO.earlyAccess( planTierEarlyAccess( plan ) );
        membershipPlanDTO.prioritySupport( planTierPrioritySupport( plan ) );
        membershipPlanDTO.maxCouponsPerMonth( planTierMaxCouponsPerMonth( plan ) );
        membershipPlanDTO.deliveryDays( planTierDeliveryDays( plan ) );
        membershipPlanDTO.additionalBenefits( planTierAdditionalBenefits( plan ) );
        membershipPlanDTO.description( plan.getDescription() );
        membershipPlanDTO.durationInMonths( plan.getDurationInMonths() );
        membershipPlanDTO.id( plan.getId() );
        membershipPlanDTO.isActive( plan.getIsActive() );
        membershipPlanDTO.name( plan.getName() );
        membershipPlanDTO.price( plan.getPrice() );
        membershipPlanDTO.type( plan.getType() );

        return membershipPlanDTO.build();
    }

    private String planTierName(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        String name = tier.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }

    private Integer planTierLevel(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Integer level = tier.getLevel();
        if ( level == null ) {
            return null;
        }
        return level;
    }

    private BigDecimal planTierDiscountPercentage(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        BigDecimal discountPercentage = tier.getDiscountPercentage();
        if ( discountPercentage == null ) {
            return null;
        }
        return discountPercentage;
    }

    private Boolean planTierFreeDelivery(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Boolean freeDelivery = tier.getFreeDelivery();
        if ( freeDelivery == null ) {
            return null;
        }
        return freeDelivery;
    }

    private Boolean planTierExclusiveDeals(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Boolean exclusiveDeals = tier.getExclusiveDeals();
        if ( exclusiveDeals == null ) {
            return null;
        }
        return exclusiveDeals;
    }

    private Boolean planTierEarlyAccess(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Boolean earlyAccess = tier.getEarlyAccess();
        if ( earlyAccess == null ) {
            return null;
        }
        return earlyAccess;
    }

    private Boolean planTierPrioritySupport(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Boolean prioritySupport = tier.getPrioritySupport();
        if ( prioritySupport == null ) {
            return null;
        }
        return prioritySupport;
    }

    private Integer planTierMaxCouponsPerMonth(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Integer maxCouponsPerMonth = tier.getMaxCouponsPerMonth();
        if ( maxCouponsPerMonth == null ) {
            return null;
        }
        return maxCouponsPerMonth;
    }

    private Integer planTierDeliveryDays(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        Integer deliveryDays = tier.getDeliveryDays();
        if ( deliveryDays == null ) {
            return null;
        }
        return deliveryDays;
    }

    private String planTierAdditionalBenefits(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }
        MembershipTier tier = membershipPlan.getTier();
        if ( tier == null ) {
            return null;
        }
        String additionalBenefits = tier.getAdditionalBenefits();
        if ( additionalBenefits == null ) {
            return null;
        }
        return additionalBenefits;
    }
}
