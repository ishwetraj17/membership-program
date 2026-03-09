package com.firstclub.payments.refund.controller;

import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.service.RefundServiceV2;
import com.firstclub.platform.idempotency.annotation.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Merchant-scoped refund management (V2).
 *
 * <p>All paths are prefixed with {@code /api/v2/merchants/{merchantId}/payments/{paymentId}/refunds}.
 * Tenant isolation is enforced by {@link RefundServiceV2}: the service verifies that the referenced
 * payment belongs to the given {@code merchantId} before any mutation is allowed.
 */
@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/payments/{paymentId}/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds V2 (Merchant-Scoped)", description = "Partial and full refund issuance with cumulative over-refund protection")
public class RefundControllerV2 {

    private final RefundServiceV2 refundService;

    /**
     * Issue a new (partial or full) refund for a captured payment.
     *
     * <p>Idempotent: supplying the same {@code Idempotency-Key} header within 24 hours will
     * return the cached response without re-processing the refund.
     */
    @PostMapping
    @Idempotent(ttlHours = 24)
    @Operation(
        summary = "Create refund",
        description = "Issues a partial or full refund against a CAPTURED/PARTIALLY_REFUNDED payment. "
                + "Protected against over-refund via a pessimistic write lock. "
                + "Posts DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING to the ledger atomically. "
                + "Idempotent when the same Idempotency-Key header is re-sent within 24 hours.")
    public ResponseEntity<RefundV2ResponseDTO> createRefund(
            @Parameter(description = "Merchant identifier", required = true)
            @PathVariable Long merchantId,
            @Parameter(description = "Payment to refund", required = true)
            @PathVariable Long paymentId,
            @Valid @RequestBody RefundCreateRequestDTO request) {
        RefundV2ResponseDTO response = refundService.createRefund(merchantId, paymentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List all refunds for the given payment (newest first from DB insertion order).
     */
    @GetMapping
    @Operation(summary = "List refunds for a payment")
    public ResponseEntity<List<RefundV2ResponseDTO>> listRefunds(
            @PathVariable Long merchantId,
            @PathVariable Long paymentId) {
        return ResponseEntity.ok(refundService.listRefundsByPayment(merchantId, paymentId));
    }

    /**
     * Retrieve a specific refund by its ID.
     */
    @GetMapping("/{refundId}")
    @Operation(summary = "Get refund by ID")
    public ResponseEntity<RefundV2ResponseDTO> getRefund(
            @PathVariable Long merchantId,
            @PathVariable Long paymentId,
            @Parameter(description = "Refund identifier", required = true)
            @PathVariable Long refundId) {
        return ResponseEntity.ok(refundService.getRefund(merchantId, refundId));
    }

    /**
     * Compute the currently refundable amount for a payment without issuing a refund.
     */
    @GetMapping("/refundable-amount")
    @Operation(
        summary = "Get refundable amount",
        description = "Returns capturedAmount - refundedAmount - disputedAmount for informational display. "
                + "Not authoritative (the real check uses a pessimistic lock on create).")
    public ResponseEntity<BigDecimal> getRefundableAmount(
            @PathVariable Long merchantId,
            @PathVariable Long paymentId) {
        return ResponseEntity.ok(refundService.computeRefundableAmount(paymentId));
    }
}
