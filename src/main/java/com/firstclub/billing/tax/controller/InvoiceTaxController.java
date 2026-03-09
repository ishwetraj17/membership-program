package com.firstclub.billing.tax.controller;

import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.tax.dto.InvoiceTaxBreakdownDTO;
import com.firstclub.billing.tax.dto.RecalculateTaxRequestDTO;
import com.firstclub.billing.tax.service.TaxCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/invoices/{invoiceId}")
@RequiredArgsConstructor
@Tag(name = "Invoice Tax", description = "Compute and apply India GST to invoices")
public class InvoiceTaxController {

    private final TaxCalculationService taxCalculationService;

    @GetMapping("/tax-breakdown")
    @Operation(summary = "Get GST breakdown for an invoice (read-only, does not modify the invoice)")
    public ResponseEntity<InvoiceTaxBreakdownDTO> getTaxBreakdown(
            @PathVariable Long merchantId,
            @PathVariable Long invoiceId,
            @RequestParam Long customerId) {
        return ResponseEntity.ok(taxCalculationService.getTaxBreakdown(merchantId, invoiceId, customerId));
    }

    @PostMapping("/recalculate-tax")
    @Operation(summary = "Delete existing GST lines, recompute GST and update invoice totals")
    public ResponseEntity<InvoiceSummaryDTO> recalculateTax(
            @PathVariable Long merchantId,
            @PathVariable Long invoiceId,
            @Valid @RequestBody RecalculateTaxRequestDTO request) {
        return ResponseEntity.ok(
                taxCalculationService.recalculateTax(merchantId, invoiceId, request.getCustomerId()));
    }
}
