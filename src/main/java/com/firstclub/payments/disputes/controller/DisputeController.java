package com.firstclub.payments.disputes.controller;

import com.firstclub.payments.disputes.dto.DisputeCreateRequestDTO;
import com.firstclub.payments.disputes.dto.DisputeResponseDTO;
import com.firstclub.payments.disputes.service.DisputeService;
import com.firstclub.platform.idempotency.annotation.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payment-scoped dispute endpoints.
 *
 * <p>All paths are prefixed with
 * {@code /api/v2/merchants/{merchantId}/payments/{paymentId}/disputes}.
 * Tenant isolation is enforced by the service layer.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/payments/{paymentId}/disputes")
@RequiredArgsConstructor
@Tag(name = "Disputes (Payment-Scoped)",
     description = "Open and list disputes against a single payment")
public class DisputeController {

    private final DisputeService disputeService;

    /**
     * Open a dispute against a captured payment.
     * Idempotent: re-sending the same {@code Idempotency-Key} within 24 hours
     * returns the cached 201 without creating a duplicate dispute.
     */
    @PostMapping
    @Idempotent(ttlHours = 24)
    @Operation(
        summary = "Open a dispute",
        description = "Opens a dispute against a CAPTURED or PARTIALLY_REFUNDED payment. "
                + "Moves payment status to DISPUTED. Posts DR DISPUTE_RESERVE / CR PG_CLEARING. "
                + "Only one active dispute (OPEN or UNDER_REVIEW) is allowed per payment.")
    public ResponseEntity<DisputeResponseDTO> openDispute(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable Long merchantId,
            @Parameter(description = "Payment to dispute", required = true)
            @PathVariable Long paymentId,
            @Valid @RequestBody DisputeCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.openDispute(merchantId, paymentId, request));
    }

    /**
     * List all disputes for a specific payment, scoped to the merchant.
     */
    @GetMapping
    @Operation(summary = "List disputes for a payment")
    public ResponseEntity<List<DisputeResponseDTO>> listDisputesForPayment(
            @PathVariable Long merchantId,
            @PathVariable Long paymentId) {
        return ResponseEntity.ok(disputeService.listDisputesByPayment(merchantId, paymentId));
    }
}
