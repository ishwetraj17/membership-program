package com.firstclub.recon.controller;

import com.firstclub.recon.dto.StatementImportRequestDTO;
import com.firstclub.recon.dto.StatementImportResponseDTO;
import com.firstclub.recon.service.StatementImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/recon/import-statement")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Statement Imports", description = "External gateway/bank statement import")
public class StatementImportAdminController {

    private final StatementImportService service;

    @PostMapping
    @Operation(summary = "Import an external gateway/bank statement CSV")
    public ResponseEntity<StatementImportResponseDTO> importStatement(
            @RequestBody StatementImportRequestDTO req) {
        return ResponseEntity.ok(service.importStatement(req));
    }

    @GetMapping
    @Operation(summary = "List imported statements for a merchant")
    public ResponseEntity<Page<StatementImportResponseDTO>> listImports(
            @RequestParam Long merchantId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.listImports(merchantId, pageable));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "Get a statement import by ID")
    public ResponseEntity<StatementImportResponseDTO> getImport(@PathVariable Long importId) {
        return ResponseEntity.ok(service.getImport(importId));
    }
}
