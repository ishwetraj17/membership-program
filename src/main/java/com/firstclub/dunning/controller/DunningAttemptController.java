package com.firstclub.dunning.controller;

import com.firstclub.dunning.entity.DunningAttempt;
import com.firstclub.dunning.service.DunningServiceV2;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Operational endpoints for managing individual dunning attempts.
 *
 * <p>All paths are prefixed with
 * {@code /api/v2/merchants/{merchantId}/dunning-attempts}.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/dunning-attempts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Dunning Attempts (v2)",
     description = "Operational endpoints to inspect and manage individual dunning attempt records")
public class DunningAttemptController {

    private final DunningServiceV2 dunningServiceV2;

    @PostMapping("/{attemptId}/force-retry")
    @Operation(
        summary = "Force-retry a failed dunning attempt",
        description = "Creates an immediate SCHEDULED dunning attempt based on the specified FAILED "
                + "attempt. Use this for ops intervention when automated retries are exhausted "
                + "or when a subscriber confirms they have updated their payment method. "
                + "The source attempt must be in FAILED state and must belong to a subscription "
                + "within the given merchant.")
    public ResponseEntity<DunningAttempt> forceRetry(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable Long merchantId,
            @Parameter(description = "ID of the FAILED dunning attempt to retry", required = true)
            @PathVariable Long attemptId) {
        DunningAttempt newAttempt = dunningServiceV2.forceRetry(merchantId, attemptId);
        return ResponseEntity.ok(newAttempt);
    }
}
