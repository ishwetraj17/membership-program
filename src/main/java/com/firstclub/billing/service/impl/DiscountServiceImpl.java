package com.firstclub.billing.service.impl;

import com.firstclub.billing.dto.*;
import com.firstclub.billing.entity.*;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.*;
import com.firstclub.billing.service.DiscountService;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements DiscountService {

    private final DiscountRepository discountRepository;
    private final DiscountRedemptionRepository redemptionRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceTotalService invoiceTotalService;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DiscountResponseDTO createDiscount(Long merchantId, DiscountCreateRequestDTO req) {
        validateCreateRequest(merchantId, req);

        Discount discount = Discount.builder()
                .merchantId(merchantId)
                .code(req.getCode().trim().toUpperCase())
                .discountType(req.getDiscountType())
                .value(req.getValue())
                .currency(req.getCurrency())
                .maxRedemptions(req.getMaxRedemptions())
                .perCustomerLimit(req.getPerCustomerLimit())
                .validFrom(req.getValidFrom())
                .validTo(req.getValidTo())
                .status(DiscountStatus.ACTIVE)
                .build();

        return toDto(discountRepository.save(discount), 0L);
    }

    // ── List / Fetch ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DiscountResponseDTO> listDiscounts(Long merchantId) {
        return discountRepository.findAllByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(d -> toDto(d, redemptionRepository.countByDiscountId(d.getId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DiscountResponseDTO getDiscount(Long merchantId, Long discountId) {
        Discount d = discountRepository.findByIdAndMerchantId(discountId, merchantId)
                .orElseThrow(() -> new MembershipException("Discount not found: " + discountId, "DISCOUNT_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));
        return toDto(d, redemptionRepository.countByDiscountId(d.getId()));
    }

    // ── Apply ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public InvoiceSummaryDTO applyDiscountToInvoice(Long merchantId, Long invoiceId,
                                                    ApplyDiscountRequestDTO req) {
        // 1. Resolve discount by code (case-insensitive)
        Discount discount = discountRepository
                .findByMerchantIdAndCodeIgnoreCase(merchantId, req.getCode())
                .orElseThrow(() -> new MembershipException(
                        "Discount code not found: " + req.getCode(), "DISCOUNT_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));

        // 2. Status check
        if (discount.getStatus() != DiscountStatus.ACTIVE) {
            throw new MembershipException("Discount is not active: " + req.getCode(), "DISCOUNT_NOT_ACTIVE");
        }

        // 3. Validity window
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(discount.getValidFrom()) || now.isAfter(discount.getValidTo())) {
            throw new MembershipException("Discount is outside its validity window: " + req.getCode(), "DISCOUNT_EXPIRED");
        }

        // 4. Max redemptions (global)
        if (discount.getMaxRedemptions() != null) {
            long used = redemptionRepository.countByDiscountId(discount.getId());
            if (used >= discount.getMaxRedemptions()) {
                throw new MembershipException("Discount has reached its maximum redemptions: " + req.getCode(), "DISCOUNT_EXHAUSTED");
            }
        }

        // 5. Per-customer limit
        if (discount.getPerCustomerLimit() != null) {
            long usedByCustomer = redemptionRepository.countByDiscountIdAndCustomerId(
                    discount.getId(), req.getCustomerId());
            if (usedByCustomer >= discount.getPerCustomerLimit()) {
                throw new MembershipException("Customer has exhausted per-customer limit for: " + req.getCode(), "DISCOUNT_LIMIT_EXCEEDED");
            }
        }

        // 6. Resolve invoice (must belong to this merchant)
        Invoice invoice = invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new MembershipException("Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND", org.springframework.http.HttpStatus.NOT_FOUND));

        // 7. Invoice must be OPEN
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new MembershipException(
                    "Cannot apply discount to invoice in status: " + invoice.getStatus(), "INVALID_INVOICE_STATUS");
        }

        // 8. One discount per invoice
        if (redemptionRepository.existsByDiscountIdAndInvoiceId(discount.getId(), invoiceId)) {
            throw new MembershipException("Discount already applied to invoice: " + invoiceId, "DISCOUNT_ALREADY_APPLIED", org.springframework.http.HttpStatus.CONFLICT);
        }

        // 9. Compute discount amount
        BigDecimal subtotal = invoice.getSubtotal();
        BigDecimal discountAmount;
        if (discount.getDiscountType() == DiscountType.FIXED) {
            // Cap at subtotal so we never produce a negative balance
            discountAmount = discount.getValue().min(subtotal);
        } else {
            // PERCENTAGE
            discountAmount = subtotal
                    .multiply(discount.getValue())
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        }

        // 10. Add DISCOUNT invoice line (stored as negative amount)
        InvoiceLine discountLine = InvoiceLine.builder()
                .invoiceId(invoiceId)
                .lineType(InvoiceLineType.DISCOUNT)
                .description("Discount: " + discount.getCode())
                .amount(discountAmount.negate())
                .build();
        invoiceLineRepository.save(discountLine);

        // 11. Record redemption
        DiscountRedemption redemption = DiscountRedemption.builder()
                .discountId(discount.getId())
                .customerId(req.getCustomerId())
                .invoiceId(invoiceId)
                .build();
        redemptionRepository.save(redemption);

        // 12. Recompute totals
        Invoice updated = invoiceTotalService.recomputeTotals(invoice);

        return toSummaryDto(updated);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateCreateRequest(Long merchantId, DiscountCreateRequestDTO req) {
        if (req.getValidFrom() != null && req.getValidTo() != null
                && !req.getValidFrom().isBefore(req.getValidTo())) {
            throw new MembershipException("validFrom must be before validTo", "INVALID_DISCOUNT_DATES");
        }
        if (req.getDiscountType() == DiscountType.PERCENTAGE) {
            if (req.getValue().compareTo(BigDecimal.ZERO) <= 0
                    || req.getValue().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new MembershipException("Percentage discount value must be between 0 (exclusive) and 100", "INVALID_DISCOUNT_VALUE");
            }
        }
        if (discountRepository.existsByMerchantIdAndCodeIgnoreCase(merchantId, req.getCode())) {
            throw new MembershipException("Discount code already exists for this merchant: " + req.getCode(), "DUPLICATE_DISCOUNT_CODE", org.springframework.http.HttpStatus.CONFLICT);
        }
    }

    private DiscountResponseDTO toDto(Discount d, long redemptionCount) {
        return DiscountResponseDTO.builder()
                .id(d.getId())
                .merchantId(d.getMerchantId())
                .code(d.getCode())
                .discountType(d.getDiscountType())
                .value(d.getValue())
                .currency(d.getCurrency())
                .maxRedemptions(d.getMaxRedemptions())
                .perCustomerLimit(d.getPerCustomerLimit())
                .validFrom(d.getValidFrom())
                .validTo(d.getValidTo())
                .status(d.getStatus())
                .createdAt(d.getCreatedAt())
                .redemptionCount(redemptionCount)
                .build();
    }

    private InvoiceSummaryDTO toSummaryDto(Invoice inv) {
        List<InvoiceLineDTO> lineDtos = invoiceLineRepository.findByInvoiceId(inv.getId()).stream()
                .map(l -> InvoiceLineDTO.builder()
                        .id(l.getId())
                        .invoiceId(l.getInvoiceId())
                        .lineType(l.getLineType())
                        .description(l.getDescription())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return InvoiceSummaryDTO.builder()
                .id(inv.getId())
                .invoiceNumber(inv.getInvoiceNumber())
                .merchantId(inv.getMerchantId())
                .userId(inv.getUserId())
                .subscriptionId(inv.getSubscriptionId())
                .status(inv.getStatus())
                .currency(inv.getCurrency())
                .subtotal(inv.getSubtotal())
                .discountTotal(inv.getDiscountTotal())
                .creditTotal(inv.getCreditTotal())
                .taxTotal(inv.getTaxTotal())
                .grandTotal(inv.getGrandTotal())
                .dueDate(inv.getDueDate())
                .periodStart(inv.getPeriodStart())
                .periodEnd(inv.getPeriodEnd())
                .createdAt(inv.getCreatedAt())
                .lines(lineDtos)
                .build();
    }
}
