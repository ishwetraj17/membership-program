package com.firstclub.payments.routing.controller;

import com.firstclub.payments.routing.dto.GatewayHealthResponseDTO;
import com.firstclub.payments.routing.dto.GatewayHealthUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingDecisionSnapshot;
import com.firstclub.payments.routing.service.GatewayHealthService;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/gateways/health")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Gateway Health Admin", description = "Inspect and update gateway health status and routing decision audit trail")
public class GatewayHealthAdminController {

    private final GatewayHealthService gatewayHealthService;
    private final PaymentRoutingService paymentRoutingService;

    @GetMapping
    @Operation(summary = "List health snapshots for all registered gateways")
    public ResponseEntity<List<GatewayHealthResponseDTO>> listAll() {
        return ResponseEntity.ok(gatewayHealthService.getAllHealthSnapshots());
    }

    @PutMapping("/{gatewayName}")
    @Operation(summary = "Update (or register) the health record for a gateway. Refreshes the Redis health cache.")
    public ResponseEntity<GatewayHealthResponseDTO> update(
            @PathVariable String gatewayName,
            @Valid @RequestBody GatewayHealthUpdateRequestDTO request) {
        return ResponseEntity.ok(gatewayHealthService.updateGatewayHealth(gatewayName, request));
    }

    @GetMapping("/routing-decisions/{attemptId}")
    @Operation(
        summary = "Retrieve the routing decision snapshot for a payment attempt",
        description = "Returns the RoutingDecisionSnapshot persisted at the time the gateway was selected "
            + "for the given payment attempt. Returns 204 No Content if the attempt exists but has no "
            + "snapshot (pre-Phase-5 attempt or routing fell back to a request hint)."
    )
    public ResponseEntity<RoutingDecisionSnapshot> getRoutingDecision(@PathVariable Long attemptId) {
        return paymentRoutingService.getRoutingDecision(attemptId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}

