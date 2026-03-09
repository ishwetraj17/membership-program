package com.firstclub.platform.ops.controller;

import com.firstclub.platform.ops.dto.DeepHealthResponseDTO;
import com.firstclub.platform.ops.dto.ScalingReadinessDTO;
import com.firstclub.platform.ops.dto.SystemSummaryDTO;
import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.dto.DlqSummaryDTO;
import com.firstclub.platform.ops.dto.FeatureFlagResponseDTO;
import com.firstclub.platform.ops.dto.FeatureFlagUpdateRequestDTO;
import com.firstclub.platform.ops.dto.IdempotencyDebugDTO;
import com.firstclub.platform.ops.dto.OutboxLagResponseDTO;
import com.firstclub.platform.ops.dto.RateLimitPolicyDTO;
import com.firstclub.platform.ops.dto.RedisHealthStatusDTO;
import com.firstclub.platform.idempotency.IdempotencyKeyEntity;
import com.firstclub.platform.idempotency.IdempotencyService;
import com.firstclub.platform.idempotency.IdempotencyProcessingMarker;
import com.firstclub.platform.idempotency.IdempotencyResponseEnvelope;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import com.firstclub.platform.ratelimit.RateLimitPolicy;
import com.firstclub.platform.ratelimit.RateLimitProperties;
import com.firstclub.platform.ratelimit.RedisSlidingWindowRateLimiter;
import com.firstclub.platform.ops.service.DeepHealthService;
import com.firstclub.platform.ops.service.DlqOpsService;
import com.firstclub.platform.ops.service.FeatureFlagService;
import com.firstclub.platform.ops.service.OutboxOpsService;
import com.firstclub.platform.redis.RedisAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v2/admin/system")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Ops Admin — System", description = "Deep health, DLQ tooling, outbox lag, and feature flags")
public class OpsAdminController {

    private final DeepHealthService     deepHealthService;
    private final DlqOpsService         dlqOpsService;
    private final OutboxOpsService       outboxOpsService;
    private final FeatureFlagService     featureFlagService;
    private final RedisAvailabilityService redisAvailabilityService;
    private final IdempotencyService     idempotencyService;
    private final RedisIdempotencyStore  redisIdempotencyStore;
    private final RateLimitProperties    rateLimitProperties;
    private final RedisSlidingWindowRateLimiter rateLimiter;

    // ── Deep Health ──────────────────────────────────────────────────────────

    @GetMapping("/health/deep")
    @Operation(summary = "Deep system health report",
               description = "Aggregates outbox, DLQ, webhook, revenue recognition, and reconciliation counters.")
    public ResponseEntity<DeepHealthResponseDTO> deepHealth() {
        return ResponseEntity.ok(deepHealthService.buildDeepHealthReport());
    }

    @GetMapping("/summary")
    @Operation(summary = "System operational summary",
               description = "Aggregates outbox, DLQ, webhook, recon, dunning backlog, integrity violations, and stale job locks.")
    public ResponseEntity<SystemSummaryDTO> systemSummary() {
        return ResponseEntity.ok(deepHealthService.buildSystemSummary());
    }

    @GetMapping("/scaling-readiness")
    @Operation(summary = "Scaling readiness report",
               description = "Returns a static architectural analysis: bottlenecks, decomposition candidates, and evolution stages.")
    public ResponseEntity<ScalingReadinessDTO> scalingReadiness() {
        return ResponseEntity.ok(deepHealthService.buildScalingReadiness());
    }

    @GetMapping("/redis/health")
    @Operation(summary = "Redis infrastructure health",
               description = "Returns current Redis status (UP/DOWN/DEGRADED/DISABLED), PING latency, and connection details.")
    public ResponseEntity<RedisHealthStatusDTO> redisHealth() {
        String status = redisAvailabilityService.getStatus().name();
        long latencyMs = redisAvailabilityService.getPingLatencyMs();
        String host = redisAvailabilityService.getHost();
        int port = redisAvailabilityService.getPort();
        String message = buildRedisMessage(status, latencyMs);
        return ResponseEntity.ok(new RedisHealthStatusDTO(status, latencyMs, host, port, message, LocalDateTime.now()));
    }

    private static String buildRedisMessage(String status, long latencyMs) {
        return switch (status) {
            case "UP"       -> "Redis is reachable. PING latency: " + latencyMs + " ms";
            case "DEGRADED" -> "Redis responded but may be under stress. PING latency: " + latencyMs + " ms";
            case "DOWN"     -> "Redis is unreachable. Check connection settings and Redis server status.";
            case "DISABLED" -> "Redis is disabled (app.redis.enabled=false). All operations use PostgreSQL.";
            default         -> "Unknown Redis status: " + status;
        };
    }

    // ── DLQ ─────────────────────────────────────────────────────────────────

    @GetMapping("/dlq")
    @Operation(summary = "List DLQ entries",
               description = "Returns all dead-letter entries, optionally filtered by source and/or failureCategory.")
    public ResponseEntity<List<DlqEntryResponseDTO>> listDlq(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String failureCategory) {
        return ResponseEntity.ok(dlqOpsService.listDlqEntries(source, failureCategory));
    }

    @GetMapping("/dlq/summary")
    @Operation(summary = "DLQ aggregate summary",
               description = "Returns total DLQ count, grouped by source and failure category.")
    public ResponseEntity<DlqSummaryDTO> dlqSummary() {
        return ResponseEntity.ok(dlqOpsService.getDlqSummary());
    }

    @PostMapping("/dlq/{id}/retry")
    @Operation(summary = "Retry a DLQ entry",
               description = "Re-queues the entry as a new outbox event and removes it from the DLQ.")
    public ResponseEntity<DlqEntryResponseDTO> retryDlqEntry(@PathVariable Long id) {
        return ResponseEntity.ok(dlqOpsService.retryDlqEntry(id));
    }

    // ── Outbox ───────────────────────────────────────────────────────────────

    @GetMapping("/outbox")
    @Operation(summary = "Outbox full summary",
               description = "Returns pending/failed/done counts, stale lease count, and oldest pending event age.")
    public ResponseEntity<OutboxLagResponseDTO> outboxSummary() {
        return ResponseEntity.ok(outboxOpsService.getOutboxLag());
    }

    @GetMapping("/outbox/lag")
    @Operation(summary = "Outbox lag summary",
               description = "Alias for GET /outbox — returns pending/failed/done counts grouped by status and event type.")
    public ResponseEntity<OutboxLagResponseDTO> outboxLag() {
        return ResponseEntity.ok(outboxOpsService.getOutboxLag());
    }

    // ── Feature Flags ────────────────────────────────────────────────────────

    @GetMapping("/feature-flags")
    @Operation(summary = "List all feature flags")
    public ResponseEntity<List<FeatureFlagResponseDTO>> listFeatureFlags() {
        return ResponseEntity.ok(featureFlagService.listFlags());
    }

    @PutMapping("/feature-flags/{flagKey}")
    @Operation(summary = "Create or update a global feature flag")
    public ResponseEntity<FeatureFlagResponseDTO> updateFeatureFlag(
            @PathVariable String flagKey,
            @Valid @RequestBody FeatureFlagUpdateRequestDTO request) {
        return ResponseEntity.ok(featureFlagService.updateFlag(flagKey, request));
    }

    // ── Idempotency Debug ────────────────────────────────────────────────────

    @GetMapping("/idempotency/{merchantId}/{idempotencyKey}")
    @Operation(summary = "Debug idempotency key state",
               description = "Returns the combined Redis and PostgreSQL state for a single idempotency key. " +
                             "Useful for diagnosing replay failures or stuck in-flight locks.")
    public ResponseEntity<IdempotencyDebugDTO> getIdempotencyState(
            @PathVariable String merchantId,
            @PathVariable String idempotencyKey) {

        // Check Redis
        String redisState = "ABSENT";
        String lockedAt   = null;

        Optional<IdempotencyResponseEnvelope> cached =
                redisIdempotencyStore.tryGetCachedResponse(merchantId, idempotencyKey);
        if (cached.isPresent()) {
            redisState = "CACHED";
        } else {
            Optional<IdempotencyProcessingMarker> marker =
                    redisIdempotencyStore.getProcessingMarker(merchantId, idempotencyKey);
            if (marker.isPresent()) {
                redisState = "PROCESSING";
                lockedAt   = marker.get().lockedAt();
            }
        }

        // Check DB
        Optional<IdempotencyKeyEntity> entity =
                idempotencyService.findByMerchantAndKey(merchantId, idempotencyKey);

        String        dbState     = "ABSENT";
        String        requestHash = null;
        String        endpointSig = null;
        Integer       statusCode  = null;
        String        contentType = null;
        LocalDateTime createdAt   = null;
        LocalDateTime expiresAt   = null;

        if (entity.isPresent()) {
            IdempotencyKeyEntity rec = entity.get();
            dbState     = rec.isProcessed() ? "PROCESSED" : "PLACEHOLDER";
            requestHash = rec.getRequestHash();
            endpointSig = rec.getEndpointSignature();
            statusCode  = rec.getStatusCode();
            contentType = rec.getContentType();
            createdAt   = rec.getCreatedAt();
            expiresAt   = rec.getExpiresAt();
        }

        return ResponseEntity.ok(new IdempotencyDebugDTO(
                merchantId, idempotencyKey,
                redisState, dbState,
                requestHash, endpointSig,
                statusCode, contentType,
                createdAt, expiresAt, lockedAt));
    }

    // ── Rate Limits ──────────────────────────────────────────────────────────

    @GetMapping("/rate-limits")
    @Operation(summary = "Rate limit policy overview",
               description = "Lists all policies with their effective limits and window sizes, " +
                             "and the total block count recorded in the last hour.")
    public ResponseEntity<List<RateLimitPolicyDTO>> rateLimitPolicies() {
        List<RateLimitPolicyDTO> policies = java.util.Arrays.stream(RateLimitPolicy.values())
                .map(p -> new RateLimitPolicyDTO(
                        p.name(),
                        p.getKeySegment(),
                        rateLimitProperties.resolveLimit(p),
                        rateLimitProperties.resolveWindow(p).toSeconds(),
                        buildKeyPatternExample(p)))
                .toList();
        return ResponseEntity.ok(policies);
    }

    private static String buildKeyPatternExample(RateLimitPolicy p) {
        return switch (p) {
            case AUTH_BY_IP       -> "{env}:firstclub:rl:auth:ip:{clientIp}";
            case AUTH_BY_EMAIL    -> "{env}:firstclub:rl:auth:user:{email}";
            case PAYMENT_CONFIRM  -> "{env}:firstclub:rl:payconfirm:{merchantId}:{customerId}";
            case WEBHOOK_INGEST   -> "{env}:firstclub:rl:webhook:{provider}:{clientIp}";
            case APIKEY_GENERAL   -> "{env}:firstclub:rl:apikey:{merchantId}:{keyPrefix}";
        };
    }
}
