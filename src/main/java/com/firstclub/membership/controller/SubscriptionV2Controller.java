package com.firstclub.membership.controller;

import com.firstclub.billing.dto.ProratedPreviewResponse;
import com.firstclub.billing.dto.SubscriptionV2Response;
import com.firstclub.billing.service.BillingSubscriptionService;
import com.firstclub.billing.service.ProrationCalculator;
import com.firstclub.membership.dto.SubscriptionRequestDTO;
import com.firstclub.platform.idempotency.annotation.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * V2 Subscription API — adds idempotency support for safe retries.
 *
 * <p>Clients must include an {@code Idempotency-Key} header (max 80 chars,
 * e.g. a UUID) on every {@code POST} request.  Duplicate requests with the
 * same key and identical body return the original response without creating a
 * second subscription.  Reusing a key with a different body returns HTTP 409.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/subscriptions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Subscription Management V2", description = "V2 subscription APIs with idempotency support")
public class SubscriptionV2Controller {

    private final BillingSubscriptionService billingSubscriptionService;
    private final ProrationCalculator prorationCalculator;

    @PostMapping
    @Idempotent(ttlHours = 24)
    @Operation(
        summary     = "Create subscription (idempotent)",
        description = """
            Creates a new subscription for the given user and plan.
            Requires an **Idempotency-Key** header (UUID recommended, max 80 chars).
            Duplicate requests with the same key and payload return the original
            response without executing the operation again.
            Reusing a key with a different payload returns HTTP 409.
            """,
        parameters  = @Parameter(
            name        = "Idempotency-Key",
            description = "Client-generated idempotency key (UUID recommended). Required.",
            in          = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
            required    = true,
            schema      = @Schema(type = "string", maxLength = 80, example = "7f9a1d2e-4c3b-4e5f-8a6b-9c0d1e2f3a4b")
        )
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Subscription created successfully"),
        @ApiResponse(responseCode = "200", description = "Duplicate request — original response replayed",
            headers = @Header(name = "Idempotency-Key", description = "Echoed idempotency key")),
        @ApiResponse(responseCode = "400", description = "Missing or invalid Idempotency-Key header / validation error"),
        @ApiResponse(responseCode = "409", description = "Idempotency-Key reused with a different request payload"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<SubscriptionV2Response> createSubscription(
            @Valid @RequestBody SubscriptionRequestDTO request) {
        log.info("V2: Creating subscription for user={} plan={}", request.getUserId(), request.getPlanId());
        SubscriptionV2Response created = billingSubscriptionService.createSubscriptionV2(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}/change-preview")
    @Operation(
        summary     = "Preview plan-change proration",
        description = "Returns an itemised cost breakdown for switching the subscription to a new plan today."
    )
    public ResponseEntity<ProratedPreviewResponse> changePreview(
            @PathVariable Long id,
            @RequestParam Long newPlanId) {
        return ResponseEntity.ok(prorationCalculator.preview(id, newPlanId));
    }
}
