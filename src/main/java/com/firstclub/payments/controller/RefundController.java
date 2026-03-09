package com.firstclub.payments.controller;

import com.firstclub.payments.dto.RefundRequestDTO;
import com.firstclub.payments.dto.RefundResponseDTO;
import com.firstclub.payments.service.RefundService;
import com.firstclub.platform.idempotency.annotation.Idempotent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds V2", description = "Idempotent refund issuance")
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    @Idempotent(ttlHours = 24)
    @Operation(summary = "Issue a refund",
               description = "Creates a refund for a CAPTURED payment and posts a REFUND_ISSUED double-entry. " +
                             "Idempotent when the same Idempotency-Key header is supplied.")
    public ResponseEntity<RefundResponseDTO> createRefund(@Valid @RequestBody RefundRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(refundService.createRefund(request));
    }
}
