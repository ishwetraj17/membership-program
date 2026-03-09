package com.firstclub.integrity.api;

import com.firstclub.integrity.IntegrityCheckService;
import com.firstclub.integrity.api.dto.IntegrityCheckRunResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only REST endpoints for triggering and inspecting integrity-check runs.
 *
 * <p>Both endpoints require the {@code ADMIN} role.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/integrity")
@RequiredArgsConstructor
public class IntegrityAdminController {

    private final IntegrityCheckService integrityCheckService;

    /**
     * Trigger a full invariant-check run.
     *
     * <p>POST /api/v1/admin/integrity/check
     *
     * @param triggeredBy   optional query-param to label who triggered this run
     *                      (defaults to "admin-api" if omitted)
     * @param xRequestId    optional X-Request-Id header
     * @param xCorrelationId optional X-Correlation-Id header
     */
    @PostMapping("/check")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<IntegrityCheckRunResponseDTO> triggerCheck(
            @RequestParam(name = "triggered_by", defaultValue = "admin-api") String triggeredBy,
            @RequestHeader(name = "X-Request-Id",    required = false) String xRequestId,
            @RequestHeader(name = "X-Correlation-Id", required = false) String xCorrelationId) {

        log.info("Integrity check triggered by={} requestId={}", triggeredBy, xRequestId);
        IntegrityCheckRunResponseDTO response =
                integrityCheckService.runCheck(triggeredBy, xRequestId, xCorrelationId);
        return ResponseEntity.ok(response);
    }

    /**
     * Fetch the details of a previously completed integrity-check run.
     *
     * <p>GET /api/v1/admin/integrity/check-runs/{id}
     */
    @GetMapping("/check-runs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<IntegrityCheckRunResponseDTO> getCheckRun(@PathVariable Long id) {
        return ResponseEntity.ok(integrityCheckService.getRunById(id));
    }
}
