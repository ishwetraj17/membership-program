package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.*;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.mapper.MembershipPlanMapper;
import com.firstclub.membership.mapper.MembershipTierMapper;
import com.firstclub.membership.mapper.SubscriptionMapper;
import com.firstclub.membership.repository.*;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
@Transactional
public class MembershipServiceImpl implements MembershipService {
    
    private final MembershipTierRepository tierRepository;
    private final MembershipPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserService userService;
    private final MembershipPlanMapper planMapper;
    private final MembershipTierMapper tierMapper;
    private final SubscriptionMapper subscriptionMapper;

    @Value("${membership.pricing.silver:299}")
    private BigDecimal silverBasePrice;

    @Value("${membership.pricing.gold:499}")
    private BigDecimal goldBasePrice;

    @Value("${membership.pricing.platinum:799}")
    private BigDecimal platinumBasePrice;
    
    /**
     * Initialize default data on application startup
     * 
     * Creates tiers and plans, plus some sample users for demo.
     * This runs every time the app starts since we're using H2 in-memory.
     */
    @PostConstruct
    public void init() {
        initializeDefaultData();
        createSampleUsers();
    }
    
    @Override
    @CacheEvict(value = {"plans", "plansByTier", "plansByType", "tiers"}, allEntries = true)
    public void initializeDefaultData() {
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
     * Create sample users for demo purposes
     */
    private void createSampleUsers() {
        if (userService.getAllUsers().isEmpty()) {
            log.info("Creating sample users for demonstration...");
            
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
            
        // Quarterly plan - 5% discount
        MembershipPlan quarterlyPlan = MembershipPlan.builder()
            .name(tier.getName() + " Quarterly")
            .description("Quarterly " + tier.getName() + " membership with savings")
            .type(MembershipPlan.PlanType.QUARTERLY)
            .price(basePrice.multiply(new BigDecimal("3")).multiply(new BigDecimal("0.95")))
            .durationInMonths(3)
            .tier(tier)
            .isActive(true)
            .build();
            
        // Yearly plan - 15% discount
        MembershipPlan yearlyPlan = MembershipPlan.builder()
            .name(tier.getName() + " Yearly")
            .description("Yearly " + tier.getName() + " membership with maximum savings")
            .type(MembershipPlan.PlanType.YEARLY)
            .price(basePrice.multiply(new BigDecimal("12")).multiply(new BigDecimal("0.85")))
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
     */
    private BigDecimal getBasePriceForTier(Integer tierLevel) {
        return switch (tierLevel) {
            case 1 -> silverBasePrice;
            case 2 -> goldBasePrice;
            case 3 -> platinumBasePrice;
            default -> silverBasePrice;
        };
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable("plans")
    public List<MembershipPlanDTO> getAllPlans() {
        return planRepository.findAll().stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable("plans")
    public List<MembershipPlanDTO> getActivePlans() {
        return planRepository.findByIsActiveTrue().stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "plansByTier", key = "#tierName.toUpperCase()")
    public List<MembershipPlanDTO> getPlansByTier(String tierName) {
        MembershipTier tier = tierRepository.findByName(tierName.toUpperCase())
            .orElseThrow(() -> MembershipException.tierNotFound(tierName));
            
        return planRepository.findByTierAndIsActiveTrue(tier).stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getPlansByTierId(Long tierId) {
        MembershipTier tier = tierRepository.findById(tierId)
            .orElseThrow(() -> MembershipException.tierNotFound("ID: " + tierId));
            
        return planRepository.findByTierAndIsActiveTrue(tier).stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "plansByType", key = "#type.name()")
    public List<MembershipPlanDTO> getPlansByType(MembershipPlan.PlanType type) {
        return planRepository.findByTypeAndIsActiveTrue(type).stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipPlanDTO> getPlanById(Long id) {
        return planRepository.findById(id).map(this::convertPlanToDTO);
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable("tiers")
    public List<MembershipTierDTO> getAllTiers() {
        return tierRepository.findAll().stream()
            .map(tierMapper::toDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "tiers", key = "#name.toUpperCase()")
    public Optional<MembershipTierDTO> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase()).map(tierMapper::toDTO);
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipTierDTO> getTierById(Long id) {
        return tierRepository.findById(id).map(tierMapper::toDTO);
    }
    
    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
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
        
        return convertSubscriptionToDTO(savedSubscription);
    }
    
    @Override
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
                
                // Update subscription end date based on new plan duration
                LocalDateTime newEndDate = calculateNewEndDate(subscription.getStartDate(), newPlan.getDurationInMonths());
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
            
            // Validate status transition
            if (isValidStatusTransition(subscription.getStatus(), newStatus)) {
                subscription.setStatus(newStatus);
                
                // Handle specific status change logic
                if (newStatus == Subscription.SubscriptionStatus.CANCELLED) {
                    subscription.setCancelledAt(LocalDateTime.now());
                    subscription.setCancellationReason(
                        updateDTO.getReason() != null ? updateDTO.getReason() : "Updated via API"
                    );
                    subscription.setAutoRenewal(false);
                }
                
                hasChanges = true;
                log.info("Updated subscription {} status to: {}", subscriptionId, newStatus);
            } else {
                throw new MembershipException(
                    "Invalid status transition from " + subscription.getStatus() + " to " + newStatus, 
                    "INVALID_STATUS_TRANSITION"
                );
            }
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
     * Helper method to calculate new end date based on plan duration
     */
    private LocalDateTime calculateNewEndDate(LocalDateTime startDate, Integer durationInMonths) {
        return startDate.plusMonths(durationInMonths);
    }
    
    /**
     * Calculate pro-rated billing amount for plan changes
     * 
     * Calculates the difference between plans based on remaining time in current subscription.
     * Positive amount = user owes money (upgrade), Negative = user gets credit (downgrade)
     */
    private BigDecimal calculateProRatedAmount(Subscription subscription, MembershipPlan currentPlan, MembershipPlan newPlan) {
        LocalDateTime now = LocalDateTime.now();
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(subscription.getStartDate(), subscription.getEndDate());
        long remainingDays = java.time.temporal.ChronoUnit.DAYS.between(now, subscription.getEndDate());
        
        if (remainingDays <= 0) {
            // If subscription expired, charge full new plan price
            return newPlan.getPrice();
        }
        
        // Calculate unused portion of current plan
        BigDecimal unusedCurrentPlanValue = currentPlan.getPrice()
            .multiply(new BigDecimal(remainingDays))
            .divide(new BigDecimal(totalDays), 2, java.math.RoundingMode.HALF_UP);
            
        // Calculate proportional cost for remaining time on new plan
        BigDecimal newPlanProportionalCost = newPlan.getPrice()
            .multiply(new BigDecimal(remainingDays))
            .divide(new BigDecimal(totalDays), 2, java.math.RoundingMode.HALF_UP);
            
        // Return the difference (positive = user pays more, negative = user gets credit)
        BigDecimal proRatedDifference = newPlanProportionalCost.subtract(unusedCurrentPlanValue);
        
        log.debug("Pro-rated calculation: Current plan unused value: {}, New plan proportional cost: {}, Difference: {}", 
            unusedCurrentPlanValue, newPlanProportionalCost, proRatedDifference);
            
        return proRatedDifference;
    }
    
    /**
     * Validate if status transition is allowed
     */
    private boolean isValidStatusTransition(Subscription.SubscriptionStatus from, Subscription.SubscriptionStatus to) {
        // Define valid transitions
        return switch (from) {
            case ACTIVE -> to == Subscription.SubscriptionStatus.CANCELLED || 
                          to == Subscription.SubscriptionStatus.SUSPENDED ||
                          to == Subscription.SubscriptionStatus.EXPIRED;
            case PENDING -> to == Subscription.SubscriptionStatus.ACTIVE || 
                           to == Subscription.SubscriptionStatus.CANCELLED;
            case SUSPENDED -> to == Subscription.SubscriptionStatus.ACTIVE || 
                             to == Subscription.SubscriptionStatus.CANCELLED;
            case EXPIRED -> to == Subscription.SubscriptionStatus.ACTIVE; // Allow reactivation
            case CANCELLED -> false; // Cannot transition from cancelled
        };
    }
    
    @Override
    public SubscriptionDTO cancelSubscription(Long subscriptionId, String reason) {
        log.info("Cancelling subscription: {} with reason: {}", subscriptionId, reason);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> MembershipException.subscriptionNotFound(subscriptionId));
            
        if (subscription.getStatus() != Subscription.SubscriptionStatus.ACTIVE) {
            throw new MembershipException("Cannot cancel non-active subscription", "INVALID_SUBSCRIPTION_STATUS");
        }
        
        subscription.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscription.setCancelledAt(LocalDateTime.now());
        subscription.setCancellationReason(reason);
        subscription.setAutoRenewal(false);
        subscription.setUpdatedAt(LocalDateTime.now());
        
        Subscription cancelledSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription cancelled successfully: {} with reason: {}", subscriptionId, reason);
        
        return convertSubscriptionToDTO(cancelledSubscription);
    }
    
    @Override
    public SubscriptionDTO renewSubscription(Long subscriptionId) {
        log.info("Renewing subscription: {}", subscriptionId);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found", "SUBSCRIPTION_NOT_FOUND"));
            
        if (subscription.getStatus() != Subscription.SubscriptionStatus.EXPIRED) {
            throw new MembershipException("Only expired subscriptions can be renewed", "INVALID_SUBSCRIPTION_STATUS");
        }
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newEndDate = now.plusMonths(subscription.getPlan().getDurationInMonths());
        
        subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        subscription.setStartDate(now);
        subscription.setEndDate(newEndDate);
        subscription.setNextBillingDate(newEndDate);
        
        Subscription renewedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription renewed successfully: {}", subscriptionId);
        
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

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionDTO> getAllSubscriptionsPaged(Pageable pageable) {
        return subscriptionRepository.findAll(pageable)
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
        
        // Calculate pro-rated amount for upgrade
        BigDecimal priceDifference = newPlan.getPrice().subtract(currentPlan.getPrice());
        subscription.setPlan(newPlan);
        subscription.setPaidAmount(subscription.getPaidAmount().add(priceDifference));
        subscription.setUpdatedAt(LocalDateTime.now());
        
        Subscription upgradedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription upgraded successfully: {} to plan: {}", subscriptionId, newPlan.getName());
        
        return convertSubscriptionToDTO(upgradedSubscription);
    }
    
    @Override
    public SubscriptionDTO downgradeSubscription(Long subscriptionId, Long newPlanId) {
        log.info("Downgrading subscription: {} to plan: {}", subscriptionId, newPlanId);
        
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
            .orElseThrow(() -> new MembershipException("Subscription not found", "SUBSCRIPTION_NOT_FOUND"));
            
        MembershipPlan newPlan = planRepository.findById(newPlanId)
            .orElseThrow(() -> new MembershipException("Plan not found", "PLAN_NOT_FOUND"));
            
        if (newPlan.getTier().getLevel() >= subscription.getPlan().getTier().getLevel()) {
            throw new MembershipException("New plan must be of lower tier", "INVALID_DOWNGRADE");
        }
        
        subscription.setPlan(newPlan);
        
        Subscription downgradedSubscription = subscriptionRepository.save(subscription);
        log.info("Subscription downgraded successfully: {}", subscriptionId);
        
        return convertSubscriptionToDTO(downgradedSubscription);
    }
    
    @Override
    public void processExpiredSubscriptions() {
        log.info("Processing expired subscriptions...");
        
        List<Subscription> expiredSubscriptions = subscriptionRepository.findByStatus(Subscription.SubscriptionStatus.ACTIVE)
            .stream()
            .filter(sub -> sub.getEndDate().isBefore(LocalDateTime.now()))
            .collect(Collectors.toList());
            
        expiredSubscriptions.forEach(sub -> {
            sub.setStatus(Subscription.SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            log.info("Marked subscription as expired: {}", sub.getId());
        });
        
        log.info("Processed {} expired subscriptions", expiredSubscriptions.size());
    }
    
    @Override
    public void processRenewals() {
        log.info("Processing subscription renewals...");
        
        List<Subscription> renewalSubscriptions = subscriptionRepository
            .findSubscriptionsForRenewal(LocalDateTime.now().plusDays(1));
            
        renewalSubscriptions.forEach(sub -> {
            try {
                LocalDateTime newEndDate = sub.getEndDate().plusMonths(sub.getPlan().getDurationInMonths());
                sub.setEndDate(newEndDate);
                sub.setNextBillingDate(newEndDate);
                subscriptionRepository.save(sub);
                log.info("Renewed subscription: {}", sub.getId());
            } catch (Exception e) {
                log.error("Failed to renew subscription: {}", sub.getId(), e);
            }
        });
        
        log.info("Processed {} subscription renewals", renewalSubscriptions.size());
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
                .availablePlans(planRepository.findByIsActiveTrue().size())
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
                .totalActivePlans(planRepository.findByIsActiveTrue().size())
                .build())
            .summary(AnalyticsDTO.SummaryDTO.builder()
                .totalSubscriptions(total)
                .activeSubscriptions(active)
                .generatedAt(LocalDateTime.now())
                .build())
            .build();
    }

    /**
     * Convert plan to DTO using MapStruct mapper, then set computed fields.
     */
    private MembershipPlanDTO convertPlanToDTO(MembershipPlan plan) {
        MembershipPlanDTO dto = planMapper.toDTO(plan);

        BigDecimal monthlyPrice = plan.getMonthlyPrice();
        dto.setMonthlyPrice(monthlyPrice);

        BigDecimal savings = BigDecimal.ZERO;
        if (plan.getType() != MembershipPlan.PlanType.MONTHLY) {
            Optional<MembershipPlan> monthlyPlan = planRepository
                .findByTierAndIsActiveTrue(plan.getTier())
                .stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst();

            if (monthlyPlan.isPresent()) {
                savings = plan.calculateSavings(monthlyPlan.get().getPrice());
            }
        }
        dto.setSavings(savings);
        return dto;
    }

    /**
     * Convert Subscription entity to DTO using MapStruct mapper.
     */
    private SubscriptionDTO convertSubscriptionToDTO(Subscription subscription) {
        return subscriptionMapper.toDTO(subscription);
    }
}