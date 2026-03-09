package com.firstclub.events.controller;

import com.firstclub.events.dto.ReplayReportDTO;
import com.firstclub.events.dto.ReplayRequestDTO;
import com.firstclub.events.dto.ReplayResponseDTO;
import com.firstclub.events.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Admin endpoints for replaying and re-validating a slice of the immutable
 * domain event log.
 *
 * <p>V1 legacy endpoint kept for backwards compatibility.
 * <p>V2 endpoints accept a rich {@link ReplayRequestDTO} supporting merchant,
 * aggregate, and event-type scoping as well as projection rebuild.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Domain Event Replay (Admin)", description = "Re-validate invariants over the domain event log")
public class ReplayController {

    private final ReplayService replayService;

    // -------------------------------------------------------------------------
    // V1 – legacy (kept for backwards compatibility)
    // -------------------------------------------------------------------------

    @Operation(summary = "Replay domain events and validate invariants – V1 (ADMIN)")
    @PostMapping("/api/v1/admin/replay")
    public ResponseEntity<ReplayReportDTO> replayV1(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "VALIDATE_ONLY") String mode) {

        return ResponseEntity.ok(replayService.replay(from, to, mode));
    }

    // -------------------------------------------------------------------------
    // V2 – rich scoped replay
    // -------------------------------------------------------------------------

    @Operation(summary = "Validate invariants over a scoped event window – V2 (ADMIN)")
    @PostMapping("/api/v2/admin/replay/validate")
    public ResponseEntity<ReplayResponseDTO> validate(
            @Valid @RequestBody ReplayRequestDTO request) {

        request.setMode("VALIDATE_ONLY");
        return ResponseEntity.ok(replayService.replay(request));
    }

    @Operation(summary = "Rebuild a read-model projection from the event log – V2 (ADMIN)")
    @PostMapping("/api/v2/admin/replay/rebuild-projection")
    public ResponseEntity<ReplayResponseDTO> rebuildProjection(
            @Valid @RequestBody ReplayRequestDTO request) {

        request.setMode("REBUILD_PROJECTION");
        return ResponseEntity.ok(replayService.replay(request));
    }
}
