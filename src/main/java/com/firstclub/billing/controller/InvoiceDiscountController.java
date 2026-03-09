package com.firstclub.billing.controller;

import com.firstclub.billing.dto.ApplyDiscountRequestDTO;
import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.DiscountService;
import com.firstclub.membership.exception.MembershipException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v2/merchants/{merchantId}/invoices/{invoiceId}")
@RequiredArgsConstructor
@Tag(name = "Invoice Discounts", description = "Apply discounts and fetch invoice summaries")
public class InvoiceDiscountController {

    private final DiscountService discountService;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;

    @PostMapping("/apply-discount")
    @Operation(summary = "Apply a discount code to an open invoice")
    public ResponseEntity<InvoiceSummaryDTO> applyDiscount(
            @PathVariable Long merchantId,
            @PathVariable Long invoiceId,
            @Valid @RequestBody ApplyDiscountRequestDTO request) {
        return ResponseEntity.ok(discountService.applyDiscountToInvoice(merchantId, invoiceId, request));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get full invoice summary including total breakdown")
    public ResponseEntity<InvoiceSummaryDTO> getSummary(
            @PathVariable Long merchantId,
            @PathVariable Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new MembershipException("Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));

        var lines = invoiceLineRepository.findByInvoiceId(invoiceId).stream()
                .map(l -> InvoiceLineDTO.builder()
                        .id(l.getId())
                        .invoiceId(l.getInvoiceId())
                        .lineType(l.getLineType())
                        .description(l.getDescription())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(InvoiceSummaryDTO.builder()
                .id(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .merchantId(invoice.getMerchantId())
                .userId(invoice.getUserId())
                .subscriptionId(invoice.getSubscriptionId())
                .status(invoice.getStatus())
                .currency(invoice.getCurrency())
                .subtotal(invoice.getSubtotal())
                .discountTotal(invoice.getDiscountTotal())
                .creditTotal(invoice.getCreditTotal())
                .taxTotal(invoice.getTaxTotal())
                .grandTotal(invoice.getGrandTotal())
                .dueDate(invoice.getDueDate())
                .periodStart(invoice.getPeriodStart())
                .periodEnd(invoice.getPeriodEnd())
                .createdAt(invoice.getCreatedAt())
                .lines(lines)
                .build());
    }
}
