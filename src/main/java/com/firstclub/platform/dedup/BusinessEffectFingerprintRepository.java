package com.firstclub.platform.dedup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link BusinessEffectFingerprint} — the durable tier of the
 * two-tier deduplication strategy for critical business effects.
 */
public interface BusinessEffectFingerprintRepository
        extends JpaRepository<BusinessEffectFingerprint, Long> {

    /**
     * Returns a fingerprint record if the effect has already been applied.
     * Used as the DB-tier fallback when Redis is unavailable.
     */
    Optional<BusinessEffectFingerprint> findByEffectTypeAndFingerprint(
            String effectType, String fingerprint);

    /** Whether a specific effect has already been applied. */
    boolean existsByEffectTypeAndFingerprint(String effectType, String fingerprint);

    /**
     * Recent fingerprints for an effect type, ordered newest-first.
     * Used by the admin endpoint for observability.
     */
    @Query("""
            SELECT f FROM BusinessEffectFingerprint f
            WHERE f.effectType = :effectType
              AND (:since IS NULL OR f.createdAt >= :since)
            ORDER BY f.createdAt DESC
            """)
    List<BusinessEffectFingerprint> findRecentByEffectType(
            @Param("effectType") String effectType,
            @Param("since") LocalDateTime since);

    /**
     * Recent fingerprints across all effect types, ordered newest-first.
     * Used by the admin overview endpoint.
     */
    @Query("""
            SELECT f FROM BusinessEffectFingerprint f
            WHERE (:since IS NULL OR f.createdAt >= :since)
            ORDER BY f.createdAt DESC
            """)
    List<BusinessEffectFingerprint> findRecent(@Param("since") LocalDateTime since);

    /**
     * Count fingerprints for a specific effect type.
     * Used for metrics/diagnostics.
     */
    long countByEffectType(String effectType);
}
