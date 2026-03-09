package com.firstclub.payments.controller;

import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.service.PaymentMethodService;
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
 * REST controller for managing tokenized payment methods per customer.
 *
 * <p>All endpoints are tenant-scoped (merchantId) and customer-scoped (customerId).
 * Raw payment credentials are never accepted or stored — only opaque provider tokens.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/customers/{customerId}/payment-methods")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Customer Payment Methods", description = "Manage tokenized payment instruments per customer")
public class CustomerPaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @PostMapping
    @Operation(summary = "Register a new payment method for a customer")
    public ResponseEntity<PaymentMethodResponseDTO> create(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @Valid @RequestBody PaymentMethodCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentMethodService.createPaymentMethod(merchantId, customerId, request));
    }

    @GetMapping
    @Operation(summary = "List all payment methods for a customer")
    public ResponseEntity<List<PaymentMethodResponseDTO>> list(
            @PathVariable Long merchantId,
            @PathVariable Long customerId) {
        return ResponseEntity.ok(
                paymentMethodService.listCustomerPaymentMethods(merchantId, customerId));
    }

    @PostMapping("/{paymentMethodId}/default")
    @Operation(summary = "Set a payment method as the customer default")
    public ResponseEntity<PaymentMethodResponseDTO> setDefault(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @PathVariable Long paymentMethodId) {
        return ResponseEntity.ok(
                paymentMethodService.setDefaultPaymentMethod(merchantId, customerId,
                        paymentMethodId));
    }

    @DeleteMapping("/{paymentMethodId}")
    @Operation(summary = "Revoke a payment method")
    public ResponseEntity<PaymentMethodResponseDTO> revoke(
            @PathVariable Long merchantId,
            @PathVariable Long customerId,
            @PathVariable Long paymentMethodId) {
        return ResponseEntity.ok(
                paymentMethodService.revokePaymentMethod(merchantId, customerId, paymentMethodId));
    }
}
