package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CheckoutQuoteRequest;
import com.firstclub.membership.dto.CheckoutQuoteResponse;
import com.firstclub.membership.dto.OrderDTO;
import com.firstclub.membership.security.AccessGuard;
import com.firstclub.membership.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checkout")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Apply membership benefits to a cart")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final AccessGuard accessGuard;

    @PostMapping("/quote")
    @Operation(summary = "Price a cart with membership benefits applied",
            description = "Returns the subtotal, member discount, delivery waiver and total based on the user's active tier.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Quote produced"),
        @ApiResponse(responseCode = "403", description = "Cannot quote for another user")
    })
    public ResponseEntity<CheckoutQuoteResponse> quote(@Valid @RequestBody CheckoutQuoteRequest request) {
        accessGuard.requireSelfOrAdmin(request.getUserId());
        return ResponseEntity.ok(checkoutService.quote(request));
    }

    @PostMapping("/confirm")
    @Operation(summary = "Place an order, redeeming any coupon against it atomically")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order placed"),
        @ApiResponse(responseCode = "422", description = "Coupon invalid / limit reached")
    })
    public ResponseEntity<OrderDTO> confirm(@Valid @RequestBody CheckoutQuoteRequest request) {
        accessGuard.requireSelfOrAdmin(request.getUserId());
        return ResponseEntity.ok(checkoutService.confirm(request));
    }
}
