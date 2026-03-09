package com.firstclub.payments.webhooks;

import com.firstclub.payments.repository.WebhookEventRepository;
import com.firstclub.platform.dedup.DedupResult;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Deduplication service for inbound gateway webhook events.
 *
 * <h3>Two checks are available</h3>
 * <ol>
 *   <li><b>Event-ID dedup</b> — keyed by the gateway-assigned {@code eventId}.
 *       Redis fast-path (TTL 3600 s) backed by a DB lookup against
 *       {@code webhook_events.event_id}.  Used as the primary check because
 *       event IDs are stable and unique per provider.</li>
 *   <li><b>Payload-hash dedup</b> — keyed by SHA-256 of the raw payload bytes.
 *       Redis-only (TTL 300 s), used as a fallback when the gateway omits or
 *       rotates event IDs, or when the same payload is delivered twice by
 *       different gateway servers with different IDs.</li>
 * </ol>
 *
 * <h3>Redis keys</h3>
 * <pre>{@code
 * {env}:firstclub:dedup:webhook:{provider}:{eventId}           TTL=3600s
 * {env}:firstclub:dedup:webhookfp:{provider}:{payloadHash}     TTL=300s
 * }</pre>
 *
 * <h3>Graceful degradation</h3>
 * When Redis is unavailable both checks fall through to {@link DedupResult#NEW}
 * so the caller proceeds to the authoritative DB UNIQUE constraint check that
 * is always active in {@code webhook_events.event_id}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDedupService {

    /** TTL for event-id dedup markers — 1 h covers gateway retry windows. */
    public static final int EVENT_ID_TTL_SECONDS   = 3_600;

    /** TTL for payload-hash markers — 5 min covers burst duplicates from same source. */
    public static final int PAYLOAD_HASH_TTL_SECONDS = 300;

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;
    private final WebhookEventRepository              webhookEventRepository;

    // ── Primary API ───────────────────────────────────────────────────────

    /**
     * Checks if a webhook event with the given provider + eventId has already
     * been received.
     *
     * <ol>
     *   <li>Redis SET NX (fast path, TTL 3600 s)</li>
     *   <li>DB {@code findByEventId} (durable fallback, seeds Redis on hit)</li>
     * </ol>
     *
     * @param provider the gateway identifier (e.g. "razorpay", "stripe")
     * @param eventId  the gateway-assigned event ID
     * @return {@link DedupResult#DUPLICATE} if already queued or processed,
     *         {@link DedupResult#NEW} otherwise
     */
    public DedupResult checkByEventId(String provider, String eventId) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template != null) {
            try {
                String key   = keyFactory.webhookEventDedupKey(provider, eventId);
                Boolean isNew = template.opsForValue()
                        .setIfAbsent(key, "1", Duration.ofSeconds(EVENT_ID_TTL_SECONDS));
                if (Boolean.FALSE.equals(isNew)) {
                    log.info("[WEBHOOK-DEDUP] Redis HIT — duplicate event {}/{}", provider, eventId);
                    return DedupResult.DUPLICATE;
                }
                // Redis says new (SET succeeded) — still verify DB to handle warm-up / failover gaps
            } catch (Exception ex) {
                log.warn("[WEBHOOK-DEDUP] Redis check failed for event {}/{} — DB fallback: {}",
                        provider, eventId, ex.getMessage());
            }
        }

        // DB authoritative check (always processed=true before we consider an event done)
        boolean alreadyProcessed = webhookEventRepository.findByEventId(eventId)
                .map(e -> e.isProcessed())
                .orElse(false);
        if (alreadyProcessed) {
            seedEventIdRedisKey(template, provider, eventId);   // re-warm cache
            log.info("[WEBHOOK-DEDUP] DB HIT — duplicate event {}/{}", provider, eventId);
            return DedupResult.DUPLICATE;
        }
        return DedupResult.NEW;
    }

    /**
     * Payload-hash–based dedup — Redis only, short TTL.
     * Used when the event-id is unknown, blank, or after an event-id check
     * has already returned {@link DedupResult#NEW} to catch exact-duplicate
     * payloads with distinct event IDs.
     *
     * @param provider    the gateway identifier
     * @param payloadHash SHA-256 hex of the raw webhook body (see {@link #computePayloadHash})
     * @return {@link DedupResult#DUPLICATE} if seen within the TTL window,
     *         {@link DedupResult#NEW} otherwise
     */
    public DedupResult checkByPayloadHash(String provider, String payloadHash) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            log.debug("[WEBHOOK-DEDUP] Redis unavailable — payload-hash check skipped for {}", provider);
            return DedupResult.NEW;
        }
        try {
            String  key   = keyFactory.webhookPayloadFingerprintKey(provider, payloadHash);
            Boolean isNew = template.opsForValue()
                    .setIfAbsent(key, "1", Duration.ofSeconds(PAYLOAD_HASH_TTL_SECONDS));
            if (Boolean.FALSE.equals(isNew)) {
                log.info("[WEBHOOK-DEDUP] Payload-hash HIT — duplicate payload for {}", provider);
                return DedupResult.DUPLICATE;
            }
        } catch (Exception ex) {
            log.warn("[WEBHOOK-DEDUP] Redis payload-hash check failed for {} — proceeding: {}",
                    provider, ex.getMessage());
        }
        return DedupResult.NEW;
    }

    /**
     * Seeds the event-id Redis key after an event has been marked processed in
     * the DB (e.g. after successful processing, to warm the cache for retries).
     *
     * @param provider gateway identifier
     * @param eventId  gateway-assigned event ID
     */
    public void recordWebhookReceived(String provider, String eventId) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        seedEventIdRedisKey(template, provider, eventId);
    }

    /**
     * Computes the SHA-256 fingerprint of a raw payload string.
     * Encoding is UTF-8. Result is lower-case hex.
     *
     * @param rawPayload the exact bytes received from the gateway
     * @return 64-character lower-case hex string
     */
    public String computePayloadHash(String rawPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JVM spec — this cannot happen
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void seedEventIdRedisKey(StringRedisTemplate template, String provider, String eventId) {
        if (template == null) return;
        try {
            String key = keyFactory.webhookEventDedupKey(provider, eventId);
            template.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(EVENT_ID_TTL_SECONDS));
        } catch (Exception ex) {
            log.debug("[WEBHOOK-DEDUP] Could not seed event-id Redis key for {}/{}: {}",
                    provider, eventId, ex.getMessage());
        }
    }
}
