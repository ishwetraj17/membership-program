package com.firstclub.payments.routing.controller;

import com.firstclub.payments.routing.cache.GatewayHealthCache;
import com.firstclub.payments.routing.cache.RoutingRuleCache;
import com.firstclub.payments.routing.dto.GatewayRouteRuleCreateRequestDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleResponseDTO;
import com.firstclub.payments.routing.dto.GatewayRouteRuleUpdateRequestDTO;
import com.firstclub.payments.routing.dto.RoutingCacheStatusDTO;
import com.firstclub.payments.routing.service.PaymentRoutingService;
import com.firstclub.platform.redis.RedisAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v2/admin/gateway-routes")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Gateway Route Admin", description = "Manage gateway routing rules and inspect the routing cache")
public class GatewayRouteAdminController {

    private final PaymentRoutingService paymentRoutingService;
    private final RedisAvailabilityService redisAvailabilityService;

    @PostMapping
    @Operation(summary = "Create a gateway route rule")
    public ResponseEntity<GatewayRouteRuleResponseDTO> create(
            @Valid @RequestBody GatewayRouteRuleCreateRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentRoutingService.createRouteRule(request));
    }

    @GetMapping
    @Operation(summary = "List all gateway route rules ordered by priority")
    public ResponseEntity<List<GatewayRouteRuleResponseDTO>> listAll() {
        return ResponseEntity.ok(paymentRoutingService.getAllRouteRules());
    }

    @PutMapping("/{routeId}")
    @Operation(summary = "Update an existing gateway route rule")
    public ResponseEntity<GatewayRouteRuleResponseDTO> update(
            @PathVariable Long routeId,
            @Valid @RequestBody GatewayRouteRuleUpdateRequestDTO request) {
        return ResponseEntity.ok(paymentRoutingService.updateRouteRule(routeId, request));
    }

    @DeleteMapping("/{routeId}")
    @Operation(summary = "Deactivate a gateway route rule (soft delete). Evicts the routing rule cache.")
    public ResponseEntity<Void> deactivate(@PathVariable Long routeId) {
        paymentRoutingService.deactivateRouteRule(routeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routing-cache")
    @Operation(summary = "Inspect routing cache configuration. reports TTLs and key patterns.")
    public ResponseEntity<RoutingCacheStatusDTO> routingCacheStatus() {
        boolean redisEnabled = redisAvailabilityService.isAvailable();
        String keyPattern = "{env}:firstclub:routing:{scope}:{methodType}:{currency}:{retryNumber}";
        String note = redisEnabled
                ? "Redis is UP. Routing rules (TTL=" + RoutingRuleCache.TTL_SECONDS
                + "s) and gateway health (TTL=" + GatewayHealthCache.TTL_SECONDS
                + "s) are cached. Scopes: merchantId or 'global'."
                : "Redis is unavailable. All routing decisions read directly from the database.";

        RoutingCacheStatusDTO status = new RoutingCacheStatusDTO(
                redisEnabled,
                GatewayHealthCache.TTL_SECONDS,
                RoutingRuleCache.TTL_SECONDS,
                keyPattern,
                note,
                LocalDateTime.now());
        return ResponseEntity.ok(status);
    }
}

