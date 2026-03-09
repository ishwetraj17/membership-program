package com.firstclub.ledger.controller;

import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.dto.LedgerEntryResponseDTO;
import com.firstclub.ledger.dto.LedgerReversalRequestDTO;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.reversal.LedgerReversalService;
import com.firstclub.ledger.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger Admin", description = "Double-entry ledger administration")
public class LedgerController {

    private final LedgerService         ledgerService;
    private final LedgerReversalService ledgerReversalService;

    // ── Balance sheet ─────────────────────────────────────────────────────────

    @GetMapping("/balances")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get balance sheet",
               description = "Returns debit/credit totals and normal-balance for each ledger account. Admin only.")
    public ResponseEntity<List<LedgerAccountBalanceDTO>> getBalances() {
        return ResponseEntity.ok(ledgerService.getBalances());
    }

    // ── Phase 10: Single account balance ─────────────────────────────────────

    @GetMapping("/accounts/{code}/balance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get balance for a specific ledger account",
               description = "Returns debit total, credit total and net balance for the named account "
                           + "(e.g. PG_CLEARING, BANK, DISPUTE_RESERVE). Admin only.")
    public ResponseEntity<LedgerAccountBalanceDTO> getAccountBalance(@PathVariable String code) {
        return ResponseEntity.ok(ledgerService.getAccountBalanceByCode(code));
    }

    // ── Phase 10: Single entry read ───────────────────────────────────────────

    @GetMapping("/entries/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get a ledger entry by id",
               description = "Returns the journal entry header plus all debit/credit lines. Admin only.")
    public ResponseEntity<LedgerEntryResponseDTO> getEntry(@PathVariable Long id) {
        return ResponseEntity.ok(ledgerService.getEntry(id));
    }

    // ── Phase 10: Reversal ────────────────────────────────────────────────────

    @PostMapping("/entries/{id}/reverse")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reverse a ledger entry",
               description = "Creates a REVERSAL entry that mirrors the original with all debit/credit sides "
                           + "flipped. The original entry is preserved unchanged. "
                           + "Reason text is mandatory. Each entry may be reversed at most once. Admin only.")
    public ResponseEntity<LedgerEntryResponseDTO> reverseEntry(
            @PathVariable Long id,
            @Valid @RequestBody LedgerReversalRequestDTO request) {

        LedgerEntry reversal = ledgerReversalService.reverse(
                id, request.getReason(), request.getPostedByUserId());

        return ResponseEntity.ok(ledgerService.getEntry(reversal.getId()));
    }
}

