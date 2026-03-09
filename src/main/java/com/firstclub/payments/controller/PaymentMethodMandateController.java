package com.firstclub.payments.controller;

import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.service.PaymentMethodMandateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for payment method mandates.
 *
 * <p>Only CARD and MANDATE payment method types support mandate creation.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods/{paymentMethodId}/mandates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Payment Method Mandates", description = "Manage recurring debit mandates for payment methods")
public class PaymentMethodMandateController {

    private final PaymentMethodMandateService paymentMethodMandateService;

    @PostMapping
    @Operation(summary = "Create a mandate for a payment method")
    public ResponseEntity<PaymentMethodMandateResponseDTO> create(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @PathVariable Long paymentMethodId,
            @Valid @RequestBody PaymentMethodMandateCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentMethodMandateService.createMandate(merchantId, customerId,
                        paymentMethodId, request));
    }

    @GetMapping
    @Operation(summary = "List all mandates for a payment method")
    public ResponseEntity<List<PaymentMethodMandateResponseDTO>> list(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @PathVariable Long paymentMethodId) {
        return ResponseEntity.ok(
                paymentMethodMandateService.listMandates(merchantId, customerId, paymentMethodId));
    }

    @PostMapping("/{mandateId}/revoke")
    @Operation(summary = "Revoke a mandate")
    public ResponseEntity<PaymentMethodMandateResponseDTO> revoke(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @PathVariable Long paymentMethodId,
            @PathVariable Long mandateId) {
        return ResponseEntity.ok(
                paymentMethodMandateService.revokeMandate(merchantId, customerId, paymentMethodId,
                        mandateId));
    }
}
