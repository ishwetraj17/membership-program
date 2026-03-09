package com.firstclub.platform.dedup.controller;

import com.firstclub.payments.repository.WebhookEventRepository;
import com.firstclub.platform.dedup.BusinessEffectFingerprintRepository;
import com.firstclub.platform.dedup.BusinessEffectDedupService;
import com.firstclub.platform.dedup.dto.BusinessEffectFingerprintResponseDTO;
import com.firstclub.platform.dedup.dto.WebhookDedupStatusDTO;
import com.firstclub.platform.redis.RedisKeyFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin read-only endpoints for the deduplication layer diagnostics.
 *
 * <p>All endpoints require the {@code ADMIN} role and are intended for
 * operator / on-call debugging only — they expose no user-identifying data.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Deduplication Admin", description = "Inspect business-effect and webhook dedup state")
public class DedupAdminController {

    private static final int DEFAULT_LOOKBACK_HOURS = 24;

    private final BusinessEffectFingerprintRepository fingerprintRepository;
    private final BusinessEffectDedupService          dedupService;
    private final WebhookEventRepository              webhookEventRepository;
    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;

    // ── Business-effect fingerprints ──────────────────────────────────────

    /**
     * Lists recently recorded business-effect fingerprints.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code effectType} (optional) — filter to a single effect type</li>
     *   <li>{@code since} (optional, ISO-8601 datetime) — lower bound, defaults to 24 h ago</li>
     * </ul>
     */
    @GetMapping("/api/v2/admin/dedup/business-effects")
    @Operation(
        summary  = "List recently recorded business-effect dedup fingerprints",
        description = "Returns rows from business_effect_fingerprints within the requested time window. "
            + "Fingerprints are truncated for display — they are not reversible."
    )
    public ResponseEntity<List<BusinessEffectFingerprintResponseDTO>> listBusinessEffects(
            @RequestParam(required = false) String effectType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {

        LocalDateTime from = (since != null) ? since
                : LocalDateTime.now().minusHours(DEFAULT_LOOKBACK_HOURS);

        List<BusinessEffectFingerprintResponseDTO> results;
        if (effectType != null && !effectType.isBlank()) {
            results = fingerprintRepository.findRecentByEffectType(effectType, from)
                    .stream()
                    .map(this::toResponseDTO)
                    .collect(Collectors.toList());
        } else {
            results = fingerprintRepository.findRecent(from)
                    .stream()
                    .map(this::toResponseDTO)
                    .collect(Collectors.toList());
        }
        return ResponseEntity.ok(results);
    }

    // ── Webhook dedup status ──────────────────────────────────────────────

    /**
     * Returns the current dedup state for a specific inbound webhook event.
     *
     * <p>Checks both the Redis fast-path marker and the DB processed flag so
     * operators can diagnose why a webhook was (or was not) treated as a duplicate.
     */
    @GetMapping("/api/v2/admin/webhooks/dedup/{provider}/{eventId}")
    @Operation(
        summary  = "Get dedup status for an inbound webhook event",
        description = "Returns the Redis marker presence and DB processed-flag for the given "
            + "provider + eventId combination."
    )
    public ResponseEntity<WebhookDedupStatusDTO> getWebhookDedupStatus(
            @PathVariable String provider,
            @PathVariable String eventId) {

        boolean redisMarker = checkRedisMarker(provider, eventId);
        boolean dbProcessed = webhookEventRepository.findByEventId(eventId)
                .map(e -> e.isProcessed())
                .orElse(false);
        String status = (redisMarker || dbProcessed) ? "DUPLICATE" : "NEW";

        return ResponseEntity.ok(WebhookDedupStatusDTO.builder()
                .provider(provider)
                .eventId(eventId)
                .redisMarkerPresent(redisMarker)
                .dbProcessed(dbProcessed)
                .status(status)
                .build());
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private boolean checkRedisMarker(String provider, String eventId) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) return false;
        try {
            String key = keyFactory.webhookEventDedupKey(provider, eventId);
            return Boolean.TRUE.equals(template.hasKey(key));
        } catch (Exception ex) {
            return false;
        }
    }

    private BusinessEffectFingerprintResponseDTO toResponseDTO(
            com.firstclub.platform.dedup.BusinessEffectFingerprint row) {
        String fp = row.getFingerprint();
        String prefix = (fp != null && fp.length() > 16) ? fp.substring(0, 16) + "…" : fp;
        return BusinessEffectFingerprintResponseDTO.builder()
                .id(row.getId())
                .effectType(row.getEffectType())
                .fingerprintPrefix(prefix)
                .referenceType(row.getReferenceType())
                .referenceId(row.getReferenceId())
                .createdAt(row.getCreatedAt())
                .build();
    }
}
