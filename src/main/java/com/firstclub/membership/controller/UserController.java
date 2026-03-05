package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for user management operations
 * 
 * Handles user CRUD operations with proper validation.
 * All endpoints return consistent JSON responses.
 * 
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "User Management", description = "APIs for managing FirstClub users")
public class UserController {
    
    private final UserService userService;
    private final MembershipService membershipService;
    
    @PostMapping
    @Operation(summary = "Create new user", description = "Creates a new user account with Indian validation")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "User created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input or email already exists")
    })
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody UserDTO userDTO) {
        log.info("Creating new user: {}", userDTO.getEmail());
        UserDTO createdUser = userService.createUser(userDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }
    
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#id, authentication)")
    @Operation(summary = "Get user by ID", description = "Retrieves user information by ID")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User found successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserById(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long id) {
        return userService.getUserById(id)
            .map(user -> ResponseEntity.ok(user))
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get user by email", description = "Finds user by email address")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User found successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserByEmail(
            @Parameter(description = "User email", example = "karan.singh@flipkart.com") @PathVariable String email) {
        return userService.getUserByEmail(email)
            .map(user -> ResponseEntity.ok(user))
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get all users", description = "Retrieves registered users with optional pagination")
    @ApiResponse(responseCode = "200", description = "Users retrieved successfully")
    public ResponseEntity<Page<UserDTO>> getAllUsers(
            @Parameter(description = "Page number (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Sort by field") @RequestParam(defaultValue = "createdAt") String sort,
            @Parameter(description = "Sort direction: asc or desc") @RequestParam(defaultValue = "desc") String direction) {
        Sort.Direction sortDir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDir, sort));
        Page<UserDTO> users = userService.getAllUsersPaged(pageable);
        log.debug("Retrieved {} users (page {}/{})", users.getNumberOfElements(), page, users.getTotalPages());
        return ResponseEntity.ok(users);
    }
    
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#id, authentication)")
    @Operation(summary = "Update user", description = "Updates user information")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> updateUser(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long id,
            @Valid @RequestBody UserDTO userDTO) {
        log.info("Updating user: {}", id);
        UserDTO updatedUser = userService.updateUser(id, userDTO);
        return ResponseEntity.ok(updatedUser);
    }
    
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#id, authentication)")
    @Operation(summary = "Partially update user", description = "Updates specific user fields")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> partialUpdateUser(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long id,
            @RequestBody Map<String, Object> updates) {
        log.info("Partially updating user: {} with fields: {}", id, updates.keySet());
        
        try {
            // Get current user
            UserDTO currentUser = userService.getUserById(id).orElseThrow(() -> 
                new RuntimeException("User not found with id: " + id));
            
            // Apply partial updates
            if (updates.containsKey("name")) {
                currentUser.setName((String) updates.get("name"));
            }
            if (updates.containsKey("phoneNumber")) {
                currentUser.setPhoneNumber((String) updates.get("phoneNumber"));
            }
            if (updates.containsKey("address")) {
                currentUser.setAddress((String) updates.get("address"));
            }
            if (updates.containsKey("city")) {
                currentUser.setCity((String) updates.get("city"));
            }
            if (updates.containsKey("state")) {
                currentUser.setState((String) updates.get("state"));
            }
            if (updates.containsKey("pincode")) {
                currentUser.setPincode((String) updates.get("pincode"));
            }
            if (updates.containsKey("status")) {
                currentUser.setStatus(User.UserStatus.valueOf((String) updates.get("status")));
            }
            
            // Update with full object
            UserDTO updatedUser = userService.updateUser(id, currentUser);
            return ResponseEntity.ok(updatedUser);
        } catch (Exception e) {
            log.error("Error during partial update for user: {}", id, e);
            throw e;
        }
    }
    
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete user", description = "Deletes a user account")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "User deleted successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long id) {
        log.info("Deleting user: {}", id);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
    
    // ==== SUBSCRIPTION MANAGEMENT ENDPOINTS FOR USERS ====
    
    @GetMapping("/{userId}/subscription")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Get user's active subscription", 
        description = "Get the current active subscription for a specific user. Returns 404 if no active subscription exists."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "User not found or no active subscription")
    })
    public ResponseEntity<SubscriptionDTO> getUserActiveSubscription(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId) {
        // First verify user exists
        if (userService.getUserById(userId).isEmpty()) {
            log.warn("User not found for subscription query: {}", userId);
            return ResponseEntity.notFound().build();
        }
        
        return membershipService.getActiveSubscription(userId)
            .map(subscription -> {
                log.info("Found active subscription for user: {} - Plan: {}", userId, subscription.getPlanName());
                return ResponseEntity.ok(subscription);
            })
            .orElseGet(() -> {
                log.info("No active subscription found for user: {}", userId);
                return ResponseEntity.notFound().build();
            });
    }
    
    @GetMapping("/{userId}/subscriptions")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Get all user subscriptions", 
        description = "Get complete subscription history for a specific user including active, expired, and cancelled subscriptions."
    )
    @ApiResponse(responseCode = "200", description = "User subscriptions retrieved successfully")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId) {
        // Verify user exists
        if (userService.getUserById(userId).isEmpty()) {
            log.warn("User not found for subscription history query: {}", userId);
            return ResponseEntity.notFound().build();
        }
        
        List<SubscriptionDTO> subscriptions = membershipService.getUserSubscriptions(userId);
        log.info("Retrieved {} subscription records for user: {}", subscriptions.size(), userId);
        return ResponseEntity.ok(subscriptions);
    }
    
    @PostMapping("/{userId}/subscriptions")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Create subscription for user", 
        description = "Create a new subscription for the specified user. Only one active subscription allowed per user."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Subscription created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or user already has active subscription"),
        @ApiResponse(responseCode = "404", description = "User or plan not found")
    })
    public ResponseEntity<SubscriptionDTO> createUserSubscription(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId,
            @Valid @RequestBody SubscriptionRequestDTO request) {
        
        // Verify user exists
        if (userService.getUserById(userId).isEmpty()) {
            log.error("Cannot create subscription - user not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
        
        // Ensure the request has the correct user ID
        request.setUserId(userId);
        
        log.info("Creating subscription for user: {} with plan: {}", userId, request.getPlanId());
        SubscriptionDTO subscription = membershipService.createSubscription(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }
    
    @PutMapping("/{userId}/subscriptions/{subscriptionId}")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Update user's subscription", 
        description = "Update subscription settings like auto-renewal, plan changes, or status changes."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid update request"),
        @ApiResponse(responseCode = "404", description = "User or subscription not found"),
        @ApiResponse(responseCode = "403", description = "Subscription doesn't belong to user")
    })
    public ResponseEntity<SubscriptionDTO> updateUserSubscription(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId,
            @Parameter(description = "Subscription ID", example = "1") @Positive @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionUpdateDTO updateDTO) {
        
        // Verify user exists
        if (userService.getUserById(userId).isEmpty()) {
            log.error("Cannot update subscription - user not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
        
        // Verify subscription belongs to user
        List<SubscriptionDTO> userSubscriptions = membershipService.getUserSubscriptions(userId);
        boolean subscriptionBelongsToUser = userSubscriptions.stream()
            .anyMatch(sub -> sub.getId().equals(subscriptionId));
            
        if (!subscriptionBelongsToUser) {
            log.error("Subscription {} does not belong to user {}", subscriptionId, userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        log.info("Updating subscription: {} for user: {}", subscriptionId, userId);
        SubscriptionDTO updatedSubscription = membershipService.updateSubscription(subscriptionId, updateDTO);
        return ResponseEntity.ok(updatedSubscription);
    }
    
    @PutMapping("/{userId}/subscriptions/{subscriptionId}/upgrade/{newPlanId}")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Upgrade user's subscription", 
        description = "Upgrade an active subscription to a higher tier plan or longer duration."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription upgraded successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid upgrade (downgrade not allowed or same plan)"),
        @ApiResponse(responseCode = "404", description = "User, subscription, or plan not found"),
        @ApiResponse(responseCode = "403", description = "Subscription doesn't belong to user")
    })
    public ResponseEntity<SubscriptionDTO> upgradeUserSubscription(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId,
            @Parameter(description = "Subscription ID", example = "1") @Positive @PathVariable Long subscriptionId,
            @Parameter(description = "New Plan ID", example = "4") @Positive @PathVariable Long newPlanId) {
        
        // Verify user exists
        if (userService.getUserById(userId).isEmpty()) {
            log.error("Cannot upgrade subscription - user not found: {}", userId);
            return ResponseEntity.notFound().build();
        }
        
        // Verify subscription belongs to user
        List<SubscriptionDTO> userSubscriptions = membershipService.getUserSubscriptions(userId);
        boolean subscriptionBelongsToUser = userSubscriptions.stream()
            .anyMatch(sub -> sub.getId().equals(subscriptionId));
            
        if (!subscriptionBelongsToUser) {
            log.error("Subscription {} does not belong to user {}", subscriptionId, userId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        log.info("Upgrading subscription: {} for user: {} to plan: {}", subscriptionId, userId, newPlanId);
        SubscriptionDTO upgradedSubscription = membershipService.upgradeSubscription(subscriptionId, newPlanId);
        return ResponseEntity.ok(upgradedSubscription);
    }

    @PostMapping("/{userId}/subscriptions/{subscriptionId}/cancel")
    @PreAuthorize("hasRole('ADMIN') or @securityService.isSameUser(#userId, authentication)")
    @Operation(
        summary = "Cancel user's subscription", 
        description = "Cancel an active subscription for the specified user."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Subscription cancelled successfully"),
        @ApiResponse(responseCode = "400", description = "Cannot cancel inactive subscription"),
        @ApiResponse(responseCode = "404", description = "User or subscription not found"),
        @ApiResponse(responseCode = "403", description = "Subscription doesn't belong to user")
    })
    public ResponseEntity<SubscriptionDTO> cancelUserSubscription(
            @Parameter(description = "User ID", example = "1") @Positive @PathVariable Long userId,
            @Parameter(description = "Subscription ID", example = "1") @Positive @PathVariable Long subscriptionId,
            @RequestBody(required = false) java.util.Map<String, String> request) {
        
        // Verify user exists and subscription belongs to them
        if (userService.getUserById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        List<SubscriptionDTO> userSubscriptions = membershipService.getUserSubscriptions(userId);
        boolean subscriptionBelongsToUser = userSubscriptions.stream()
            .anyMatch(sub -> sub.getId().equals(subscriptionId));
            
        if (!subscriptionBelongsToUser) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        
        String reason = request != null ? request.get("reason") : "User requested cancellation";
        log.info("Cancelling subscription: {} for user: {} - Reason: {}", subscriptionId, userId, reason);
        
        SubscriptionDTO cancelledSubscription = membershipService.cancelSubscription(subscriptionId, reason);
        return ResponseEntity.ok(cancelledSubscription);
    }
}