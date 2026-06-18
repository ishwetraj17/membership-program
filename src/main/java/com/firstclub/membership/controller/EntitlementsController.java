package com.firstclub.membership.controller;

import com.firstclub.membership.dto.EntitlementsDTO;
import com.firstclub.membership.security.AccessGuard;
import com.firstclub.membership.service.EntitlementsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The commerce-integration contract: checkout asks "what is this user entitled to?".
 * Cache-first and fail-open — a membership outage degrades to non-member, never an error.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Entitlements", description = "Membership entitlements for the checkout/commerce platform")
public class EntitlementsController {

    private final EntitlementsService entitlementsService;
    private final AccessGuard accessGuard;

    @GetMapping("/{userId}/entitlements")
    @Operation(summary = "Get a user's current membership entitlements",
            description = "Stable contract for the commerce platform. Cache-backed and fail-open: "
                    + "returns non-member entitlements rather than an error if the lookup degrades.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Entitlements returned (always, even when degraded)"),
        @ApiResponse(responseCode = "403", description = "Cannot read another user's entitlements")
    })
    public ResponseEntity<EntitlementsDTO> entitlements(@PathVariable Long userId) {
        accessGuard.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(entitlementsService.getEntitlements(userId));
    }
}
