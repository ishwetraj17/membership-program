package com.firstclub.membership.service.impl;

import com.firstclub.membership.config.AppConstants;
import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.SubscriptionMapper;
import com.firstclub.membership.repository.*;
import com.firstclub.membership.service.AuditContext;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.SeedingService;
import com.firstclub.membership.service.UserService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.firstclub.membership.event.*;
import com.firstclub.platform.statemachine.StateMachineValidator;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Implementation of MembershipService
 * 
 * Core business logic for membership management.
 * Handles plan creation, subscription lifecycle, and background jobs.
 * 
 * Implemented by Shwet Raj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipServiceImpl implements MembershipService, SeedingService {
    
    private final MembershipTierRepository tierRepository;
    private final MembershipPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;
    private final UserService userService;
    private final SubscriptionMapper subscriptionMapper;
    private final AuditContext auditContext;
    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final StateMachineValidator stateMachineValidator;

    // Business event counters — lazily initialised in @PostConstruct
    private Counter subscriptionsCreatedCounter;
    private Counter subscriptionsCancelledCounter;
    private Counter subscriptionsRenewedCounter;

    @Value("${membership.pricing.silver:299}")
    private BigDecimal silverBasePrice;

    @Value("${membership.pricing.gold:499}")
    private BigDecimal goldBasePrice;

    @Value("${membership.pricing.platinum:799}")
    private BigDecimal platinumBasePrice;

    // Plan duration discount factors — business constants, configurable here rather than buried in code
    private static final BigDecimal QUARTERLY_DISCOUNT_FACTOR = new BigDecimal("0.95"); // 5% off
    private static final BigDecimal YEARLY_DISCOUNT_FACTOR    = new BigDecimal("0.85"); // 15% off
    
    /**
     * Initialize default data on application startup
     * 
     * Creates tiers and plans, plus some sample users for demo.
     * This runs every time the app starts since we're using H2 in-memory.
     */
    @PostConstruct
    public void init() {
        initializeDefaultData();
        subscriptionsCreatedCounter  = meterRegistry.counter("membership.subscriptions.created");
        subscriptionsCancelledCounter = meterRegistry.counter("membership.subscriptions.cancelled");
        subscriptionsRenewedCounter  = meterRegistry.counter("membership.subscriptions.renewed");
    }
    
    /**
     * Initialize default data on application startup.
     *
     * Package-private: called only by {@code @PostConstruct init()} within this class.
     * Removed from the {@link MembershipService} public interface — initialisation
     * is an implementation detail, not a public contract.
     */
    @Transactional
    @CacheEvict(value = {"plans", "plansByTier", "plansByType", "tiers"}, allEntries = true)
    void initializeDefaultData() {
        log.info("Starting membership system initialization...");
        
        if (tierRepository.count() == 0) {
            List<MembershipTier> defaultTiers = buildDefaultTiers();
            for (MembershipTier tier : defaultTiers) {
                MembershipTier savedTier = tierRepository.save(tier);
                log.info("Created tier: {} with {}% discount", savedTier.getName(), savedTier.getDiscountPercentage());
                createDefaultPlansForTier(savedTier);
            }
            log.info("Membership system initialized successfully!");
        }
    }

    /**
     * Tier seed definitions — previously a static factory on the entity class (wrong layer).
     * Business/configuration data belongs in the service layer.
     */
    private List<MembershipTier> buildDefaultTiers() {
        return List.of(
            MembershipTier.builder()
                .name("SILVER")
                .description("Essential benefits for new members")
                .level(1)
                .discountPercentage(new BigDecimal("5.00"))
                .freeDelivery(false)
                .exclusiveDeals(false)
                .earlyAccess(false)
                .prioritySupport(false)
                .maxCouponsPerMonth(2)
                .deliveryDays(5)
                .additionalBenefits("Basic member perks and content access")
                .build(),
            MembershipTier.builder()
                .name("GOLD")
                .description("Premium benefits with free delivery")
                .level(2)
                .discountPercentage(new BigDecimal("10.00"))
                .freeDelivery(true)
                .exclusiveDeals(true)
                .earlyAccess(true)
                .prioritySupport(false)
                .maxCouponsPerMonth(5)
                .deliveryDays(3)
                .additionalBenefits("Free delivery, exclusive deals, early sale access")
                .build(),
            MembershipTier.builder()
                .name("PLATINUM")
                .description("Ultimate tier with all premium features")
                .level(3)
                .discountPercentage(new BigDecimal("15.00"))
                .freeDelivery(true)
                .exclusiveDeals(true)
                .earlyAccess(true)
                .prioritySupport(true)
                .maxCouponsPerMonth(10)
                .deliveryDays(1)
                .additionalBenefits("All benefits plus priority support and same-day delivery")
                .build()
        );
    }
    
    /**
     * Create sample users for demo purposes.
     * Called by DevDataSeeder (@Profile("dev")) on startup — not from this class.
     */
    public void createSampleUsers() {
        if (userService.getUserByEmail("admin@firstclub.com").isEmpty()) {
            log.info("Creating sample users for demonstration...");

            // Admin user — used by integration tests and management
            UserDTO admin = UserDTO.builder()
                .name("System Admin")
                .email("admin@firstclub.com")
                .phoneNumber("9000000001")
                .address("1 Admin St")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400001")
                .password("Admin@firstclub1")
                .build();

            UserDTO user1 = UserDTO.builder()
                .name("Karan Singh")
                .email("karan.singh@flipkart.com")
                .phoneNumber("9876543210")
                .address("12 HSR Layout")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560102")
                .password("Demo@1234")
                .build();
                
            UserDTO user2 = UserDTO.builder()
                .name("Ananya Sharma")
                .email("ananya.sharma@tcs.com")
                .phoneNumber("9876543211")
                .address("23 Andheri West")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400058")
                .password("Demo@1234")
                .build();
                
            UserDTO user3 = UserDTO.builder()
                .name("Rohit Agarwal")
                .email("rohit.agarwal@zomato.com")
                .phoneNumber("9876543212")
                .address("45 Connaught Place")
                .city("New Delhi")
                .state("Delhi")
                .pincode("110001")
                .password("Demo@1234")
                .build();
            
            try {
                userService.createAdminUser(admin);
                userService.createUser(user1);
                userService.createUser(user2);
                userService.createUser(user3);
                log.info("Sample users created successfully for demo");
            } catch (Exception e) {
                log.warn("Sample users might already exist, skipping creation");
            }
        }
    }
    
    /**
     * Create default plans for a tier
     * 
     * Each tier gets 3 plans: Monthly, Quarterly (5% off), Yearly (15% off)
     * Pricing based on tier level - learned this pattern from e-commerce sites
     */
    private void createDefaultPlansForTier(MembershipTier tier) {
        BigDecimal basePrice = getBasePriceForTier(tier.getLevel());
        
        // Monthly plan - no discount
        MembershipPlan monthlyPlan = MembershipPlan.builder()
            .name(tier.getName() + " Monthly")
            .description("Monthly " + tier.getName() + " membership")
            .type(MembershipPlan.PlanType.MONTHLY)
            .price(basePrice)
            .durationInMonths(1)
            .tier(tier)
            .isActive(true)
            .build();
            
        // Quarterly plan - QUARTERLY_DISCOUNT_FACTOR (5% off)
        MembershipPlan quarterlyPlan = MembershipPlan.builder()
            .name(tier.getName() + " Quarterly")
            .description("Quarterly " + tier.getName() + " membership with savings")
            .type(MembershipPlan.PlanType.QUARTERLY)
            .price(basePrice.multiply(new BigDecimal("3")).multiply(QUARTERLY_DISCOUNT_FACTOR))
            .durationInMonths(3)
            .tier(tier)
            .isActive(true)
            .build();
            
        // Yearly plan - YEARLY_DISCOUNT_FACTOR (15% off)
        MembershipPlan yearlyPlan = MembershipPlan.builder()
            .name(tier.getName() + " Yearly")
            .description("Yearly " + tier.getName() + " membership with maximum savings")
            .type(MembershipPlan.PlanType.YEARLY)
            .price(basePrice.multiply(new BigDecimal("12")).multiply(YEARLY_DISCOUNT_FACTOR))
            .durationInMonths(12)
            .tier(tier)
            .isActive(true)
            .build();
            
        planRepository.save(monthlyPlan);
        planRepository.save(quarterlyPlan);
        planRepository.save(yearlyPlan);
        
        log.info("Created plans for tier: {}", tier.getName());
    }
    
    /**
     * Get base pricing for tier levels from configuration.
     * Prices are defined in application.properties (membership.pricing.*).
     * Throws if an unrecognised level is passed — prevents silent wrong-price bugs.
     */
    private BigDecimal getBasePriceForTier(Integer tierLevel) {
        return switch (tierLevel) {
            case 1 -> silverBasePrice;
            case 2 -> goldBasePrice;
            case 3 -> platinumBasePrice;
            default -> throw new MembershipException(
                "No pricing configured for tier level " + tierLevel
                    + ". Supported levels: 1 (Silver), 2 (Gold), 3 (Platinum).",
                "UNSUPPORTED_TIER_LEVEL",
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
            );
        };
    }
    
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SubscriptionDTO createSubscription(SubscriptionRequestDTO request) {
        log.info("Creating subscription for user: {} with plan: {}", request.getUserId(), request.getPlanId());
        
        // Additional validation for API testing robustness
        if (request.getUserId() == null || request.getUserId() <= 0) {
            throw new MembershipException("Invalid user ID provided", "INVALID_USER_ID");
        }
        
        if (request.getPlanId() == null || request.getPlanId() <= 0) {
            throw new MembershipException("Invalid plan ID provided", "INVALID_PLAN_ID");
        }
        
        User user = userService.findUserEntityById(request.getUserId());
        MembershipPlan plan = planRepository.findById(request.getPlanId())
            .orElseThrow(() -> new MembershipException("Plan not found", "PLAN_NOT_FOUND"));
            
        // Validate plan is active
        if (!plan.getIsActive()) {
            throw new MembershipException("Cannot subscribe to inactive plan", "INACTIVE_PLAN");
        }
            
        // Check if user already has active subscription
        Optional<Subscription> existingSubscription = subscriptionRepository
            .findActiveSubscriptionByUser(user, LocalDateTime.now());
            
        if (existingSubscription.isPresent()) {
            throw new MembershipException("User already has an active subscription", "ACTIVE_SUBSCRIPTION_EXISTS");
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
            
        Subscription savedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription created successfully with ID: {}", savedSubscription.getId());
        subscriptionsCreatedCounter.increment();
        
        recordHistory(savedSubscription, SubscriptionHistory.EventType.CREATED,
            null, plan.getId(), null, Subscription.SubscriptionStatus.ACTIVE, "Subscription created");
        eventPublisher.publishEvent(new SubscriptionCreatedEvent(
                this, savedSubscription.getId(), savedSubscription.getUser().getId(), plan.getId()));

        return convertSubscriptionToDTO(savedSubscription);
    }
    
    @Override
    @Transactional
    public SubscriptionDTO updateSubscription(Long subscriptionId, SubscriptionUpdateDTO updateDTO) {
        log.info("Updating subscription: {} with update request: {}", subscriptionId, updateDTO);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found", "SUBSCRIPTION_NOT_FOUND"));
            
        boolean hasChanges = false;
        
        // Update auto-renewal setting
        if (updateDTO.getAutoRenewal() != null) {
            subscription.setAutoRenewal(updateDTO.getAutoRenewal());
            hasChanges = true;
            log.info("Updated auto-renewal for subscription {}: {}", subscriptionId, updateDTO.getAutoRenewal());
        }
        
        // Handle plan changes (upgrade/downgrade)
        if (updateDTO.getNewPlanId() != null) {
            MembershipPlan newPlan = planRepository.findById(updateDTO.getNewPlanId())
                .orElseThrow(() -> new MembershipException("Plan not found", "PLAN_NOT_FOUND"));
                
            if (!newPlan.getIsActive()) {
                throw new MembershipException("Cannot subscribe to inactive plan", "INACTIVE_PLAN");
            }
            
            MembershipPlan currentPlan = subscription.getPlan();
            
            // Validate the plan change is valid
            if (!currentPlan.getId().equals(newPlan.getId())) {
                // Calculate pro-rated adjustments for plan changes
                subscription.setPlan(newPlan);
                
                // Update subscription end date from NOW — not from original start date (bug fix)
                LocalDateTime newEndDate = LocalDateTime.now().plusMonths(newPlan.getDurationInMonths());
                subscription.setEndDate(newEndDate);
                subscription.setNextBillingDate(newEndDate);
                
                // Calculate pro-rated billing adjustment
                BigDecimal proRatedAmount = calculateProRatedAmount(subscription, currentPlan, newPlan);
                subscription.setPaidAmount(subscription.getPaidAmount().add(proRatedAmount));
                
                hasChanges = true;
                log.info("Updated subscription {} from plan {} to plan {} with pro-rated adjustment: {}", 
                    subscriptionId, currentPlan.getName(), newPlan.getName(), proRatedAmount);
            }
        }
        
        // Handle status changes
        if (updateDTO.getStatus() != null && updateDTO.getStatus() != subscription.getStatus()) {
            Subscription.SubscriptionStatus newStatus = updateDTO.getStatus();
            Subscription.SubscriptionStatus oldStatus = subscription.getStatus();

            // Validate state transition — throws MembershipException if illegal
            stateMachineValidator.validate("SUBSCRIPTION", oldStatus, newStatus);
            subscription.setStatus(newStatus);

            if (newStatus == Subscription.SubscriptionStatus.CANCELLED) {
                subscription.setCancelledAt(LocalDateTime.now());
                subscription.setCancellationReason(
                    updateDTO.getReason() != null ? updateDTO.getReason() : "Updated via API"
                );
                subscription.setAutoRenewal(false);
            }

            hasChanges = true;
            log.info("Updated subscription {} status from {} to {}", subscriptionId, oldStatus, newStatus);
        }
        
        if (!hasChanges) {
            log.info("No changes detected for subscription update: {}", subscriptionId);
            return convertSubscriptionToDTO(subscription);
        }
        
        Subscription updatedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription updated successfully: {}", subscriptionId);
        
        return convertSubscriptionToDTO(updatedSubscription);
    }
    
    /**
     * Calculate pro-rated billing amount for plan changes.
     *
     * Positive return = user owes additional amount (upgrade).
     * Negative return = user receives a credit (downgrade).
     *
     * Guard: if totalDays == 0 (same-day create+change edge case), charge full new plan price
     * to avoid ArithmeticException on divide-by-zero.
     */
    private BigDecimal calculateProRatedAmount(Subscription subscription, MembershipPlan currentPlan, MembershipPlan newPlan) {
        LocalDateTime now = LocalDateTime.now();
        long totalDays     = java.time.temporal.ChronoUnit.DAYS.between(subscription.getStartDate(), subscription.getEndDate());
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(now, subscription.getEndDate());

        if (remainingDays <= 0) {
            // Past end date — charge full new plan price
            return newPlan.getPrice();
        }

        if (totalDays <= 0) {
            // Same-day edge case: charge full new plan price, avoid divide-by-zero
            return newPlan.getPrice();
        }

        BigDecimal total     = new BigDecimal(totalDays);
        BigDecimal remaining = new BigDecimal(remainingDays);

        BigDecimal unusedCurrentPlanValue  = currentPlan.getPrice().multiply(remaining).divide(total, 2, RoundingMode.HALF_UP);
        BigDecimal newPlanProportionalCost = newPlan.getPrice().multiply(remaining).divide(total, 2, RoundingMode.HALF_UP);
        BigDecimal proRatedDifference      = newPlanProportionalCost.subtract(unusedCurrentPlanValue);

        log.debug("Pro-rated calculation: totalDays={}, remainingDays={}, unusedCurrent={}, newCost={}, diff={}",
            totalDays, remainingDays, unusedCurrentPlanValue, newPlanProportionalCost, proRatedDifference);

        return proRatedDifference;
    }
    
    
    @Override
    @Transactional
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) {
        log.info("Cancelling subscription: {} with reason: {}", subscriptionId, reason);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));
            
        stateMachineValidator.validate("SUBSCRIPTION", subscription.getStatus(), Subscription.SubscriptionStatus.CANCELLED);
        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenewal(false);
        // @UpdateTimestamp on updatedAt handles the timestamp — no manual call needed
        
        Subscription cancelledSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription cancelled successfully: {} with reason: {}", subscriptionId, reason);
        subscriptionsCancelledCounter.increment();
        
        recordHistory(cancelledSubscription, SubscriptionHistory.EventType.CANCELLED,
            cancelledSubscription.getPlan().getId(), null,
            Subscription.SubscriptionStatus.ACTIVE, Subscription.SubscriptionStatus.CANCELLED, reason);
        eventPublisher.publishEvent(new SubscriptionCancelledEvent(
                this, cancelledSubscription.getId(), cancelledSubscription.getUser().getId(), reason));

        return convertSubscriptionToDTO(cancelledSubscription);
    }
    
    @Override
    @Transactional
    public SubscriptionDTO renewSubscription(Long subscriptionId) {
        log.info("Renewing subscription: {}", subscriptionId);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found", "SUBSCRIPTION_NOT_FOUND"));
            
        stateMachineValidator.validate("SUBSCRIPTION", subscription.getStatus(), Subscription.SubscriptionStatus.ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndDate = now.plusMonths(subscription.getPlan().getDurationInMonths());
        
        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setStartDate(now);
        subscription.setEndDate(newEndDate);
        subscription.setNextBillingDate(newEndDate);
        
        Subscription renewedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription renewed successfully: {}", subscriptionId);
        subscriptionsRenewedCounter.increment();
        
        recordHistory(renewedSubscription, SubscriptionHistory.EventType.RENEWED,
            renewedSubscription.getPlan().getId(), renewedSubscription.getPlan().getId(),
            Subscription.SubscriptionStatus.EXPIRED, Subscription.SubscriptionStatus.ACTIVE, "Subscription renewed");
        eventPublisher.publishEvent(new SubscriptionRenewedEvent(
                this, renewedSubscription.getId(), renewedSubscription.getUser().getId(),
                renewedSubscription.getPlan().getId()));

        return convertSubscriptionToDTO(renewedSubscription);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<SubscriptionDTO> getActiveSubscription(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findActiveSubscriptionByUser(user, LocalDateTime.now())
            .map(this::convertSubscriptionToDTO);
    }
    
    @Override
    @Transactional(readOnly = true)
    @Deprecated(since = "1.0", forRemoval = true)
    public List<SubscriptionDTO> getUserSubscriptions(Long userId) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findByUserOrderByCreatedAtDesc(user).stream()
            .map(this::convertSubscriptionToDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getUserSubscriptionsPaged(Long userId, Pageable pageable) {
        User user = userService.findUserEntityById(userId);
        return subscriptionRepository.findByUserOrderByCreatedAtDesc(user, pageable)
            .map(this::convertSubscriptionToDTO);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getAllSubscriptionsPaged(Pageable pageable) {
        return subscriptionRepository.findAll(pageable)
            .map(this::convertSubscriptionToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getAllSubscriptionsFiltered(
            Subscription.SubscriptionStatus status, Long userId, Pageable pageable) {
        return subscriptionRepository.findWithFilters(status, userId, pageable)
            .map(this::convertSubscriptionToDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public SubscriptionDTO getSubscriptionById(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found with id: " + subscriptionId, "SUBSCRIPTION_NOT_FOUND"));
        return convertSubscriptionToDTO(subscription);
    }
    
    @Override
    @Transactional
    public SubscriptionDTO upgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Upgrading subscription: {} to plan: {}", subscriptionId, newPlanId);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));
            
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot upgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }
            
        MembershipPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> MembershipException.planNotFound(newPlanId));
        
        MembershipPlan currentPlan = subscription.getPlan();
        
        // Allow upgrades within same tier (monthly to yearly) or to higher tier
        boolean isValidUpgrade = newPlan.getTier().getLevel() > currentPlan.getTier().getLevel() ||
                               (newPlan.getTier().getLevel().equals(currentPlan.getTier().getLevel()) && 
                                newPlan.getDurationInMonths() > currentPlan.getDurationInMonths());
        
        if (!isValidUpgrade) {
            throw new MembershipException("Invalid upgrade: new plan must be higher tier or longer duration", "INVALID_UPGRADE");
        }
        
        // Use pro-rated calculation: charge only for remaining days at new plan rate
        BigDecimal proRatedAmount = calculateProRatedAmount(subscription, currentPlan, newPlan);
        subscription.setPlan(newPlan);
        subscription.setPaidAmount(subscription.getPaidAmount().add(proRatedAmount));
        
        Subscription upgradedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription upgraded successfully: {} to plan: {} (pro-rated charge: {})",
            subscriptionId, newPlan.getName(), proRatedAmount);
        
        recordHistory(upgradedSubscription, SubscriptionHistory.EventType.UPGRADED,
            currentPlan.getId(), newPlan.getId(),
            Subscription.SubscriptionStatus.ACTIVE, Subscription.SubscriptionStatus.ACTIVE,
            "Upgraded to " + newPlan.getName());
        eventPublisher.publishEvent(new SubscriptionUpgradedEvent(
                this, upgradedSubscription.getId(), upgradedSubscription.getUser().getId(),
                currentPlan.getId(), newPlan.getId()));

        return convertSubscriptionToDTO(upgradedSubscription);
    }
    
    @Override
    @Transactional
    public SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Downgrading subscription: {} to plan: {}", subscriptionId, newPlanId);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found", "SUBSCRIPTION_NOT_FOUND"));

        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot downgrade non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }

        MembershipPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> new MembershipException("Plan not found", "PLAN_NOT_FOUND"));
            
        MembershipPlan currentPlan = subscription.getPlan();

        // Allow lower tier OR same tier with shorter duration (e.g. yearly → monthly)
        boolean isValidDowngrade = newPlan.getTier().getLevel() < currentPlan.getTier().getLevel() ||
            (newPlan.getTier().getLevel().equals(currentPlan.getTier().getLevel()) &&
             newPlan.getDurationInMonths() < currentPlan.getDurationInMonths());

        if (!isValidDowngrade) {
            throw new MembershipException(
                "Invalid downgrade: new plan must be lower tier or shorter duration within same tier",
                "INVALID_DOWNGRADE"
            );
        }
        
        // Apply pro-rated credit for unused portion of current plan (negative = credit)
        BigDecimal proRatedDiff = calculateProRatedAmount(subscription, currentPlan, newPlan);
        subscription.setPlan(newPlan);
        subscription.setPaidAmount(subscription.getPaidAmount().add(proRatedDiff));
        
        Subscription downgradedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription downgraded successfully: {} to plan: {} (pro-rated diff: {})",
            subscriptionId, newPlan.getName(), proRatedDiff);
        
        recordHistory(downgradedSubscription, SubscriptionHistory.EventType.DOWNGRADED,
            currentPlan.getId(), newPlan.getId(),
            Subscription.SubscriptionStatus.ACTIVE, Subscription.SubscriptionStatus.ACTIVE,
            "Downgraded to " + newPlan.getName());
        eventPublisher.publishEvent(new SubscriptionDowngradedEvent(
                this, downgradedSubscription.getId(), downgradedSubscription.getUser().getId(),
                currentPlan.getId(), newPlan.getId()));

        return convertSubscriptionToDTO(downgradedSubscription);
    }
    
    @Override
    @Transactional(timeout = 120)
    public void processExpiredSubscriptions() {
        log.info("Processing expired subscriptions...");
        int expired = subscriptionRepository.bulkExpireSubscriptions(LocalDateTime.now());
        log.info("Marked {} subscriptions as EXPIRED via bulk update", expired);
    }
    
    @Override
    @Transactional(timeout = 300)
    public void processRenewals() {
        log.info("Processing subscription renewals...");

        int pageNum = 0;
        int totalRenewed = 0;
        int totalFailed = 0;
        Page<Subscription> renewalsPage;

        do {
            renewalsPage = subscriptionRepository.findSubscriptionsForRenewal(
                LocalDateTime.now().plusDays(1),
                PageRequest.of(pageNum, AppConstants.RENEWAL_BATCH_SIZE)
            );
            for (Subscription sub : renewalsPage.getContent()) {
                try {
                    renewSingleSubscription(sub);
                    totalRenewed++;
                } catch (Exception e) {
                    totalFailed++;
                    log.error("Failed to renew subscription: {}", sub.getId(), e);
                }
            }
            pageNum++;
        } while (renewalsPage.hasNext());

        log.info("Renewal job complete: {} renewed, {} failed", totalRenewed, totalFailed);
        subscriptionsRenewedCounter.increment(totalRenewed);
    }

    /**
     * Renews a single subscription in its own transaction so that a failure
     * for one record does not roll back the entire renewal batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public void renewSingleSubscription(Subscription sub) {
        LocalDateTime newEndDate = sub.getEndDate().plusMonths(sub.getPlan().getDurationInMonths());
        sub.setStartDate(LocalDateTime.now());
        sub.setEndDate(newEndDate);
        sub.setNextBillingDate(newEndDate);
        subscriptionRepository.save(sub);
        log.debug("Renewed subscription: {}", sub.getId());
    }
    
    @Override
    @Transactional(readOnly = true)
    public UpgradePreviewDTO getUpgradePreview(Long subscriptionId, Long newPlanId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));

        MembershipPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> MembershipException.planNotFound(newPlanId));

        BigDecimal proRated = calculateProRatedAmount(subscription, subscription.getPlan(), newPlan);

        return UpgradePreviewDTO.builder()
            .subscriptionId(subscriptionId)
            .currentPlanName(subscription.getPlan().getName())
            .currentTier(subscription.getPlan().getTier().getName())
            .currentPlanPrice(subscription.getPlan().getPrice())
            .newPlanName(newPlan.getName())
            .newTier(newPlan.getTier().getName())
            .newPlanPrice(newPlan.getPrice())
            .proRatedDifference(proRated)
            .currency("INR")
            .effectiveDate(LocalDateTime.now())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("systemHealth")
    public SystemHealthDTO getSystemHealth() {
        long active    = subscriptionRepository.countBySubscriptionStatus(Subscription.SubscriptionStatus.ACTIVE);
        long expired   = subscriptionRepository.countBySubscriptionStatus(Subscription.SubscriptionStatus.EXPIRED);
        long cancelled = subscriptionRepository.countBySubscriptionStatus(Subscription.SubscriptionStatus.CANCELLED);
        long users     = subscriptionRepository.countDistinctActiveUsers();

        Map<String, Long> tierDist = new LinkedHashMap<>();
        for (Object[] row : subscriptionRepository.countActiveByTier()) {
            tierDist.put((String) row[0], (Long) row[1]);
        }

        return SystemHealthDTO.builder()
            .status("UP")
            .timestamp(LocalDateTime.now())
            .version("1.0.0")
            .metrics(SystemHealthDTO.MetricsDTO.builder()
                .totalUsers(users)
                .activeSubscriptions(active)
                .expiredSubscriptions(expired)
                .cancelledSubscriptions(cancelled)
                .availablePlans((int) planRepository.countActivePlans())
                .membershipTiers(3)
                .tierDistribution(tierDist)
                .build())
            .system(SystemHealthDTO.SystemInfoDTO.builder()
                .javaVersion(System.getProperty("java.version"))
                .database("H2 In-Memory")
                .environment("Development")
                .build())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable("analytics")
    public AnalyticsDTO getAnalytics() {
        BigDecimal totalRevenue = subscriptionRepository.sumActiveRevenue();
        long active = subscriptionRepository.countBySubscriptionStatus(Subscription.SubscriptionStatus.ACTIVE);
        long total  = subscriptionRepository.count();

        Map<String, Long> tierPop = new LinkedHashMap<>();
        for (Object[] row : subscriptionRepository.countActiveByTier()) {
            tierPop.put((String) row[0], (Long) row[1]);
        }

        Map<String, Long> planTypeDist = new LinkedHashMap<>();
        for (Object[] row : subscriptionRepository.countActiveByPlanType()) {
            planTypeDist.put(row[0].toString(), (Long) row[1]);
        }

        BigDecimal arpu = active == 0
            ? BigDecimal.ZERO
            : totalRevenue.divide(new BigDecimal(active), 2, RoundingMode.HALF_UP);

        return AnalyticsDTO.builder()
            .revenue(AnalyticsDTO.RevenueDTO.builder()
                .totalRevenue(totalRevenue)
                .currency("INR")
                .averageRevenuePerUser(arpu)
                .build())
            .membership(AnalyticsDTO.MembershipMetricsDTO.builder()
                .tierPopularity(tierPop)
                .planTypeDistribution(planTypeDist)
                .totalActivePlans((int) planRepository.countActivePlans())
                .build())
            .summary(AnalyticsDTO.SummaryDTO.builder()
                .totalSubscriptions(total)
                .activeSubscriptions(active)
                .generatedAt(LocalDateTime.now())
                .build())
            .build();
    }

    /**
     * Records a subscription lifecycle event to the audit log.
     * Captures the authenticated user ID when invoked from a web request;
     * null for background scheduler jobs (no security context).
     */
    private void recordHistory(Subscription subscription,
                               SubscriptionHistory.EventType eventType,
                               Long oldPlanId,
                               Long newPlanId,
                               Subscription.SubscriptionStatus oldStatus,
                               Subscription.SubscriptionStatus newStatus,
                               String reason) {
        SubscriptionHistory entry = SubscriptionHistory.builder()
            .subscription(subscription)
            .eventType(eventType)
            .oldPlanId(oldPlanId)
            .newPlanId(newPlanId)
            .oldStatus(oldStatus)
            .newStatus(newStatus)
            .reason(reason)
            .changedByUserId(auditContext.getCurrentUserId())
            .build();
        subscriptionHistoryRepository.save(entry);
    }

    /**
     * Convert Subscription entity to DTO using MapStruct mapper.
     */
    private SubscriptionDTO convertSubscriptionToDTO(Subscription subscription) {
        return subscriptionMapper.toDTO(subscription);
    }
}