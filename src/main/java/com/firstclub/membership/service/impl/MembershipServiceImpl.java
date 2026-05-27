package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.*;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class MembershipServiceImpl implements MembershipService {

    private final MembershipTierRepository tierRepository;
    private final MembershipPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserService userService;

    // ─── Plan queries ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getAllPlans() {
        return convertPlans(planRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getActivePlans() {
        return convertPlans(planRepository.findByIsActiveTrue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getPlansByTier(String tierName) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
                .orElseThrow(() -> MembershipException.tierNotFound(tierName));
        return convertPlans(planRepository.findByTierAndIsActiveTrue(tier));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getPlansByTierId(Long tierId) {
        MembershipTier tier = tierRepository.findById(tierId)
                .orElseThrow(() -> MembershipException.tierNotFound("ID: " + tierId));
        return convertPlans(planRepository.findByTierAndIsActiveTrue(tier));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type) {
        return convertPlans(planRepository.findByTypeAndIsActiveTrue(type));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipPlanDTO> getPlanById(Long id) {
        return planRepository.findById(id).map(plan -> {
            Map<Long, BigDecimal> monthlyPrices = buildMonthlyPriceMap(List.of(plan));
            return convertPlanToDTO(plan, monthlyPrices);
        });
    }

    // ─── Tier queries ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TierDTO> getAllTiers() {
        return tierRepository.findAll().stream()
                .map(this::convertTierToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TierDTO> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase()).map(this::convertTierToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TierDTO> getTierById(Long id) {
        return tierRepository.findById(id).map(this::convertTierToDTO);
    }

    // ─── Subscription lifecycle ───────────────────────────────────────────────

    @Override
    public SubscriptionDTO createSubscription(SubscriptionRequestDTO request) {
        log.info("Creating subscription — userId={} planId={}", request.getUserId(), request.getPlanId());

        User user = userService.findUserEntityById(request.getUserId());
        MembershipPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> MembershipException.planNotFound(request.getPlanId()));

        if (!plan.getIsActive()) {
            throw MembershipException.inactivePlan(plan.getId());
        }

        // Application-level guard — the DB partial unique index is the hard safety net
        if (subscriptionRepository.findActiveSubscriptionByUser(user, LocalDateTime.now()).isPresent()) {
            throw MembershipException.userAlreadySubscribed(user.getId());
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endDate = now.plusMonths(plan.getDurationInMonths());

        Subscription subscription = Subscription.builder()
                .user(user)
                .plan(plan)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(now)
                .endDate(endDate)
                .nextBillingDate(endDate)
                .paidAmount(plan.getPrice())
                .autoRenewal(request.getAutoRenewal())
                .build();

        Subscription saved = subscriptionRepository.save(subscription);
        log.info("Subscription created — id={}", saved.getId());
        return convertSubscriptionToDTO(saved);
    }

    @Override
    public SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO) {
        log.info("Updating subscription {}", subscriptionId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        boolean changed = false;

        if (updateDTO.getAutoRenewal() != null) {
            subscription.setAutoRenewal(updateDTO.getAutoRenewal());
            changed = true;
        }

        if (updateDTO.getNewPlanId() != null) {
            MembershipPlan newPlan = planRepository.findById(updateDTO.getNewPlanId())
                    .orElseThrow(() -> MembershipException.planNotFound(updateDTO.getNewPlanId()));

            if (!newPlan.getIsActive()) throw MembershipException.inactivePlan(newPlan.getId());

            if (!newPlan.getId().equals(subscription.getPlan().getId())) {
                MembershipPlan currentPlan = subscription.getPlan();
                BigDecimal proRated = calculateProRatedAmount(subscription, currentPlan, newPlan);

                subscription.setPlan(newPlan);
                subscription.setEndDate(subscription.getStartDate().plusMonths(newPlan.getDurationInMonths()));
                subscription.setNextBillingDate(subscription.getEndDate());
                subscription.setPaidAmount(subscription.getPaidAmount().add(proRated));
                changed = true;
                log.info("Plan changed on subscription {} — pro-rated adjustment: {}", subscriptionId, proRated);
            }
        }

        if (updateDTO.getStatus() != null && updateDTO.getStatus() != subscription.getStatus()) {
            if (!isValidStatusTransition(subscription.getStatus(), updateDTO.getStatus())) {
                throw new MembershipException(
                        "Invalid status transition from " + subscription.getStatus() + " to " + updateDTO.getStatus(),
                        "INVALID_STATUS_TRANSITION");
            }

            subscription.setStatus(updateDTO.getStatus());

            if (updateDTO.getStatus() == Subscription.SubscriptionStatus.CANCELLED) {
                subscription.setCancelledAt(LocalDateTime.now());
                subscription.setCancellationReason(
                        updateDTO.getReason() != null ? updateDTO.getReason() : "Updated via API");
                subscription.setAutoRenewal(false);
            }
            changed = true;
        }

        if (!changed) {
            return convertSubscriptionToDTO(subscription);
        }

        return convertSubscriptionToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) {
        log.info("Cancelling subscription {} — reason: {}", subscriptionId, reason);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw MembershipException.invalidSubscriptionStatus("cancel");
        }

        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenewal(false);
        // @UpdateTimestamp handles updatedAt automatically

        return convertSubscriptionToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO renewSubscription(Long subscriptionId) {
        log.info("Renewing subscription {}", subscriptionId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        if (subscription.getStatus() != Subscription.SubscriptionStatus.EXPIRED) {
            throw new MembershipException("Only expired subscriptions can be renewed", "INVALID_SUBSCRIPTION_STATUS");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndDate = now.plusMonths(subscription.getPlan().getDurationInMonths());

        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setStartDate(now);
        subscription.setEndDate(newEndDate);
        subscription.setNextBillingDate(newEndDate);

        return convertSubscriptionToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Upgrading subscription {} to plan {}", subscriptionId, newPlanId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot upgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }

        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));

        MembershipPlan currentPlan = subscription.getPlan();

        boolean higherTier = newPlan.getTier().getLevel() > currentPlan.getTier().getLevel();
        boolean longerDuration = newPlan.getTier().getLevel().equals(currentPlan.getTier().getLevel())
                && newPlan.getDurationInMonths() > currentPlan.getDurationInMonths();

        if (!higherTier && !longerDuration) {
            throw MembershipException.invalidPlanTransition(currentPlan.getName(), newPlan.getName());
        }

        BigDecimal proRated = calculateProRatedAmount(subscription, currentPlan, newPlan);
        subscription.setPlan(newPlan);
        subscription.setPaidAmount(subscription.getPaidAmount().add(proRated));

        return convertSubscriptionToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Downgrading subscription {} to plan {}", subscriptionId, newPlanId);

        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        // Guard that was previously missing
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot downgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }

        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));

        if (newPlan.getTier().getLevel() >= subscription.getPlan().getTier().getLevel()) {
            throw MembershipException.invalidPlanTransition(subscription.getPlan().getName(), newPlan.getName());
        }

        subscription.setPlan(newPlan);
        return convertSubscriptionToDTO(subscriptionRepository.save(subscription));
    }

    // ─── Subscription queries ─────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDTO> getActiveSubscription(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findActiveSubscriptionByUser(user, LocalDateTime.now())
                .map(this::convertSubscriptionToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionDTO> getUserSubscriptions(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::convertSubscriptionToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionDTO> getAllSubscriptions() {
        return subscriptionRepository.findAll().stream()
                .map(this::convertSubscriptionToDTO)
                .collect(Collectors.toList());
    }

    // ─── Background jobs ──────────────────────────────────────────────────────

    @Override
    public void processExpiredSubscriptions() {
        List<Subscription> expired = subscriptionRepository.findExpiredActiveSubscriptions(LocalDateTime.now());
        if (expired.isEmpty()) return;

        expired.forEach(sub -> sub.setStatus(Subscription.SubscriptionStatus.EXPIRED));
        subscriptionRepository.saveAll(expired);
        log.info("Marked {} subscription(s) as expired.", expired.size());
    }

    @Override
    public void processRenewals() {
        List<Subscription> due = subscriptionRepository.findSubscriptionsForRenewal(LocalDateTime.now().plusDays(1));
        due.forEach(sub -> {
            try {
                LocalDateTime newEnd = sub.getEndDate().plusMonths(sub.getPlan().getDurationInMonths());
                sub.setEndDate(newEnd);
                sub.setNextBillingDate(newEnd);
                subscriptionRepository.save(sub);
                log.info("Renewed subscription {}", sub.getId());
            } catch (Exception e) {
                log.error("Failed to renew subscription {}", sub.getId(), e);
            }
        });
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Converts a list of plans to DTOs in a single pass, fetching monthly base
     * prices up front to avoid per-plan N+1 queries.
     */
    private List<MembershipPlanDTO> convertPlans(List<MembershipPlan> plans) {
        Map<Long, BigDecimal> monthlyPriceByTierId = buildMonthlyPriceMap(plans);
        return plans.stream()
                .map(p -> convertPlanToDTO(p, monthlyPriceByTierId))
                .collect(Collectors.toList());
    }

    /** Builds a tierId → monthly-price lookup from an already-fetched plan list. */
    private Map<Long, BigDecimal> buildMonthlyPriceMap(List<MembershipPlan> plans) {
        return plans.stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY)
                .collect(Collectors.toMap(
                        p -> p.getTier().getId(),
                        MembershipPlan::getPrice,
                        (a, b) -> a)); // keep first on collision
    }

    private MembershipPlanDTO convertPlanToDTO(MembershipPlan plan, Map<Long, BigDecimal> monthlyPriceByTierId) {
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

    private TierDTO convertTierToDTO(MembershipTier tier) {
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
                .build();
    }

    private SubscriptionDTO convertSubscriptionToDTO(Subscription s) {
        return SubscriptionDTO.builder()
                .id(s.getId())
                .userId(s.getUser().getId())
                .userName(s.getUser().getName())
                .userEmail(s.getUser().getEmail())
                .planId(s.getPlan().getId())
                .planName(s.getPlan().getName())
                .planType(s.getPlan().getType().name())
                .tier(s.getPlan().getTier().getName())
                .tierLevel(s.getPlan().getTier().getLevel())
                .paidAmount(s.getPaidAmount())
                .status(s.getStatus())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .nextBillingDate(s.getNextBillingDate())
                .autoRenewal(s.getAutoRenewal())
                .daysRemaining(s.getDaysRemaining())
                .isActive(s.isActive())
                .cancelledAt(s.getCancelledAt())
                .cancellationReason(s.getCancellationReason())
                .discountPercentage(s.getPlan().getTier().getDiscountPercentage())
                .freeDelivery(s.getPlan().getTier().getFreeDelivery())
                .exclusiveDeals(s.getPlan().getTier().getExclusiveDeals())
                .earlyAccess(s.getPlan().getTier().getEarlyAccess())
                .prioritySupport(s.getPlan().getTier().getPrioritySupport())
                .maxCouponsPerMonth(s.getPlan().getTier().getMaxCouponsPerMonth())
                .deliveryDays(s.getPlan().getTier().getDeliveryDays())
                .additionalBenefits(s.getPlan().getTier().getAdditionalBenefits())
                .build();
    }

    private BigDecimal calculateProRatedAmount(Subscription subscription,
                                               MembershipPlan currentPlan,
                                               MembershipPlan newPlan) {
        LocalDateTime now = LocalDateTime.now();
        long totalDays = java.time.temporal.ChronoUnit.DAYS
                .between(subscription.getStartDate(), subscription.getEndDate());
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(now, subscription.getEndDate());

        if (remainingDays <= 0) return newPlan.getPrice();

        BigDecimal unusedValue = currentPlan.getPrice()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);

        BigDecimal newCost = newPlan.getPrice()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);

        return newCost.subtract(unusedValue);
    }

    private boolean isValidStatusTransition(Subscription.SubscriptionStatus from,
                                            Subscription.SubscriptionStatus to) {
        return switch (from) {
            case ACTIVE    -> to == Subscription.SubscriptionStatus.CANCELLED
                           || to == Subscription.SubscriptionStatus.SUSPENDED
                           || to == Subscription.SubscriptionStatus.EXPIRED;
            case PENDING   -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case SUSPENDED -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case EXPIRED   -> to == Subscription.SubscriptionStatus.ACTIVE;
            case CANCELLED -> false;
        };
    }
}
