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

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
    public void initializeDefaultData() {
        log.info("Starting membership system initialization...");
        
        // Only create if no tiers exist
        if (tierRepository.count() == 0) {
            MembershipTier[] defaultTiers = MembershipTier.getDefaultTiers();
            for (MembershipTier tier : defaultTiers) {
                MembershipTier savedTier = tierRepository.save(tier);
                log.info("Created tier: {} with {}% discount", savedTier.getName(), savedTier.getDiscountPercentage());
                
                // Create plans for each tier
                createDefaultPlansForTier(savedTier);
            }
            log.info("Membership system initialized successfully!");
        }
    }
    
    /**
     * Create sample users for demo purposes
     * 
     * These users help demonstrate the system functionality.
     * Added for now - might want to remove in production
     */
    private void createSampleUsers() {
        if (userService.getAllUsers().isEmpty()) {
            log.info("Creating sample users for demonstration...");
            
            // Sample user 1 - Tech professional from Bangalore
            UserDTO user1 = UserDTO.builder()
                .name("Karan Singh")
                .email("karan.singh@flipkart.com")
                .phoneNumber("9876543210")
                .address("12 HSR Layout")
                .city("Bangalore")
                .state("Karnataka")
                .pincode("560102")
                .build();
                
            // Sample user 2 - Business professional from Mumbai
            UserDTO user2 = UserDTO.builder()
                .name("Ananya Sharma")
                .email("ananya.sharma@tcs.com")
                .phoneNumber("9876543211")
                .address("23 Andheri West")
                .city("Mumbai")
                .state("Maharashtra")
                .pincode("400058")
                .build();
                
            // Sample user 3 - Startup founder from Delhi
            UserDTO user3 = UserDTO.builder()
                .name("Rohit Agarwal")
                .email("rohit.agarwal@zomato.com")
                .phoneNumber("9876543212")
                .address("45 Connaught Place")
                .city("New Delhi")
                .state("Delhi")
                .pincode("110001")
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
     * Get base pricing for tier levels
     * 
     * Pricing in INR based on Indian market research.
     * TODO: Move these to configuration file later
     */
    private BigDecimal getBasePriceForTier(Integer tierLevel) {
        switch (tierLevel) {
            case 1: return new BigDecimal("299"); // Silver
            case 2: return new BigDecimal("499"); // Gold  
            case 3: return new BigDecimal("799"); // Platinum
            default: return new BigDecimal("299");
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getAllPlans() {
        return planRepository.findAll().stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
    public List<MembershipPlanDTO> getActivePlans() {
        return planRepository.findByIsActiveTrue().stream()
            .map(this::convertPlanToDTO)
            .collect(Collectors.toList());
    }
    
    @Override
    @Transactional(readOnly = true)
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
    public List<MembershipTier> getAllTiers() {
        return tierRepository.findAll();
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipTier> getTierByName(String name) {
        return tierRepository.findByName(name.toUpperCase());
    }
    
    @Override
    @Transactional(readOnly = true)
    public Optional<MembershipTier> getTierById(Long id) {
        return tierRepository.findById(id);
    }
    
    @Override
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
    
    /**
     * Convert MembershipPlan entity to DTO with benefits
     * 
     * This method does a lot of work - might need to optimize later.
     * Combines plan data with tier benefits for API responses.
     */
    private MembershipPlanDTO convertPlanToDTO(MembershipPlan plan) {
        BigDecimal monthlyPrice = plan.getMonthlyPrice();
        BigDecimal savings = BigDecimal.ZERO;
        
        // Calculate savings for quarterly and yearly plans
        if (plan.getType() != MembershipPlan.PlanType.MONTHLY) {
            // Find monthly plan of same tier for comparison
            Optional<MembershipPlan> monthlyPlan = planRepository
                .findByTierAndIsActiveTrue(plan.getTier())
                .stream()
                .filter(p -> p.getType() == MembershipPlan.PlanType.MONTHLY)
                .findFirst();
                
            if (monthlyPlan.isPresent()) {
                savings = plan.calculateSavings(monthlyPlan.get().getPrice());
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
            .monthlyPrice(monthlyPrice)
            .savings(savings)
            .isActive(plan.getIsActive())
            .build();
    }
    
    /**
     * Convert Subscription entity to DTO with all details
     * 
     * Enhanced with null safety for robust API testing
     */
    private SubscriptionDTO convertSubscriptionToDTO(Subscription subscription) {
        if (subscription == null) {
            throw new MembershipException("Subscription cannot be null", "NULL_SUBSCRIPTION");
        }
        
        if (subscription.getUser() == null) {
            throw new MembershipException("Subscription must have a user", "NULL_USER");
        }
        
        if (subscription.getPlan() == null) {
            throw new MembershipException("Subscription must have a plan", "NULL_PLAN");
        }
        
        if (subscription.getPlan().getTier() == null) {
            throw new MembershipException("Plan must have a tier", "NULL_TIER");
        }
        
        return SubscriptionDTO.builder()
            .id(subscription.getId())
            .userId(subscription.getUser().getId())
            .userName(subscription.getUser().getName())
            .userEmail(subscription.getUser().getEmail())
            .planId(subscription.getPlan().getId())
            .planName(subscription.getPlan().getName())
            .planType(subscription.getPlan().getType().name())
            .tier(subscription.getPlan().getTier().getName())
            .tierLevel(subscription.getPlan().getTier().getLevel())
            .paidAmount(subscription.getPaidAmount())
            .status(subscription.getStatus())
            .startDate(subscription.getStartDate())
            .endDate(subscription.getEndDate())
            .nextBillingDate(subscription.getNextBillingDate())
            .autoRenewal(subscription.getAutoRenewal())
            .daysRemaining(subscription.getDaysRemaining())
            .isActive(subscription.isActive())
            .cancelledAt(subscription.getCancelledAt())
            .cancellationReason(subscription.getCancellationReason())
            .discountPercentage(subscription.getPlan().getTier().getDiscountPercentage())
            .freeDelivery(subscription.getPlan().getTier().getFreeDelivery())
            .exclusiveDeals(subscription.getPlan().getTier().getExclusiveDeals())
            .earlyAccess(subscription.getPlan().getTier().getEarlyAccess())
            .prioritySupport(subscription.getPlan().getTier().getPrioritySupport())
            .maxCouponsPerMonth(subscription.getPlan().getTier().getMaxCouponsPerMonth())
            .deliveryDays(subscription.getPlan().getTier().getDeliveryDays())
            .additionalBenefits(subscription.getPlan().getTier().getAdditionalBenefits())
            .build();
    }
}