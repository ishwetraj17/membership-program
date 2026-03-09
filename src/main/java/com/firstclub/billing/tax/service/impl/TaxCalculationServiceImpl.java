package com.firstclub.billing.tax.service.impl;

import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.dto.InvoiceSummaryDTO;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.entity.InvoiceLine;
import com.firstclub.billing.entity.InvoiceLineType;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.billing.tax.dto.InvoiceTaxBreakdownDTO;
import com.firstclub.billing.tax.entity.CustomerTaxProfile;
import com.firstclub.billing.tax.entity.TaxProfile;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.billing.tax.service.TaxCalculationService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaxCalculationServiceImpl implements TaxCalculationService {

    private static final Set<InvoiceLineType> GST_LINE_TYPES =
            EnumSet.of(InvoiceLineType.CGST, InvoiceLineType.SGST, InvoiceLineType.IGST);

    private static final BigDecimal CGST_RATE = new BigDecimal("0.09");
    private static final BigDecimal SGST_RATE = new BigDecimal("0.09");
    private static final BigDecimal IGST_RATE = new BigDecimal("0.18");

    private static final Set<InvoiceStatus> TERMINAL_STATUSES =
            EnumSet.of(InvoiceStatus.PAID, InvoiceStatus.VOID, InvoiceStatus.UNCOLLECTIBLE);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final TaxProfileRepository taxProfileRepository;
    private final CustomerTaxProfileRepository customerTaxProfileRepository;
    private final InvoiceTotalService invoiceTotalService;

    @Override
    @Transactional(readOnly = true)
    public InvoiceTaxBreakdownDTO getTaxBreakdown(Long merchantId, Long invoiceId, Long customerId) {
        Invoice invoice = fetchInvoice(invoiceId, merchantId);
        TaxProfile merchantProfile = fetchMerchantTaxProfile(merchantId);
        CustomerTaxProfile customerProfile = fetchCustomerTaxProfile(customerId);

        List<InvoiceLine> allLines = invoiceLineRepository.findByInvoiceId(invoiceId);
        BigDecimal subtotal = sum(allLines, InvoiceLineType.PLAN_CHARGE, InvoiceLineType.PRORATION);
        BigDecimal discountTotal = sum(allLines, InvoiceLineType.DISCOUNT).abs();
        BigDecimal creditTotal = sum(allLines, InvoiceLineType.CREDIT_APPLIED).abs();
        BigDecimal taxableBase = subtotal.subtract(discountTotal).max(BigDecimal.ZERO);

        boolean intraState = merchantProfile.getLegalStateCode()
                .equalsIgnoreCase(customerProfile.getStateCode());
        boolean taxExempt = customerProfile.isTaxExempt();

        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        List<InvoiceTaxBreakdownDTO.TaxLineDTO> taxLineDTOs = new ArrayList<>();

        if (!taxExempt && taxableBase.compareTo(BigDecimal.ZERO) > 0) {
            if (intraState) {
                cgst = taxableBase.multiply(CGST_RATE).setScale(2, RoundingMode.HALF_UP);
                sgst = taxableBase.multiply(SGST_RATE).setScale(2, RoundingMode.HALF_UP);
                taxLineDTOs.add(buildTaxLineDTO(null, InvoiceLineType.CGST,
                        "CGST @ 9% on ₹" + taxableBase.toPlainString(), cgst));
                taxLineDTOs.add(buildTaxLineDTO(null, InvoiceLineType.SGST,
                        "SGST @ 9% on ₹" + taxableBase.toPlainString(), sgst));
            } else {
                igst = taxableBase.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
                taxLineDTOs.add(buildTaxLineDTO(null, InvoiceLineType.IGST,
                        "IGST @ 18% on ₹" + taxableBase.toPlainString(), igst));
            }
        }

        BigDecimal taxTotal = cgst.add(sgst).add(igst);
        BigDecimal grandTotal = subtotal.subtract(discountTotal).subtract(creditTotal)
                .add(taxTotal).max(BigDecimal.ZERO);

        return InvoiceTaxBreakdownDTO.builder()
                .invoiceId(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .merchantId(invoice.getMerchantId())
                .status(invoice.getStatus())
                .currency(invoice.getCurrency())
                .subtotal(subtotal)
                .discountTotal(discountTotal)
                .taxableBase(taxableBase)
                .cgst(cgst)
                .sgst(sgst)
                .igst(igst)
                .taxTotal(taxTotal)
                .creditTotal(creditTotal)
                .grandTotal(grandTotal)
                .intraState(intraState)
                .taxExempt(taxExempt)
                .calculatedAt(LocalDateTime.now())
                .taxLines(taxLineDTOs)
                .build();
    }

    @Override
    @Transactional
    public InvoiceSummaryDTO recalculateTax(Long merchantId, Long invoiceId, Long customerId) {
        Invoice invoice = fetchInvoice(invoiceId, merchantId);

        if (TERMINAL_STATUSES.contains(invoice.getStatus())) {
            throw new MembershipException(
                    "Cannot recalculate tax on a " + invoice.getStatus() + " invoice",
                    "INVOICE_NOT_MODIFIABLE",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        TaxProfile merchantProfile = fetchMerchantTaxProfile(merchantId);
        CustomerTaxProfile customerProfile = fetchCustomerTaxProfile(customerId);

        // Remove any existing GST lines before recomputing
        invoiceLineRepository.deleteByInvoiceIdAndLineTypeIn(invoiceId, GST_LINE_TYPES);

        List<InvoiceLine> remainingLines = invoiceLineRepository.findByInvoiceId(invoiceId);
        BigDecimal subtotal = sum(remainingLines, InvoiceLineType.PLAN_CHARGE, InvoiceLineType.PRORATION);
        BigDecimal discountTotal = sum(remainingLines, InvoiceLineType.DISCOUNT).abs();
        BigDecimal taxableBase = subtotal.subtract(discountTotal).max(BigDecimal.ZERO);

        boolean intraState = merchantProfile.getLegalStateCode()
                .equalsIgnoreCase(customerProfile.getStateCode());

        if (!customerProfile.isTaxExempt() && taxableBase.compareTo(BigDecimal.ZERO) > 0) {
            if (intraState) {
                BigDecimal cgst = taxableBase.multiply(CGST_RATE).setScale(2, RoundingMode.HALF_UP);
                BigDecimal sgst = taxableBase.multiply(SGST_RATE).setScale(2, RoundingMode.HALF_UP);
                invoiceLineRepository.save(InvoiceLine.builder()
                        .invoiceId(invoiceId)
                        .lineType(InvoiceLineType.CGST)
                        .description("CGST @ 9% on ₹" + taxableBase.toPlainString())
                        .amount(cgst)
                        .build());
                invoiceLineRepository.save(InvoiceLine.builder()
                        .invoiceId(invoiceId)
                        .lineType(InvoiceLineType.SGST)
                        .description("SGST @ 9% on ₹" + taxableBase.toPlainString())
                        .amount(sgst)
                        .build());
            } else {
                BigDecimal igst = taxableBase.multiply(IGST_RATE).setScale(2, RoundingMode.HALF_UP);
                invoiceLineRepository.save(InvoiceLine.builder()
                        .invoiceId(invoiceId)
                        .lineType(InvoiceLineType.IGST)
                        .description("IGST @ 18% on ₹" + taxableBase.toPlainString())
                        .amount(igst)
                        .build());
            }
        }

        Invoice updated = invoiceTotalService.recomputeTotals(invoice);

        List<InvoiceLine> finalLines = invoiceLineRepository.findByInvoiceId(invoiceId);
        List<InvoiceLineDTO> lineDTOs = finalLines.stream()
                .map(l -> InvoiceLineDTO.builder()
                        .id(l.getId())
                        .invoiceId(l.getInvoiceId())
                        .lineType(l.getLineType())
                        .description(l.getDescription())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return InvoiceSummaryDTO.builder()
                .id(updated.getId())
                .invoiceNumber(updated.getInvoiceNumber())
                .merchantId(updated.getMerchantId())
                .userId(updated.getUserId())
                .subscriptionId(updated.getSubscriptionId())
                .status(updated.getStatus())
                .currency(updated.getCurrency())
                .subtotal(updated.getSubtotal())
                .discountTotal(updated.getDiscountTotal())
                .creditTotal(updated.getCreditTotal())
                .taxTotal(updated.getTaxTotal())
                .grandTotal(updated.getGrandTotal())
                .dueDate(updated.getDueDate())
                .periodStart(updated.getPeriodStart())
                .periodEnd(updated.getPeriodEnd())
                .createdAt(updated.getCreatedAt())
                .lines(lineDTOs)
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice fetchInvoice(Long invoiceId, Long merchantId) {
        return invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId,
                        "INVOICE_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
    }

    private TaxProfile fetchMerchantTaxProfile(Long merchantId) {
        return taxProfileRepository.findByMerchantId(merchantId)
                .orElseThrow(() -> new MembershipException(
                        "Tax profile not found for merchant: " + merchantId,
                        "TAX_PROFILE_NOT_FOUND",
                        HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private CustomerTaxProfile fetchCustomerTaxProfile(Long customerId) {
        return customerTaxProfileRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new MembershipException(
                        "Tax profile not found for customer: " + customerId,
                        "CUSTOMER_TAX_PROFILE_NOT_FOUND",
                        HttpStatus.UNPROCESSABLE_ENTITY));
    }

    private BigDecimal sum(List<InvoiceLine> lines, InvoiceLineType... types) {
        Set<InvoiceLineType> typeSet = EnumSet.copyOf(List.of(types));
        return lines.stream()
                .filter(l -> typeSet.contains(l.getLineType()))
                .map(InvoiceLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private InvoiceTaxBreakdownDTO.TaxLineDTO buildTaxLineDTO(Long lineId, InvoiceLineType type,
                                                               String description, BigDecimal amount) {
        return InvoiceTaxBreakdownDTO.TaxLineDTO.builder()
                .lineId(lineId)
                .lineType(type.name())
                .description(description)
                .amount(amount)
                .build();
    }
}
