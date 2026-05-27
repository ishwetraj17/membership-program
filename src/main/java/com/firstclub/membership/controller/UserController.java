package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.MembershipService;
import com.firstclub.membership.service.TierEvaluationService;
import com.firstclub.membership.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Management", description = "User accounts and their subscriptions")
public class UserController {

    private final UserService userService;
    private final MembershipService membershipService;
    private final TierEvaluationService tierEvaluationService;

    // ─── User CRUD ────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create user")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "User created"),
        @ApiResponse(responseCode = "400", description = "Validation error or duplicate email")
    })
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody UserDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(dto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserById(@PathVariable Long id) {
        return userService.getUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/email/{email}")
    @Operation(summary = "Get user by email")
    public ResponseEntity<UserDTO> getUserByEmail(@PathVariable String email) {
        return userService.getUserByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "Get all users")
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user (full replace)")
    public ResponseEntity<UserDTO> updateUser(@PathVariable Long id, @Valid @RequestBody UserDTO dto) {
        return ResponseEntity.ok(userService.updateUser(id, dto));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update user")
    public ResponseEntity<UserDTO> partialUpdateUser(
            @PathVariable Long id,
            @RequestBody Map<String, Object> updates) {

        UserDTO current = userService.getUserById(id)
                .orElseThrow(() -> MembershipException.userNotFound(id));

        if (updates.containsKey("name"))        current.setName((String) updates.get("name"));
        if (updates.containsKey("phoneNumber")) current.setPhoneNumber((String) updates.get("phoneNumber"));
        if (updates.containsKey("address"))     current.setAddress((String) updates.get("address"));
        if (updates.containsKey("city"))        current.setCity((String) updates.get("city"));
        if (updates.containsKey("state"))       current.setState((String) updates.get("state"));
        if (updates.containsKey("pincode"))     current.setPincode((String) updates.get("pincode"));
        if (updates.containsKey("status")) {
            current.setStatus(User.UserStatus.valueOf((String) updates.get("status")));
        }

        return ResponseEntity.ok(userService.updateUser(id, current));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete user")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "User deleted"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "400", description = "User has active subscriptions")
    })
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ─── User-scoped subscription endpoints ───────────────────────────────────

    @GetMapping("/{userId}/subscription")
    @Operation(summary = "Get user's active subscription")
    public ResponseEntity<SubscriptionDTO> getActiveSubscription(@PathVariable Long userId) {
        requireUser(userId);
        return membershipService.getActiveSubscription(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{userId}/subscriptions")
    @Operation(summary = "Get user's subscription history")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(@PathVariable Long userId) {
        requireUser(userId);
        return ResponseEntity.ok(membershipService.getUserSubscriptions(userId));
    }

    @PostMapping("/{userId}/subscriptions")
    @Operation(summary = "Create subscription for user")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "409", description = "User already has active subscription")
    })
    public ResponseEntity<SubscriptionDTO> createSubscription(
            @PathVariable Long userId,
            @Valid @RequestBody SubscriptionRequestDTO request) {
        requireUser(userId);
        request.setUserId(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(membershipService.createSubscription(request));
    }

    @PutMapping("/{userId}/subscriptions/{subscriptionId}")
    @Operation(summary = "Update user's subscription")
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionUpdateDTO updateDTO) {
        requireUserOwnsSubscription(userId, subscriptionId);
        return ResponseEntity.ok(membershipService.updateSubscription(subscriptionId, updateDTO));
    }

    @PutMapping("/{userId}/subscriptions/{subscriptionId}/upgrade")
    @Operation(summary = "Upgrade user's subscription")
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @RequestBody Map<String, Long> body) {
        requireUserOwnsSubscription(userId, subscriptionId);
        return ResponseEntity.ok(membershipService.upgradeSubscription(subscriptionId, body.get("newPlanId")));
    }

    @PostMapping("/{userId}/subscriptions/{subscriptionId}/cancel")
    @Operation(summary = "Cancel user's subscription")
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @RequestBody(required = false) Map<String, String> body) {
        requireUserOwnsSubscription(userId, subscriptionId);
        String reason = body != null ? body.getOrDefault("reason", "User requested cancellation")
                                     : "User requested cancellation";
        return ResponseEntity.ok(membershipService.cancelSubscription(subscriptionId, reason));
    }

    // ─── Tier eligibility ─────────────────────────────────────────────────────

    @GetMapping("/{userId}/tier-eligibility")
    @Operation(
        summary = "Evaluate tier eligibility",
        description = "Returns the highest tier the user currently qualifies for based on order history."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Eligibility evaluated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<TierEligibilityResult> getTierEligibility(
            @Parameter(description = "User ID", example = "1") @PathVariable Long userId) {
        requireUser(userId);
        return ResponseEntity.ok(tierEvaluationService.evaluateEligibleTier(userId));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void requireUser(Long userId) {
        if (userService.getUserById(userId).isEmpty()) {
            throw MembershipException.userNotFound(userId);
        }
    }

    private void requireUserOwnsSubscription(Long userId, Long subscriptionId) {
        requireUser(userId);
        boolean owned = membershipService.getUserSubscriptions(userId).stream()
                .anyMatch(s -> s.getId().equals(subscriptionId));
        if (!owned) {
            throw MembershipException.subscriptionNotOwnedByUser(subscriptionId, userId);
        }
    }
}
