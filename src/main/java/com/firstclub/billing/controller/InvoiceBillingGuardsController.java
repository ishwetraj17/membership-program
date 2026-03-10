package com.firstclub.billing.controller;

import com.firstclub.billing.credit.CreditCarryForwardService;
import com.firstclub.billing.dto.ApplyCreditRequestDTO;
import com.firstclub.billing.dto.CreditNoteDTO;
import com.firstclub.billing.dto.InvoiceDTO;
import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.guard.InvoiceInvariantService;
import com.firstclub.billing.rebuild.InvoiceRebuildService;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.billing.service.InvoiceService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 17 billing guardrails API.
 *
 * <pre>
 * POST /api/v2/invoices/{id}/rebuild-totals    rebuild corrupted invoice totals (ops)
 * POST /api/v2/invoices/{id}/apply-credit      apply credit wallet to an open invoice
 * GET  /api/v2/customers/{customerId}/credits  list all credit notes for a customer
 * </pre>
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class InvoiceBillingGuardsController {

    private final InvoiceRebuildService invoiceRebuildService;
    private final CreditCarryForwardService creditCarryForwardService;
    private final InvoiceInvariantService invoiceInvariantService;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;

    // ── POST /api/v2/invoices/{id}/rebuild-totals ────────────────────────────

    /**
     * Recalculates all total fields from invoice lines and stamps audit metadata.
     * For use by operations/support when totals drift out of sync with lines.
     *
     * @param invoiceId  invoice to rebuild
     * @param principal  injected by Spring Security (username of caller)
     */
    @PostMapping("/invoices/{id}/rebuild-totals")
    public ResponseEntity<InvoiceDTO> rebuildTotals(
            @PathVariable("id") Long invoiceId,
            Principal principal) {

        String rebuiltBy = principal != null ? principal.getName() : "anonymous";
        InvoiceDTO result = invoiceRebuildService.rebuildTotals(invoiceId, rebuiltBy);
        return ResponseEntity.ok(result);
    }

    // ── GET /api/v2/customers/{customerId}/credits ───────────────────────────

    /**
     * Returns all credit notes for the given customer (user), newest-first.
     */
    @GetMapping("/customers/{customerId}/credits")
    public ResponseEntity<List<CreditNoteDTO>> getCustomerCredits(
            @PathVariable Long customerId) {

        List<CreditNote> notes = creditCarryForwardService.getCreditsForUser(customerId);
        List<CreditNoteDTO> dtos = notes.stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // ── POST /api/v2/invoices/{id}/apply-credit ───────────────────────────────

    /**
     * Manually applies credit-wallet balance to an OPEN invoice.
     *
     * <p>If {@code amountToApply} is not specified the full outstanding grand-total
     * is used. Credit overflow is preserved as a carry-forward credit note.
     *
     * @param invoiceId invoice to apply credit against (must be OPEN)
     * @param request   optional body specifying how much to apply
     */
    @PostMapping("/invoices/{id}/apply-credit")
    public ResponseEntity<InvoiceDTO> applyCredit(
            @PathVariable("id") Long invoiceId,
            @RequestBody(required = false) ApplyCreditRequestDTO request) {

        // Delegate core credit application to InvoiceService (existing logic)
        InvoiceDTO result = invoiceService.applyAvailableCredits(
                resolveUserId(invoiceId), invoiceId);
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long resolveUserId(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new MembershipException(
                        "Invoice not found: " + invoiceId, "INVOICE_NOT_FOUND"))
                .getUserId();
    }

    private CreditNoteDTO toDto(CreditNote note) {
        return CreditNoteDTO.builder()
                .id(note.getId())
                .userId(note.getUserId())
                .currency(note.getCurrency())
                .amount(note.getAmount())
                .reason(note.getReason())
                .createdAt(note.getCreatedAt())
                .usedAmount(note.getUsedAmount())
                .availableBalance(note.getAvailableBalance())
                .customerId(note.getCustomerId())
                .availableAmountMinor(note.getAvailableAmountMinor())
                .sourceInvoiceId(note.getSourceInvoiceId())
                .expiresAt(note.getExpiresAt())
                .build();
    }
}
