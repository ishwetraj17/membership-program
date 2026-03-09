package com.firstclub.payments.repository;

import com.firstclub.payments.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByEventId(String eventId);

    /**
     * Returns webhook events that are eligible for a retry attempt:
     * - not yet processed
     * - signature was valid (invalid-signature events are never retried)
     * - backoff window has elapsed
     * - below the maximum retry cap
     */
    @Query("""
            SELECT e FROM WebhookEvent e
            WHERE e.processed       = false
              AND e.signatureValid  = true
              AND e.nextAttemptAt  <= :now
              AND e.attempts        < :maxAttempts
            ORDER BY e.nextAttemptAt ASC
            """)
    List<WebhookEvent> findEligibleForRetry(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts
    );

    long countByProcessedFalse();

    // ── Phase 11: integrity-check queries ────────────────────────────────────
    /**
     * Webhook events that are permanently stuck: signature is valid, not yet
     * processed, and have exhausted the maximum retry budget.
     * These events may represent unprocessed business signals (potential ledger gaps).
     */
    @Query("SELECT w FROM WebhookEvent w WHERE w.processed = false AND w.signatureValid = true AND w.attempts >= :maxAttempts")
    List<WebhookEvent> findStuckWebhooks(@Param("maxAttempts") int maxAttempts);
}
