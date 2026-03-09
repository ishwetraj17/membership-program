package com.firstclub.platform.dedup;

import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Two-tier deduplication service for critical business effects.
 *
 * <h3>Tier 1 — Redis (fast path)</h3>
 * {@code SET NX EX ttl} on the key {@code {env}:firstclub:dedup:biz:{effectType}:{fingerprint}}.
 * When Redis is available this short-circuits the DB check in under 1 ms.
 * If Redis is unavailable the check falls through to Tier 2 without error.
 *
 * <h3>Tier 2 — PostgreSQL (durable)</h3>
 * {@code INSERT INTO business_effect_fingerprints (effect_type, fingerprint, …) ON CONFLICT DO NOTHING}.
 * The UNIQUE constraint {@code uq_effect_fingerprint (effect_type, fingerprint)} is the
 * ultimate guard: even if two JVM instances bypass the Redis check simultaneously, only
 * one INSERT will succeed.
 *
 * <h3>Usage pattern</h3>
 * <pre>{@code
 * DedupResult result = dedupService.checkAndRecord(
 *     BusinessEffectType.PAYMENT_CAPTURE_SUCCESS, fingerprint,
 *     "PAYMENT", paymentId);
 * if (result == DedupResult.DUPLICATE) {
 *     log.info("Duplicate payment capture — skipping effect");
 *     return;
 * }
 * // apply the effect …
 * }</pre>
 *
 * <p><b>Important:</b> call {@link #checkAndRecord} <em>before</em> the heavy
 * business logic, not after.  The INSERT is done inside a {@code REQUIRES_NEW}
 * transaction so the fingerprint is committed even if the caller's outer
 * transaction rolls back — preventing a window where Redis holds the marker but
 * the DB row is absent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessEffectDedupService {

    /**
     * Redis TTL for business-effect dedup markers.
     * 86400 s (24 h) covers gateway retry windows and same-day reconciliation re-runs.
     */
    public static final int TTL_SECONDS = 86_400;

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;
    private final BusinessEffectFingerprintRepository repository;

    // ── Primary API ───────────────────────────────────────────────────────

    /**
     * Atomically checks whether a business effect fingerprint is new and, if so,
     * records it in both Redis and the DB.
     *
     * <p>This method uses {@code PROPAGATION.REQUIRES_NEW} for the DB INSERT so
     * the fingerprint row is committed independently of the caller's transaction.
     * This prevents a scenario where the outer TX rolls back but the Redis marker
     * remains, causing subsequent retries to be incorrectly flagged as duplicates.
     *
     * @param effectType    one of the constants in {@link BusinessEffectType}
     * @param fingerprint   SHA-256 hex from {@link BusinessFingerprintService}
     * @param referenceType human-readable entity type label (e.g. "PAYMENT")
     * @param referenceId   DB primary key of the related entity
     * @return {@link DedupResult#DUPLICATE} if already applied, {@link DedupResult#NEW} otherwise
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DedupResult checkAndRecord(String effectType,
                                      String fingerprint,
                                      String referenceType,
                                      Long referenceId) {
        // ── Tier 1: Redis fast path ────────────────────────────────────────
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template != null) {
            try {
                String key = keyFactory.bizEffectDedupKey(effectType, fingerprint);
                Boolean isNew = template.opsForValue()
                        .setIfAbsent(key, "1", Duration.ofSeconds(TTL_SECONDS));
                if (Boolean.FALSE.equals(isNew)) {
                    log.info("[DEDUP] Redis HIT — duplicate {} effect (fp={})", effectType, abbreviate(fingerprint));
                    return DedupResult.DUPLICATE;
                }
            } catch (Exception ex) {
                log.warn("[DEDUP] Redis check failed for {} — falling through to DB tier: {}",
                        effectType, ex.getMessage());
            }
        }

        // ── Tier 2: DB durable path ───────────────────────────────────────
        try {
            if (repository.existsByEffectTypeAndFingerprint(effectType, fingerprint)) {
                log.info("[DEDUP] DB HIT — duplicate {} effect (fp={})", effectType, abbreviate(fingerprint));
                return DedupResult.DUPLICATE;
            }

            repository.save(BusinessEffectFingerprint.builder()
                    .effectType(effectType)
                    .fingerprint(fingerprint)
                    .referenceType(referenceType)
                    .referenceId(referenceId)
                    .build());

            log.debug("[DEDUP] Recorded new {} effect (fp={}, ref={}:{})",
                    effectType, abbreviate(fingerprint), referenceType, referenceId);
            return DedupResult.NEW;

        } catch (DataIntegrityViolationException ex) {
            // Concurrent INSERT race — another instance won the race
            log.info("[DEDUP] Concurrent DB INSERT race — duplicate {} effect (fp={})",
                    effectType, abbreviate(fingerprint));
            return DedupResult.DUPLICATE;
        }
    }

    /**
     * Checks without recording — used for read-only admin/diagnostic queries.
     */
    public boolean isKnownDuplicate(String effectType, String fingerprint) {
        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template != null) {
            try {
                String key = keyFactory.bizEffectDedupKey(effectType, fingerprint);
                Boolean exists = template.hasKey(key);
                if (Boolean.TRUE.equals(exists)) {
                    return true;
                }
            } catch (Exception ex) {
                log.warn("[DEDUP] Redis hasKey failed for {} — DB fallback: {}", effectType, ex.getMessage());
            }
        }
        return repository.existsByEffectTypeAndFingerprint(effectType, fingerprint);
    }

    private static String abbreviate(String fingerprint) {
        return fingerprint == null ? "null"
                : (fingerprint.length() > 16 ? fingerprint.substring(0, 16) + "…" : fingerprint);
    }
}
