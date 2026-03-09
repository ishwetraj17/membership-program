package com.firstclub.payments.refund.controller;

import com.firstclub.membership.exception.MembershipException;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.refund.dto.RefundCapacityDTO;
import com.firstclub.payments.repository.PaymentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 15 admin API — refund capacity inspection.
 *
 * <p>Allows operations teams to quickly check the remaining refundable headroom
 * on any payment without performing the calculation client-side.
 */
@RestController
@RequestMapping("/api/v2/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment Admin", description = "Admin APIs for payment inspection (Phase 15)")
public class PaymentAdminController {

    private final PaymentRepository paymentRepository;

    @GetMapping("/{paymentId}/refund-capacity")
    @Operation(
        summary = "Get remaining refund capacity for a payment",
        description = "Returns capturedAmount, refundedAmount, disputedAmount and the computed " +
                      "refundableAmount (captured - refunded - disputed). Useful for ops triage " +
                      "before initiating a manual refund.")
    @ApiResponse(responseCode = "200", description = "Refund capacity snapshot")
    @ApiResponse(responseCode = "404", description = "Payment not found")
    public ResponseEntity<RefundCapacityDTO> getRefundCapacity(
            @Parameter(description = "Internal payment ID") @PathVariable Long paymentId) {

        log.debug("GET /api/v2/admin/payments/{}/refund-capacity", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));

        return ResponseEntity.ok(RefundCapacityDTO.builder()
                .paymentId(payment.getId())
                .merchantId(payment.getMerchantId())
                .capturedAmount(payment.getCapturedAmount())
                .refundedAmount(payment.getRefundedAmount())
                .disputedAmount(payment.getDisputedAmount())
                .refundableAmount(
                        payment.getCapturedAmount()
                               .subtract(payment.getRefundedAmount())
                               .subtract(payment.getDisputedAmount()))
                .paymentStatus(payment.getStatus())
                .build());
    }
}
