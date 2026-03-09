package com.firstclub.billing.tax.controller;

import com.firstclub.billing.tax.dto.TaxProfileCreateOrUpdateRequestDTO;
import com.firstclub.billing.tax.dto.TaxProfileResponseDTO;
import com.firstclub.billing.tax.service.TaxProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/tax-profile")
@RequiredArgsConstructor
@Tag(name = "Merchant Tax Profile", description = "Manage GST registration details for merchants")
public class MerchantTaxProfileController {

    private final TaxProfileService taxProfileService;

    @PostMapping
    @Operation(summary = "Create or update the merchant's GST tax profile")
    public ResponseEntity<TaxProfileResponseDTO> createOrUpdate(
            @PathVariable Long merchantId,
            @Valid @RequestBody TaxProfileCreateOrUpdateRequestDTO request) {
        return ResponseEntity.ok(taxProfileService.createOrUpdateMerchantTaxProfile(merchantId, request));
    }

    @PutMapping
    @Operation(summary = "Update the merchant's GST tax profile")
    public ResponseEntity<TaxProfileResponseDTO> update(
            @PathVariable Long merchantId,
            @Valid @RequestBody TaxProfileCreateOrUpdateRequestDTO request) {
        return ResponseEntity.ok(taxProfileService.createOrUpdateMerchantTaxProfile(merchantId, request));
    }

    @GetMapping
    @Operation(summary = "Get the merchant's GST tax profile")
    public ResponseEntity<TaxProfileResponseDTO> get(@PathVariable Long merchantId) {
        return ResponseEntity.ok(taxProfileService.getMerchantTaxProfile(merchantId));
    }
}
