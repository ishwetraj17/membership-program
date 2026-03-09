package com.firstclub.billing.tax.controller;

import com.firstclub.billing.tax.dto.CustomerTaxProfileCreateOrUpdateRequestDTO;
import com.firstclub.billing.tax.dto.CustomerTaxProfileResponseDTO;
import com.firstclub.billing.tax.service.TaxProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/customers/{customerId}/tax-profile")
@RequiredArgsConstructor
@Tag(name = "Customer Tax Profile", description = "Manage GST registration details for customers")
public class CustomerTaxProfileController {

    private final TaxProfileService taxProfileService;

    @PostMapping
    @Operation(summary = "Create or update the customer's GST tax profile")
    public ResponseEntity<CustomerTaxProfileResponseDTO> createOrUpdate(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerTaxProfileCreateOrUpdateRequestDTO request) {
        return ResponseEntity.ok(taxProfileService.createOrUpdateCustomerTaxProfile(customerId, request));
    }

    @PutMapping
    @Operation(summary = "Update the customer's GST tax profile")
    public ResponseEntity<CustomerTaxProfileResponseDTO> update(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @Valid @RequestBody CustomerTaxProfileCreateOrUpdateRequestDTO request) {
        return ResponseEntity.ok(taxProfileService.createOrUpdateCustomerTaxProfile(customerId, request));
    }

    @GetMapping
    @Operation(summary = "Get the customer's GST tax profile")
    public ResponseEntity<CustomerTaxProfileResponseDTO> get(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {
        return ResponseEntity.ok(taxProfileService.getCustomerTaxProfile(customerId));
    }
}
