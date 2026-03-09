package com.firstclub.outbox.repository;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Fetches the next batch of NEW events whose {@code next_attempt_at} is due.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} so that concurrent scheduler
     * instances (multiple pods) never process the same row simultaneously.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'NEW'
              AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findDueForProcessing(@Param("now") LocalDateTime now,
                                           @Param("limit") int limit);

    List<OutboxEvent> findByStatus(OutboxEventStatus status);

    long countByStatus(OutboxEventStatus status);

    /**
     * Groups active (non-DONE) events by eventType for outbox lag reporting.
     * Each row is [eventType (String), count (Long)].
     */
    @Query("SELECT o.eventType, COUNT(o) FROM OutboxEvent o WHERE o.status != :excludeStatus GROUP BY o.eventType ORDER BY COUNT(o) DESC")
    List<Object[]> countActiveByEventType(@Param("excludeStatus") OutboxEventStatus excludeStatus);

    /**
     * Finds events stuck in PROCESSING state whose lease started before
     * {@code threshold}.  Used by {@code OutboxService.recoverStaleLeases()}.
     */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = :status AND o.processingStartedAt < :threshold")
    List<OutboxEvent> findStaleProcessing(@Param("status") OutboxEventStatus status,
                                          @Param("threshold") LocalDateTime threshold);

    /**
     * Returns the {@code createdAt} of the oldest NEW or PROCESSING event.
     * Used to compute outbox lag age for the ops dashboard.
     */
    @Query("SELECT MIN(o.createdAt) FROM OutboxEvent o WHERE o.status IN :statuses")
    Optional<LocalDateTime> findOldestCreatedAtInStatuses(
            @Param("statuses") List<OutboxEventStatus> statuses);

    // ── Phase 12: starvation-prevention priority queries ───────────────────────

    /** Fresh events (attempts = 0) ordered by creation time — these are always processed first. */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'NEW' AND attempts = 0 AND next_attempt_at <= :now
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findAndLockFreshEvents(@Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    /** Retry events (attempts > 0) ordered by next_attempt_at — processed after fresh events. */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'NEW' AND attempts > 0 AND next_attempt_at <= :now
            ORDER BY next_attempt_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findAndLockRetryEvents(@Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    // ── Phase 12: lease-based recovery queries ─────────────────────────────────

    /** PROCESSING events whose lease has expired (heartbeat stopped). */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.leaseExpiresAt IS NOT NULL AND e.leaseExpiresAt < :now")
    List<OutboxEvent> findByLeaseExpiredBefore(@Param("status") OutboxEventStatus status,
                                               @Param("now") LocalDateTime now);

    /** PROCESSING events without a lease timestamp (pre-Phase 12 rows). */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.leaseExpiresAt IS NULL AND e.processingStartedAt < :threshold")
    List<OutboxEvent> findStaleProcessingWithoutLease(@Param("status") OutboxEventStatus status,
                                                      @Param("threshold") LocalDateTime threshold);

    /** Bulk-extend leases for all PROCESSING events owned by a specific node. */
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.leaseExpiresAt = :newExpiry WHERE e.status = :status AND e.processingOwner = :owner")
    int extendLeases(@Param("status") OutboxEventStatus status,
                     @Param("owner") String owner,
                     @Param("newExpiry") LocalDateTime newExpiry);

    // ── Phase 12: aggregate sequence assignment ────────────────────────────────

    /** Returns the current max aggregate_sequence for an aggregate instance, or empty if none. */
    @Query("SELECT MAX(e.aggregateSequence) FROM OutboxEvent e WHERE e.aggregateType = :aggregateType AND e.aggregateId = :aggregateId")
    Optional<Long> findMaxAggregateSequence(@Param("aggregateType") String aggregateType,
                                            @Param("aggregateId") String aggregateId);
}
