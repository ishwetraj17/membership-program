package com.firstclub.billing.rebuild;

import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.dto.InvoiceLineDTO;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceLineRepository;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceTotalService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ops-facing service that recalculates invoice totals from invoice lines and
 * stamps rebuild audit metadata.
 *
 * <p><strong>Why this exists:</strong> invoice totals can drift out of sync with
 * their constituent lines (e.g. after a migration bug, a manual line correction,
 * or a partial rollback). Rather than silently tolerating the inconsistency,
 * operators can call {@code rebuildTotals} to force a re-derivation and have the
 * system record who triggered the rebuild and when.
 *
 * <p>Only DRAFT or OPEN invoices may be rebuilt. PAID/VOID/UNCOLLECTIBLE invoices
 * are immutable once in a terminal state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceRebuildService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceTotalService invoiceTotalService;

    /**
     * Recalculates all total fields of an invoice from its lines and persists
     * the result with audit metadata.
     *
     * @param invoiceId invoice to rebuild
     * @param rebuiltBy principal (username/email) triggering the rebuild
     * @return the updated invoice DTO
     * @throws MembershipException if the invoice is not found or is in a terminal state
     */
    @Transactional
    public InvoiceDTO rebuildTotals(Long invoiceId, String rebuiltBy) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND"));

        if (isTerminal(invoice.getStatus())) {
            throw new MembershipException(
                    "Invoice " + invoiceId + " is in terminal state " + invoice.getStatus()
                            + " and cannot be rebuilt",
                    "INVOICE_TERMINAL_STATE",
                    HttpStatus.CONFLICT);
        }

        log.info("Rebuilding totals for invoice {} by {}", invoiceId, rebuiltBy);

        // Delegate total recalculation to the canonical service
        invoice = invoiceTotalService.recomputeTotals(invoice);

        // Stamp audit metadata
        invoice.setRebuiltAt(LocalDateTime.now());
        invoice.setRebuiltBy(rebuiltBy != null ? rebuiltBy : "system");
        invoice = invoiceRepository.save(invoice);

        log.info("Invoice {} totals rebuilt — grandTotal={}", invoiceId, invoice.getGrandTotal());
        return toDto(invoice);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isTerminal(InvoiceStatus status) {
        return status == InvoiceStatus.PAID
                || status == InvoiceStatus.VOID
                || status == InvoiceStatus.UNCOLLECTIBLE;
    }

    private InvoiceDTO toDto(Invoice invoice) {
        List<InvoiceLineDTO> lineDtos = invoiceLineRepository
                .findByInvoiceId(invoice.getId())
                .stream()
                .map(l -> InvoiceLineDTO.builder()
                        .id(l.getId())
                        .invoiceId(l.getInvoiceId())
                        .lineType(l.getLineType())
                        .description(l.getDescription())
                        .amount(l.getAmount())
                        .build())
                .collect(Collectors.toList());

        return InvoiceDTO.builder()
                .id(invoice.getId())
                .userId(invoice.getUserId())
                .subscriptionId(invoice.getSubscriptionId())
                .status(invoice.getStatus())
                .currency(invoice.getCurrency())
                .totalAmount(invoice.getTotalAmount())
                .dueDate(invoice.getDueDate())
                .periodStart(invoice.getPeriodStart())
                .periodEnd(invoice.getPeriodEnd())
                .createdAt(invoice.getCreatedAt())
                .updatedAt(invoice.getUpdatedAt())
                .merchantId(invoice.getMerchantId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .subtotal(invoice.getSubtotal())
                .discountTotal(invoice.getDiscountTotal())
                .creditTotal(invoice.getCreditTotal())
                .taxTotal(invoice.getTaxTotal())
                .grandTotal(invoice.getGrandTotal())
                .lines(lineDtos)
                .build();
    }
}
