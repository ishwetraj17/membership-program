package com.firstclub.membership.controller;

import com.firstclub.membership.dto.*;
import com.firstclub.membership.entity.User;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.service.SubscriptionService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.SmartValidator;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
    private final SubscriptionService subscriptionService;
    private final TierEvaluationService tierEvaluationService;
    private final SmartValidator validator;

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
        return ResponseEntity.ok(userService.getUserById(id)
                .orElseThrow(() -> MembershipException.userNotFound(id)));
    }

    @GetMapping("/email/{email}")
    @Operation(summary = "Get user by email")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email)
                .orElseThrow(() -> new MembershipException(
                        "User with email '" + email + "' not found",
                        "USER_NOT_FOUND",
                        org.springframework.http.HttpStatus.NOT_FOUND)));
    }

    @GetMapping
    @Operation(
        summary = "Get all users (paginated)",
        description = "Supports optional pagination: ?page=0&size=10&sort=id,desc. Omit parameters for defaults (page=0, size=20)."
    )
    public ResponseEntity<Page<UserDTO>> getAllUsers(
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(userService.getUsers(pageable));
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

        if (updates.containsKey("name"))        current.setName(asString(updates, "name"));
        if (updates.containsKey("phoneNumber")) current.setPhoneNumber(asString(updates, "phoneNumber"));
        if (updates.containsKey("address"))     current.setAddress(asString(updates, "address"));
        if (updates.containsKey("city"))        current.setCity(asString(updates, "city"));
        if (updates.containsKey("state"))       current.setState(asString(updates, "state"));
        if (updates.containsKey("pincode"))     current.setPincode(asString(updates, "pincode"));
        if (updates.containsKey("status")) {
            String statusValue = asString(updates, "status");
            try {
                current.setStatus(User.UserStatus.valueOf(statusValue));
            } catch (IllegalArgumentException e) {
                throw new MembershipException(
                    "Invalid status '" + statusValue + "'. Valid values: ACTIVE, INACTIVE, SUSPENDED",
                    "INVALID_STATUS_VALUE");
            }
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
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active subscription found"),
        @ApiResponse(responseCode = "404", description = "No active subscription or user not found")
    })
    public ResponseEntity<SubscriptionDTO> getActiveSubscription(@PathVariable Long userId) {
        requireUser(userId);
        return ResponseEntity.ok(subscriptionService.getActiveSubscription(userId)
                .orElseThrow(() -> new MembershipException(
                        "No active subscription for user " + userId,
                        "NO_ACTIVE_SUBSCRIPTION",
                        org.springframework.http.HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/{userId}/subscriptions")
    @Operation(summary = "Get user's subscription history")
    public ResponseEntity<List<SubscriptionDTO>> getUserSubscriptions(@PathVariable Long userId) {
        requireUser(userId);
        return ResponseEntity.ok(subscriptionService.getUserSubscriptions(userId));
    }

    @PostMapping("/{userId}/subscriptions")
    @Operation(summary = "Create subscription for user")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Subscription created"),
        @ApiResponse(responseCode = "409", description = "User already has active subscription")
    })
    public ResponseEntity<SubscriptionDTO> createSubscription(
            @PathVariable Long userId,
            @RequestBody SubscriptionRequestDTO request) throws MethodArgumentNotValidException {
        requireUser(userId);
        // Set userId from path before validation so @NotNull on userId passes
        request.setUserId(userId);
        var bindingResult = new BeanPropertyBindingResult(request, "request");
        validator.validate(request, bindingResult);
        if (bindingResult.hasErrors()) {
            throw new MethodArgumentNotValidException(null, bindingResult);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(subscriptionService.createSubscription(request));
    }

    @PutMapping("/{userId}/subscriptions/{subscriptionId}")
    @Operation(summary = "Update user's subscription")
    public ResponseEntity<SubscriptionDTO> updateSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionUpdateDTO updateDTO) {
        requireUserOwnsSubscription(userId, subscriptionId);
        return ResponseEntity.ok(subscriptionService.updateSubscription(subscriptionId, updateDTO));
    }

    @PutMapping("/{userId}/subscriptions/{subscriptionId}/upgrade")
    @Operation(summary = "Upgrade user's subscription")
    public ResponseEntity<SubscriptionDTO> upgradeSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody UpgradeRequest request) {
        requireUserOwnsSubscription(userId, subscriptionId);
        return ResponseEntity.ok(subscriptionService.upgradeSubscription(subscriptionId, request.getNewPlanId()));
    }

    @PutMapping("/{userId}/subscriptions/{subscriptionId}/cancel")
    @Operation(summary = "Cancel user's subscription")
    public ResponseEntity<SubscriptionDTO> cancelSubscription(
            @PathVariable Long userId,
            @PathVariable Long subscriptionId,
            @RequestBody(required = false) Map<String, String> body) {
        requireUserOwnsSubscription(userId, subscriptionId);
        String reason = body != null ? body.getOrDefault("reason", "User requested cancellation")
                                     : "User requested cancellation";
        return ResponseEntity.ok(subscriptionService.cancelSubscription(subscriptionId, reason));
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
        userService.getUserById(userId)
                .orElseThrow(() -> MembershipException.userNotFound(userId));
    }

    private void requireUserOwnsSubscription(Long userId, Long subscriptionId) {
        requireUser(userId);
        if (!subscriptionService.subscriptionBelongsToUser(subscriptionId, userId)) {
            throw MembershipException.subscriptionNotOwnedByUser(subscriptionId, userId);
        }
    }

    /** Extracts a String value from the PATCH map; returns 400 if the value is not a String. */
    private String asString(Map<String, Object> updates, String field) {
        Object value = updates.get(field);
        if (value != null && !(value instanceof String)) {
            throw new MembershipException(
                    "Field '" + field + "' must be a string, got: " + value.getClass().getSimpleName(),
                    "INVALID_FIELD_TYPE");
        }
        return (String) value;
    }
}
