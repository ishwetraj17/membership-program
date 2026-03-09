package com.firstclub.merchant.auth.controller;

import com.firstclub.merchant.auth.dto.MerchantModeResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantModeUpdateRequestDTO;
import com.firstclub.merchant.auth.service.MerchantModeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/mode")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Merchant Mode", description = "Manage sandbox/live mode configuration per merchant")
public class MerchantModeController {

    private final MerchantModeService merchantModeService;

    @GetMapping
    @Operation(summary = "Get the current sandbox/live mode configuration for a merchant")
    public ResponseEntity<MerchantModeResponseDTO> getMode(@PathVariable Long merchantId) {
        return ResponseEntity.ok(merchantModeService.getMode(merchantId));
    }

    @PutMapping
    @Operation(summary = "Update sandbox/live mode settings — enabling live requires ACTIVE merchant")
    public ResponseEntity<MerchantModeResponseDTO> updateMode(
            @PathVariable Long merchantId,
            @Valid @RequestBody MerchantModeUpdateRequestDTO request) {
        return ResponseEntity.ok(merchantModeService.updateMode(merchantId, request));
    }
}
