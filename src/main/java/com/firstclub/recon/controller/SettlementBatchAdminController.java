package com.firstclub.recon.controller;

import com.firstclub.recon.dto.SettlementBatchItemResponseDTO;
import com.firstclub.recon.dto.SettlementBatchResponseDTO;
import com.firstclub.recon.service.SettlementBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/settlement-batches")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Settlement Batches", description = "Per-merchant settlement batch operations")
public class SettlementBatchAdminController {

    private final SettlementBatchService service;

    @PostMapping("/run")
    @Operation(summary = "Create and post a settlement batch for a merchant on a given date")
    public ResponseEntity<SettlementBatchResponseDTO> runBatch(
            @RequestParam Long merchantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "STRIPE") String gatewayName) {
        return ResponseEntity.ok(service.runBatch(merchantId, date, gatewayName));
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "Get a settlement batch by ID")
    public ResponseEntity<SettlementBatchResponseDTO> getBatch(@PathVariable Long batchId) {
        return ResponseEntity.ok(service.getBatch(batchId));
    }

    @GetMapping("/{batchId}/items")
    @Operation(summary = "List all payment items in a settlement batch")
    public ResponseEntity<List<SettlementBatchItemResponseDTO>> getBatchItems(@PathVariable Long batchId) {
        return ResponseEntity.ok(service.listBatchItems(batchId));
    }

    @GetMapping
    @Operation(summary = "List settlement batches for a merchant")
    public ResponseEntity<Page<SettlementBatchResponseDTO>> listBatches(
            @RequestParam Long merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.listBatches(merchantId, pageable));
    }
}
