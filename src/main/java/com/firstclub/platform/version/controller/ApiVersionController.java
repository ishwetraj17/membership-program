package com.firstclub.platform.version.controller;

import com.firstclub.platform.version.MerchantApiVersion;
import com.firstclub.platform.version.MerchantApiVersionService;
import com.firstclub.platform.version.dto.ApiVersionPinRequestDTO;
import com.firstclub.platform.version.dto.ApiVersionPinResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for merchant API version pinning.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code PUT  /merchants/{merchantId}/api-version} — pin or update</li>
 *   <li>{@code GET  /merchants/{merchantId}/api-version} — read current pin</li>
 *   <li>{@code DELETE /merchants/{merchantId}/api-version} — remove pin</li>
 * </ul>
 */
@RestController
@RequestMapping("/merchants/{merchantId}/api-version")
@RequiredArgsConstructor
@Tag(name = "API Version Management", description = "Manage per-merchant API version pins")
public class ApiVersionController {

    private final MerchantApiVersionService merchantApiVersionService;

    /**
     * Pin (or update) the API version for a merchant.
     */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Pin API version for a merchant",
               description = "Sets the API version used for this merchant when " +
                             "no explicit X-API-Version header is present.")
    public ResponseEntity<ApiVersionPinResponseDTO> pinVersion(
            @PathVariable Long merchantId,
            @Valid @RequestBody ApiVersionPinRequestDTO request
    ) {
        MerchantApiVersion pin = merchantApiVersionService.pinVersion(
                merchantId,
                request.version(),
                request.effectiveFrom()
        );
        return ResponseEntity.ok(ApiVersionPinResponseDTO.from(pin));
    }

    /**
     * Retrieve the current version pin for a merchant.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get current API version pin for a merchant")
    public ResponseEntity<ApiVersionPinResponseDTO> getPin(@PathVariable Long merchantId) {
        return merchantApiVersionService.findPin(merchantId)
                .map(ApiVersionPinResponseDTO::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Remove the version pin; the merchant reverts to {@code ApiVersion.DEFAULT}.
     */
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove API version pin for a merchant")
    public ResponseEntity<Void> removePin(@PathVariable Long merchantId) {
        merchantApiVersionService.removePin(merchantId);
        return ResponseEntity.noContent().build();
    }
}
