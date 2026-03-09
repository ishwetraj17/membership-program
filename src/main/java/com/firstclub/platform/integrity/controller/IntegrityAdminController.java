package com.firstclub.platform.integrity.controller;

import com.firstclub.platform.integrity.IntegrityCheckRegistry;
import com.firstclub.platform.integrity.IntegrityRunService;
import com.firstclub.platform.integrity.dto.IntegrityFindingResponseDTO;
import com.firstclub.platform.integrity.dto.IntegrityRunRequestDTO;
import com.firstclub.platform.integrity.dto.IntegrityRunResponseDTO;
import com.firstclub.platform.integrity.entity.IntegrityCheckRun;
import com.firstclub.platform.integrity.repository.IntegrityCheckFindingRepository;
import com.firstclub.platform.integrity.repository.IntegrityCheckRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Admin REST endpoints for the Unified Invariant Engine.
 *
 * <p>All routes require {@code ROLE_ADMIN}.  The endpoints allow operators to
 * trigger integrity check runs, inspect previous run summaries, and drill into
 * individual findings.
 *
 * <pre>
 *   POST  /api/v2/admin/integrity/check                → run all checkers
 *   POST  /api/v2/admin/integrity/check/{invariantKey} → run one checker
 *   GET   /api/v2/admin/integrity/runs                 → list runs (most recent first)
 *   GET   /api/v2/admin/integrity/runs/{runId}         → run detail with findings
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/integrity")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Integrity Engine", description = "Admin — unified invariant integrity checks")
public class IntegrityAdminController {

    private final IntegrityRunService integrityRunService;
    private final IntegrityCheckRegistry integrityCheckRegistry;
    private final IntegrityCheckRunRepository runRepository;
    private final IntegrityCheckFindingRepository findingRepository;

    // -----------------------------------------------------------------------
    // Trigger endpoints
    // -----------------------------------------------------------------------

    @Operation(
        summary = "Run all integrity checkers",
        description = "Executes every registered invariant checker and persists results. "
                    + "Pass an optional merchantId to scope the run to one tenant.")
    @ApiResponse(responseCode = "200", description = "Run completed; see status and failedChecks for results")
    @PostMapping("/check")
    public ResponseEntity<IntegrityRunResponseDTO> runAll(
            @RequestBody(required = false) IntegrityRunRequestDTO request) {

        Long merchantId = request != null ? request.merchantId() : null;
        Long userId     = request != null ? request.initiatedByUserId() : null;

        log.info("Integrity run-all: merchantId={}, userId={}", merchantId, userId);
        IntegrityCheckRun run = integrityRunService.runAll(merchantId, userId);
        return ResponseEntity.ok(IntegrityRunResponseDTO.of(run));
    }

    @Operation(
        summary = "Run a single integrity checker",
        description = "Executes one named invariant checker and persists the result.")
    @ApiResponse(responseCode = "200", description = "Run completed")
    @ApiResponse(responseCode = "404", description = "Unknown invariant key")
    @PostMapping("/check/{invariantKey}")
    public ResponseEntity<IntegrityRunResponseDTO> runSingle(
            @Parameter(description = "Invariant key, e.g. billing.invoice_total_equals_line_sum")
                @PathVariable String invariantKey,
            @RequestBody(required = false) IntegrityRunRequestDTO request) {

        if (integrityCheckRegistry.findByKey(invariantKey).isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND,
                    "Unknown invariant key: " + invariantKey);
        }

        Long merchantId = request != null ? request.merchantId() : null;
        Long userId     = request != null ? request.initiatedByUserId() : null;

        log.info("Integrity run-single: key={}, merchantId={}, userId={}", invariantKey, merchantId, userId);
        IntegrityCheckRun run = integrityRunService.runSingle(invariantKey, merchantId, userId);
        return ResponseEntity.ok(IntegrityRunResponseDTO.of(run));
    }

    // -----------------------------------------------------------------------
    // Query endpoints
    // -----------------------------------------------------------------------

    @Operation(
        summary = "List integrity runs",
        description = "Returns all integrity check runs ordered by most recent first.")
    @ApiResponse(responseCode = "200", description = "Runs retrieved")
    @GetMapping("/runs")
    public ResponseEntity<List<IntegrityRunResponseDTO>> listRuns() {
        List<IntegrityRunResponseDTO> runs = runRepository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(IntegrityRunResponseDTO::of)
                .collect(Collectors.toList());
        return ResponseEntity.ok(runs);
    }

    @Operation(
        summary = "Get integrity run detail",
        description = "Returns a single integrity run along with all its checker findings.")
    @ApiResponse(responseCode = "200", description = "Run and findings retrieved")
    @ApiResponse(responseCode = "404", description = "Run not found")
    @GetMapping("/runs/{runId}")
    public ResponseEntity<IntegrityRunResponseDTO> getRunDetail(
            @Parameter(description = "Run ID") @PathVariable Long runId) {

        IntegrityCheckRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "Integrity run not found: " + runId));

        List<IntegrityFindingResponseDTO> findings = findingRepository.findByRunId(runId)
                .stream()
                .map(IntegrityFindingResponseDTO::of)
                .collect(Collectors.toList());

        return ResponseEntity.ok(IntegrityRunResponseDTO.withFindings(run, findings));
    }
}
