package com.firstclub.merchant.auth.controller;

import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateRequestDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyCreateResponseDTO;
import com.firstclub.merchant.auth.dto.MerchantApiKeyResponseDTO;
import com.firstclub.merchant.auth.service.MerchantApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/api-keys")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Merchant API Keys", description = "Manage API keys for merchant access")
public class MerchantApiKeyController {

    private final MerchantApiKeyService merchantApiKeyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new API key — raw key is shown only once in the response")
    public ResponseEntity<MerchantApiKeyCreateResponseDTO> createApiKey(
            @PathVariable Long merchantId,
            @Valid @RequestBody MerchantApiKeyCreateRequestDTO request) {
        MerchantApiKeyCreateResponseDTO response = merchantApiKeyService.createApiKey(merchantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List all API keys for a merchant (no raw key material returned)")
    public ResponseEntity<List<MerchantApiKeyResponseDTO>> listApiKeys(
            @PathVariable Long merchantId) {
        return ResponseEntity.ok(merchantApiKeyService.listApiKeys(merchantId));
    }

    @DeleteMapping("/{apiKeyId}")
    @Operation(summary = "Revoke an API key — revoked keys can no longer authenticate")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable Long merchantId,
            @PathVariable Long apiKeyId) {
        merchantApiKeyService.revokeApiKey(merchantId, apiKeyId);
        return ResponseEntity.noContent().build();
    }
}
