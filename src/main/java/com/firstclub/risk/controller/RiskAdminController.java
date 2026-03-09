package com.firstclub.risk.controller;

import com.firstclub.risk.dto.BlockIpRequest;
import com.firstclub.risk.dto.RiskEventDTO;
import com.firstclub.risk.repository.RiskEventRepository;
import com.firstclub.risk.service.RiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoints for the risk-control module.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/admin/risk")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Risk Admin", description = "Risk event inspection and IP block-list management")
public class RiskAdminController {

    private final RiskService riskService;
    private final RiskEventRepository riskEventRepository;

    // ------------------------------------------------------------------
    // GET /api/v1/admin/risk/events
    // ------------------------------------------------------------------

    @Operation(summary = "List all risk events",
               description = "Returns all recorded risk events in reverse-chronological order.")
    @GetMapping("/events")
    public ResponseEntity<List<RiskEventDTO>> listEvents() {
        List<RiskEventDTO> events = riskEventRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(RiskEventDTO::from)
                .toList();
        return ResponseEntity.ok(events);
    }

    // ------------------------------------------------------------------
    // POST /api/v1/admin/risk/block/ip
    // ------------------------------------------------------------------

    @Operation(summary = "Add an IP address to the block-list",
               description = "Immediately blocks all payment attempts from the specified IP.")
    @PostMapping("/block/ip")
    public ResponseEntity<Void> blockIp(@Valid @RequestBody BlockIpRequest request) {
        riskService.blockIp(request.ip(), request.reason());
        return ResponseEntity.noContent().build();
    }
}
