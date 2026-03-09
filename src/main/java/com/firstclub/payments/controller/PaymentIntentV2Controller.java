package com.firstclub.payments.controller;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.dto.PaymentIntentConfirmRequestDTO;
import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;
import com.firstclub.payments.service.PaymentIntentV2Service;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for payment intents (V2).
 *
 * <p>All mutations are idempotency-key aware:
 * <ul>
 *   <li>POST /  — creates an intent; duplicate key returns the original.</li>
 *   <li>POST /{id}/confirm — confirms an intent; resuming on an already-succeeded
 *       intent returns its current snapshot.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/payment-intents")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Payment Intents V2", description = "Create, confirm, cancel and inspect V2 payment intents")
public class PaymentIntentV2Controller {

    private final PaymentIntentV2Service paymentIntentV2Service;

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create a payment intent")
    public ResponseEntity<PaymentIntentV2ResponseDTO> create(
            @PathVariable Long merchantId,
            @Parameter(description = "Caller-supplied idempotency key (optional but recommended)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentIntentCreateRequestDTO request) {

        PaymentIntentV2ResponseDTO dto =
                paymentIntentV2Service.createPaymentIntent(merchantId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @GetMapping("/{paymentIntentId}")
    @Operation(summary = "Fetch a payment intent by ID")
    public ResponseEntity<PaymentIntentV2ResponseDTO> get(
            @PathVariable Long merchantId,
            @PathVariable Long paymentIntentId) {

        return ResponseEntity.ok(
                paymentIntentV2Service.getPaymentIntent(merchantId, paymentIntentId));
    }

    // ── Confirm ───────────────────────────────────────────────────────────────

    @PostMapping("/{paymentIntentId}/confirm")
    @RateLimit(RateLimitPolicy.PAYMENT_CONFIRM)
    @Operation(summary = "Confirm a payment intent and spawn a gateway attempt")
    public ResponseEntity<PaymentIntentV2ResponseDTO> confirm(
            @PathVariable Long merchantId,
            @PathVariable Long paymentIntentId,
            @Parameter(description = "Idempotency key scoped to this confirm action")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentIntentConfirmRequestDTO request) {

        PaymentIntentV2ResponseDTO dto =
                paymentIntentV2Service.confirmPaymentIntent(merchantId, paymentIntentId, request);
        return ResponseEntity.ok(dto);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @PostMapping("/{paymentIntentId}/cancel")
    @Operation(summary = "Cancel a payment intent")
    public ResponseEntity<PaymentIntentV2ResponseDTO> cancel(
            @PathVariable Long merchantId,
            @PathVariable Long paymentIntentId) {

        return ResponseEntity.ok(
                paymentIntentV2Service.cancelPaymentIntent(merchantId, paymentIntentId));
    }

    // ── Reconcile gateway status ───────────────────────────────────────────────

    @PostMapping("/{paymentIntentId}/reconcile-gateway-status")
    @Operation(summary = "Trigger gateway status reconciliation for UNKNOWN attempts")
    public ResponseEntity<PaymentIntentV2ResponseDTO> reconcileGatewayStatus(
            @PathVariable Long merchantId,
            @PathVariable Long paymentIntentId) {

        return ResponseEntity.ok(
                paymentIntentV2Service.reconcileGatewayStatus(merchantId, paymentIntentId));
    }

    // ── Attempts ──────────────────────────────────────────────────────────────

    @GetMapping("/{paymentIntentId}/attempts")
    @Operation(summary = "List all gateway attempts for a payment intent")
    public ResponseEntity<List<PaymentAttemptResponseDTO>> listAttempts(
            @PathVariable Long merchantId,
            @PathVariable Long paymentIntentId) {

        return ResponseEntity.ok(
                paymentIntentV2Service.listAttempts(merchantId, paymentIntentId));
    }
}
