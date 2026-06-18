package com.firstclub.membership.controller;

import com.firstclub.membership.dto.SavingsSummaryDTO;
import com.firstclub.membership.security.AccessGuard;
import com.firstclub.membership.service.SavingsService;
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
 * Member-facing savings tracker — lifetime/monthly totals and by-type / by-category breakdowns,
 * all aggregated from the auditable savings ledger.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Savings", description = "Member savings tracker")
public class SavingsController {

    private final SavingsService savingsService;
    private final AccessGuard accessGuard;

    @GetMapping("/{userId}/savings")
    @Operation(summary = "Get a member's savings summary",
            description = "Lifetime and current-month savings, broken down by benefit type and category.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Savings summary"),
        @ApiResponse(responseCode = "403", description = "Cannot read another user's savings")
    })
    public ResponseEntity<SavingsSummaryDTO> savings(@PathVariable Long userId) {
        accessGuard.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(savingsService.getUserSavings(userId));
    }
}
