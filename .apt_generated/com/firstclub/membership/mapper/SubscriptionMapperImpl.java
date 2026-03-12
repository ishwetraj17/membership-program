package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.dto.TierBenefitsDTO;
import com.firstclub.membership.entity.MembershipPlan;
import com.firstclub.membership.entity.MembershipTier;
import com.firstclub.membership.entity.Subscription;
import com.firstclub.membership.entity.User;
import java.math.BigDecimal;
import javax.annotation.processing.Generated;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-12T15:03:54+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
public class SubscriptionMapperImpl implements SubscriptionMapper {

    @Override
    public SubscriptionDTO toDTO(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }

        SubscriptionDTO.SubscriptionDTOBuilder subscriptionDTO = SubscriptionDTO.builder();

        subscriptionDTO.tierBenefits( membershipPlanToTierBenefitsDTO( subscription.getPlan() ) );
        subscriptionDTO.userId( subscriptionUserId( subscription ) );
        subscriptionDTO.userName( subscriptionUserName( subscription ) );
        subscriptionDTO.userEmail( subscriptionUserEmail( subscription ) );
        subscriptionDTO.planId( subscriptionPlanId( subscription ) );
        subscriptionDTO.planName( subscriptionPlanName( subscription ) );
        subscriptionDTO.tier( subscriptionPlanTierName( subscription ) );
        subscriptionDTO.tierLevel( subscriptionPlanTierLevel( subscription ) );
        subscriptionDTO.autoRenewal( subscription.getAutoRenewal() );
        subscriptionDTO.cancellationReason( subscription.getCancellationReason() );
        subscriptionDTO.cancelledAt( subscription.getCancelledAt() );
        subscriptionDTO.endDate( subscription.getEndDate() );
        subscriptionDTO.id( subscription.getId() );
        subscriptionDTO.nextBillingDate( subscription.getNextBillingDate() );
        subscriptionDTO.paidAmount( subscription.getPaidAmount() );
        subscriptionDTO.startDate( subscription.getStartDate() );
        subscriptionDTO.status( subscription.getStatus() );

        subscriptionDTO.planType( subscription.getPlan().getType().name() );
        subscriptionDTO.daysRemaining( subscription.getDaysRemaining() );
        subscriptionDTO.isActive( subscription.isActive() );

        return subscriptionDTO.build();
    }

    @Override
    public TierBenefitsDTO tierToDTO(MembershipTier tier) {
        if ( tier == null ) {
            return null;
        }

        TierBenefitsDTO.TierBenefitsDTOBuilder tierBenefitsDTO = TierBenefitsDTO.builder();

        tierBenefitsDTO.discountPercentage( tier.getDiscountPercentage() );
        tierBenefitsDTO.freeDelivery( tier.getFreeDelivery() );
        tierBenefitsDTO.exclusiveDeals( tier.getExclusiveDeals() );
        tierBenefitsDTO.earlyAccess( tier.getEarlyAccess() );
        tierBenefitsDTO.prioritySupport( tier.getPrioritySupport() );
        tierBenefitsDTO.maxCouponsPerMonth( tier.getMaxCouponsPerMonth() );
        tierBenefitsDTO.deliveryDays( tier.getDeliveryDays() );
        tierBenefitsDTO.additionalBenefits( tier.getAdditionalBenefits() );

        return tierBenefitsDTO.build();
    }

    private BigDecimal membershipPlanTierDiscountPercentage(MembershipPlan membershipPlan) {
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

    private Boolean membershipPlanTierFreeDelivery(MembershipPlan membershipPlan) {
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

    private Boolean membershipPlanTierExclusiveDeals(MembershipPlan membershipPlan) {
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

    private Boolean membershipPlanTierEarlyAccess(MembershipPlan membershipPlan) {
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

    private Boolean membershipPlanTierPrioritySupport(MembershipPlan membershipPlan) {
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

    private Integer membershipPlanTierMaxCouponsPerMonth(MembershipPlan membershipPlan) {
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

    private Integer membershipPlanTierDeliveryDays(MembershipPlan membershipPlan) {
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

    private String membershipPlanTierAdditionalBenefits(MembershipPlan membershipPlan) {
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

    protected TierBenefitsDTO membershipPlanToTierBenefitsDTO(MembershipPlan membershipPlan) {
        if ( membershipPlan == null ) {
            return null;
        }

        TierBenefitsDTO.TierBenefitsDTOBuilder tierBenefitsDTO = TierBenefitsDTO.builder();

        tierBenefitsDTO.discountPercentage( membershipPlanTierDiscountPercentage( membershipPlan ) );
        tierBenefitsDTO.freeDelivery( membershipPlanTierFreeDelivery( membershipPlan ) );
        tierBenefitsDTO.exclusiveDeals( membershipPlanTierExclusiveDeals( membershipPlan ) );
        tierBenefitsDTO.earlyAccess( membershipPlanTierEarlyAccess( membershipPlan ) );
        tierBenefitsDTO.prioritySupport( membershipPlanTierPrioritySupport( membershipPlan ) );
        tierBenefitsDTO.maxCouponsPerMonth( membershipPlanTierMaxCouponsPerMonth( membershipPlan ) );
        tierBenefitsDTO.deliveryDays( membershipPlanTierDeliveryDays( membershipPlan ) );
        tierBenefitsDTO.additionalBenefits( membershipPlanTierAdditionalBenefits( membershipPlan ) );

        return tierBenefitsDTO.build();
    }

    private Long subscriptionUserId(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        User user = subscription.getUser();
        if ( user == null ) {
            return null;
        }
        Long id = user.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private String subscriptionUserName(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        User user = subscription.getUser();
        if ( user == null ) {
            return null;
        }
        String name = user.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }

    private String subscriptionUserEmail(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        User user = subscription.getUser();
        if ( user == null ) {
            return null;
        }
        String email = user.getEmail();
        if ( email == null ) {
            return null;
        }
        return email;
    }

    private Long subscriptionPlanId(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        MembershipPlan plan = subscription.getPlan();
        if ( plan == null ) {
            return null;
        }
        Long id = plan.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private String subscriptionPlanName(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        MembershipPlan plan = subscription.getPlan();
        if ( plan == null ) {
            return null;
        }
        String name = plan.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }

    private String subscriptionPlanTierName(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        MembershipPlan plan = subscription.getPlan();
        if ( plan == null ) {
            return null;
        }
        MembershipTier tier = plan.getTier();
        if ( tier == null ) {
            return null;
        }
        String name = tier.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }

    private Integer subscriptionPlanTierLevel(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }
        MembershipPlan plan = subscription.getPlan();
        if ( plan == null ) {
            return null;
        }
        MembershipTier tier = plan.getTier();
        if ( tier == null ) {
            return null;
        }
        Integer level = tier.getLevel();
        if ( level == null ) {
            return null;
        }
        return level;
    }
}
