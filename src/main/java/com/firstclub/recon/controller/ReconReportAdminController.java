package com.firstclub.recon.controller;

import com.firstclub.recon.dto.ReconReportDTO;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin/recon/reports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Recon Reports", description = "Reconciliation report access")
public class ReconReportAdminController {

    private final ReconReportRepository   reportRepository;
    private final ReconMismatchRepository mismatchRepository;

    @GetMapping
    @Operation(summary = "List all reconciliation reports (paginated)")
    public ResponseEntity<Page<ReconReport>> listReports(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(reportRepository.findAll(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a reconciliation report by ID with its mismatches")
    public ResponseEntity<ReconReportDTO> getReport(@PathVariable Long id) {
        ReconReport report = reportRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("ReconReport not found: " + id));
        return ResponseEntity.ok(ReconReportDTO.from(report, mismatchRepository.findByReportId(id)));
    }
}
