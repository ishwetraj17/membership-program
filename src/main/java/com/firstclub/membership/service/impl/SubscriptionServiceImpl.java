package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.MembershipPlanRepository;
import com.firstclub.membership.repository.SubscriptionRepository;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SubscriptionServiceImpl implements SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final MembershipPlanRepository planRepository;
    private final UserService userService;
    private final SubscriptionRenewalProcessor renewalProcessor;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

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

        Subscription saved = subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(plan)
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .startDate(now)
                .endDate(endDate)
                .nextBillingDate(endDate)
                .paidAmount(plan.getPrice())
                .autoRenewal(request.getAutoRenewal())
                .build());

        log.info("Subscription created — id={}", saved.getId());
        return convertToDTO(saved);
    }

    @Override
    public SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO) {
        log.info("Updating subscription {}", subscriptionId);

        Subscription subscription = findById(subscriptionId);
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
                BigDecimal proRated = calculateProRated(subscription, subscription.getPlan(), newPlan);
                subscription.setPlan(newPlan);
                subscription.setEndDate(subscription.getStartDate().plusMonths(newPlan.getDurationInMonths()));
                subscription.setNextBillingDate(subscription.getEndDate());
                subscription.setPaidAmount(subscription.getPaidAmount().add(proRated));
                changed = true;
                log.info("Plan changed on subscription {} — pro-rated adjustment: {}", subscriptionId, proRated);
            }
        }

        if (updateDTO.getStatus() != null && updateDTO.getStatus() != subscription.getStatus()) {
            if (!isValidTransition(subscription.getStatus(), updateDTO.getStatus())) {
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

        return changed ? convertToDTO(subscriptionRepository.save(subscription)) : convertToDTO(subscription);
    }

    @Override
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) {
        log.info("Cancelling subscription {} — reason: {}", subscriptionId, reason);

        Subscription subscription = findById(subscriptionId);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw MembershipException.invalidSubscriptionStatus("cancel");
        }

        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenewal(false);

        return convertToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO renewSubscription(Long subscriptionId) {
        log.info("Renewing subscription {}", subscriptionId);

        Subscription subscription = findById(subscriptionId);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.EXPIRED) {
            throw new MembershipException("Only expired subscriptions can be renewed", "INVALID_SUBSCRIPTION_STATUS");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEnd = now.plusMonths(subscription.getPlan().getDurationInMonths());
        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setStartDate(now);
        subscription.setEndDate(newEnd);
        subscription.setNextBillingDate(newEnd);
        subscription.setPaidAmount(subscription.getPlan().getPrice()); // new billing period = new payment

        return convertToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Upgrading subscription {} to plan {}", subscriptionId, newPlanId);

        Subscription subscription = findById(subscriptionId);
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

        // Pro-ration is based on the current period — compute it before the period is extended.
        BigDecimal proRated = calculateProRated(subscription, currentPlan, newPlan);
        subscription.setPlan(newPlan);
        subscription.setEndDate(subscription.getStartDate().plusMonths(newPlan.getDurationInMonths()));
        subscription.setNextBillingDate(subscription.getEndDate());
        subscription.setPaidAmount(subscription.getPaidAmount().add(proRated));

        return convertToDTO(subscriptionRepository.save(subscription));
    }

    @Override
    public SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Downgrading subscription {} to plan {}", subscriptionId, newPlanId);

        Subscription subscription = findById(subscriptionId);
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot downgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }

        MembershipPlan newPlan = planRepository.findById(newPlanId)
                .orElseThrow(() -> MembershipException.planNotFound(newPlanId));

        if (newPlan.getTier().getLevel() >= subscription.getPlan().getTier().getLevel()) {
            throw MembershipException.invalidPlanTransition(subscription.getPlan().getName(), newPlan.getName());
        }

        subscription.setPlan(newPlan);
        LocalDateTime now = LocalDateTime.now();
        subscription.setStartDate(now);
        LocalDateTime newEnd = now.plusMonths(newPlan.getDurationInMonths());
        subscription.setEndDate(newEnd);
        subscription.setNextBillingDate(newEnd);
        return convertToDTO(subscriptionRepository.save(subscription));
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDTO> getActiveSubscription(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findActiveSubscriptionByUser(user, LocalDateTime.now())
                .map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionDTO> getUserSubscriptions(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getAllSubscriptions(Pageable pageable) {
        return subscriptionRepository.findAllWithAssociations(pageable).map(this::convertToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean subscriptionBelongsToUser(Long subscriptionId, Long userId) {
        return subscriptionRepository.existsByIdAndUserId(subscriptionId, userId);
    }

    // ─── Background jobs ──────────────────────────────────────────────────────

    @Override
    public void processExpiredSubscriptions() {
        int count = subscriptionRepository.bulkExpireSubscriptions(LocalDateTime.now());
        if (count > 0) log.info("Bulk expired {} subscription(s).", count);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processRenewals() {
        // NOT_SUPPORTED: entities are loaded without an outer transaction so they become
        // detached after the query. Each renewSingle call (REQUIRES_NEW) merges and saves
        // the entity in its own transaction — one failure never rolls back the rest.
        List<Subscription> due = subscriptionRepository.findSubscriptionsForRenewal(LocalDateTime.now().plusDays(1));
        if (due.isEmpty()) return;

        long count = due.stream().filter(renewalProcessor::renewSingle).count();
        if (count > 0) log.info("Renewed {} subscription(s).", count);
    }

    // ─── Aggregates ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAnalyticsStats() {
        long activeCount = subscriptionRepository.countByStatus(Subscription.SubscriptionStatus.ACTIVE);
        long totalCount = subscriptionRepository.count();
        BigDecimal totalRevenue = subscriptionRepository.sumActivePaidAmount();
        if (totalRevenue == null) totalRevenue = BigDecimal.ZERO;
        BigDecimal avgRevenue = activeCount == 0 ? BigDecimal.ZERO
                : totalRevenue.divide(BigDecimal.valueOf(activeCount), 2, RoundingMode.HALF_UP);

        Map<String, Long> tierDist = subscriptionRepository.countActiveGroupedByTier().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        Map<String, Long> planTypeDist = subscriptionRepository.countActiveGroupedByPlanType().stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> (Long) r[1]));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRevenue", totalRevenue);
        stats.put("averageRevenuePerUser", avgRevenue);
        stats.put("activeSubscriptions", activeCount);
        stats.put("totalSubscriptions", totalCount);
        stats.put("tierDistribution", tierDist);
        stats.put("planTypeDistribution", planTypeDist);
        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getActiveStats() {
        long activeCount = subscriptionRepository.countByStatus(Subscription.SubscriptionStatus.ACTIVE);
        long userCount = subscriptionRepository.countUsersWithActiveSubscriptions();
        Map<String, Long> tierDist = subscriptionRepository.countActiveGroupedByTier().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        return Map.of("activeSubscriptions", activeCount, "uniqueUsers", userCount, "tierDistribution", tierDist);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Subscription findById(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> MembershipException.subscriptionNotFound(id));
    }

    private SubscriptionDTO convertToDTO(Subscription s) {
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

    private BigDecimal calculateProRated(Subscription subscription, MembershipPlan currentPlan, MembershipPlan newPlan) {
        LocalDateTime now = LocalDateTime.now();
        long totalDays = ChronoUnit.DAYS.between(subscription.getStartDate(), subscription.getEndDate());
        long remainingDays = ChronoUnit.DAYS.between(now, subscription.getEndDate());

        if (remainingDays <= 0) return newPlan.getPrice();

        BigDecimal unusedValue = currentPlan.getPrice()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);

        BigDecimal newCost = newPlan.getPrice()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 2, RoundingMode.HALF_UP);

        return newCost.subtract(unusedValue);
    }

    private boolean isValidTransition(Subscription.SubscriptionStatus from, Subscription.SubscriptionStatus to) {
        return switch (from) {
            case ACTIVE    -> to == Subscription.SubscriptionStatus.CANCELLED
                           || to == Subscription.SubscriptionStatus.SUSPENDED
                           || to == Subscription.SubscriptionStatus.EXPIRED;
            case PENDING   -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case SUSPENDED -> to == Subscription.SubscriptionStatus.ACTIVE
                           || to == Subscription.SubscriptionStatus.CANCELLED;
            case EXPIRED   -> false; // Reactivation requires renewSubscription — not generic update
            case CANCELLED -> false;
        };
    }
}
