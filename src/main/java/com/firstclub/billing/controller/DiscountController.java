package com.firstclub.billing.controller;

import com.firstclub.billing.dto.DiscountCreateRequestDTO;
import com.firstclub.billing.dto.DiscountResponseDTO;
import com.firstclub.billing.service.DiscountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/discounts")
@RequiredArgsConstructor
@Tag(name = "Discounts", description = "Merchant-scoped discount management")
public class DiscountController {

    private final DiscountService discountService;

    @PostMapping
    @Operation(summary = "Create a discount for a merchant")
    public ResponseEntity<DiscountResponseDTO> createDiscount(
            @PathVariable Long merchantId,
            @Valid @RequestBody DiscountCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(discountService.createDiscount(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "List all discounts for a merchant")
    public ResponseEntity<List<DiscountResponseDTO>> listDiscounts(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(discountService.listDiscounts(merchantId));
    }

    @GetMapping("/{discountId}")
    @Operation(summary = "Get a single discount by ID")
    public ResponseEntity<DiscountResponseDTO> getDiscount(
            @PathVariable Long merchantId,
            @PathVariable Long discountId) {
        return ResponseEntity.ok(discountService.getDiscount(merchantId, discountId));
    }
}
