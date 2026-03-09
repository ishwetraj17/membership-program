package com.firstclub.merchant.controller;

import com.firstclub.merchant.dto.MerchantUserCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantUserResponseDTO;
import com.firstclub.merchant.service.MerchantUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin REST API for managing users within a merchant/tenant.
 *
 * All endpoints require ADMIN role.
 * Base path: /api/v2/admin/merchants/{merchantId}/users
 *
 * Implemented by Shwet Raj
 */
@RestController
@RequestMapping("/api/v2/admin/merchants/{merchantId}/users")
@RequiredArgsConstructor
@Validated
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Merchant Users Admin", description = "Admin APIs for managing users within a merchant tenant (v2)")
public class MerchantUserAdminController {

    private final MerchantUserService merchantUserService;

    @PostMapping
    @Operation(summary = "Add user to merchant", description = "Assigns an existing user to a merchant with a specified role")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "User added successfully"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Merchant or user not found"),
        @ApiResponse(responseCode = "409", description = "User already assigned to this merchant")
    })
    public ResponseEntity<MerchantUserResponseDTO> addUserToMerchant(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long merchantId,
            @Valid @RequestBody MerchantUserCreateRequestDTO request) {
        log.info("Admin: adding user {} to merchant {}", request.getUserId(), merchantId);
        MerchantUserResponseDTO response = merchantUserService.addUserToMerchant(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List merchant users", description = "Returns all users assigned to the merchant")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Users retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Merchant not found")
    })
    public ResponseEntity<List<MerchantUserResponseDTO>> listMerchantUsers(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long merchantId) {
        return ResponseEntity.ok(merchantUserService.listMerchantUsers(merchantId));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Remove user from merchant", description = "Removes a user from the merchant. The last OWNER cannot be removed.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "User removed successfully"),
        @ApiResponse(responseCode = "400", description = "Cannot remove last owner"),
        @ApiResponse(responseCode = "404", description = "Merchant or user assignment not found")
    })
    public ResponseEntity<Void> removeUserFromMerchant(
            @Parameter(description = "Merchant ID", example = "1")
            @Positive @PathVariable Long merchantId,
            @Parameter(description = "User ID", example = "1")
            @Positive @PathVariable Long userId) {
        log.info("Admin: removing user {} from merchant {}", userId, merchantId);
        merchantUserService.removeUserFromMerchant(merchantId, userId);
        return ResponseEntity.noContent().build();
    }
}
