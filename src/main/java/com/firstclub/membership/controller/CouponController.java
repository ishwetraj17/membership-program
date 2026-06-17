package com.firstclub.membership.controller;

import com.firstclub.membership.dto.CouponDTO;
import com.firstclub.membership.dto.CreateCouponRequest;
import com.firstclub.membership.dto.RedeemCouponRequest;
import com.firstclub.membership.dto.RedeemCouponResponse;
import com.firstclub.membership.security.AccessGuard;
import com.firstclub.membership.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
@Tag(name = "Coupons", description = "Create and redeem discount coupons")
public class CouponController {

    private final CouponService couponService;
    private final AccessGuard accessGuard;

    @PostMapping
    @Operation(summary = "Create a coupon (admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Coupon created"),
        @ApiResponse(responseCode = "409", description = "Coupon code already exists")
    })
    public ResponseEntity<CouponDTO> create(@Valid @RequestBody CreateCouponRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(couponService.createCoupon(request));
    }

    @GetMapping
    @Operation(summary = "List all coupons (admin)")
    public ResponseEntity<List<CouponDTO>> list() {
        return ResponseEntity.ok(couponService.listCoupons());
    }

    @PutMapping("/{code}/deactivate")
    @Operation(summary = "Deactivate a coupon (admin)")
    public ResponseEntity<CouponDTO> deactivate(@PathVariable String code) {
        return ResponseEntity.ok(couponService.deactivateCoupon(code));
    }

    @PostMapping("/redeem")
    @Operation(summary = "Redeem a coupon against an order amount",
            description = "Validates the coupon and records the redemption, enforcing total and per-user limits.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Redeemed"),
        @ApiResponse(responseCode = "404", description = "Unknown coupon"),
        @ApiResponse(responseCode = "422", description = "Coupon invalid / limit reached")
    })
    public ResponseEntity<RedeemCouponResponse> redeem(@Valid @RequestBody RedeemCouponRequest request) {
        accessGuard.requireSelfOrAdmin(request.getUserId());
        return ResponseEntity.ok(couponService.redeem(request.getCode(), request.getUserId(), request.getOrderAmount()));
    }
}
