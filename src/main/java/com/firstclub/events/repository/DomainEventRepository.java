package com.firstclub.events.repository;

import com.firstclub.events.entity.DomainEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DomainEventRepository extends JpaRepository<DomainEvent, Long> {

    List<DomainEvent> findByCreatedAtBetweenOrderByCreatedAtAsc(
            LocalDateTime from, LocalDateTime to);

    List<DomainEvent> findByEventTypeAndCreatedAtBetween(
            String eventType, LocalDateTime from, LocalDateTime to);

    // ── V29 — filtered queries ─────────────────────────────────────────────

    List<DomainEvent> findByMerchantIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            Long merchantId, LocalDateTime from, LocalDateTime to);

    List<DomainEvent> findByAggregateTypeAndAggregateIdAndCreatedAtBetweenOrderByCreatedAtAsc(
            String aggregateType, String aggregateId, LocalDateTime from, LocalDateTime to);

    List<DomainEvent> findByAggregateTypeAndCreatedAtBetweenOrderByCreatedAtAsc(
            String aggregateType, LocalDateTime from, LocalDateTime to);

    // ── V29 — flexible admin query with optional filters ─────────────────────

    @Query(value = """
            SELECT * FROM domain_events e
            WHERE (CAST(:merchantId AS BIGINT)      IS NULL OR e.merchant_id    = CAST(:merchantId AS BIGINT))
              AND (CAST(:eventType AS VARCHAR)       IS NULL OR e.event_type     = CAST(:eventType AS VARCHAR))
              AND (CAST(:aggregateType AS VARCHAR)   IS NULL OR e.aggregate_type = CAST(:aggregateType AS VARCHAR))
              AND (CAST(:aggregateId AS VARCHAR)     IS NULL OR e.aggregate_id   = CAST(:aggregateId AS VARCHAR))
              AND (CAST(:from AS TIMESTAMP)          IS NULL OR e.created_at    >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP)            IS NULL OR e.created_at    <= CAST(:to AS TIMESTAMP))
            ORDER BY e.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM domain_events e
            WHERE (CAST(:merchantId AS BIGINT)      IS NULL OR e.merchant_id    = CAST(:merchantId AS BIGINT))
              AND (CAST(:eventType AS VARCHAR)       IS NULL OR e.event_type     = CAST(:eventType AS VARCHAR))
              AND (CAST(:aggregateType AS VARCHAR)   IS NULL OR e.aggregate_type = CAST(:aggregateType AS VARCHAR))
              AND (CAST(:aggregateId AS VARCHAR)     IS NULL OR e.aggregate_id   = CAST(:aggregateId AS VARCHAR))
              AND (CAST(:from AS TIMESTAMP)          IS NULL OR e.created_at    >= CAST(:from AS TIMESTAMP))
              AND (CAST(:to AS TIMESTAMP)            IS NULL OR e.created_at    <= CAST(:to AS TIMESTAMP))
            """,
            nativeQuery = true)
    Page<DomainEvent> findWithFilters(
            @Param("merchantId")    Long merchantId,
            @Param("eventType")     String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId")   String aggregateId,
            @Param("from")          LocalDateTime from,
            @Param("to")            LocalDateTime to,
            Pageable pageable);

    // ── Phase 13: admin search ────────────────────────────────────────────

    /** All events sharing a correlation ID for a merchant — tenant-safe causal chain lookup. */
    List<DomainEvent> findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(
            String correlationId, Long merchantId);

    /** Tenant-safe single-event lookup by primary key — prevents cross-merchant leakage. */
    @Query("SELECT e FROM DomainEvent e WHERE e.id = :id AND e.merchantId = :merchantId")
    java.util.Optional<DomainEvent> findByIdAndMerchantId(
            @Param("id") Long id, @Param("merchantId") Long merchantId);

    // ── Phase 13: replay tracking ─────────────────────────────────────────────

    /** Returns {@code true} when at least one replay event exists for {@code originalEventId}. */
    boolean existsByOriginalEventId(Long originalEventId);

    /** Returns all replay events created from {@code originalEventId}, ordered by creation time. */
    List<DomainEvent> findByOriginalEventIdOrderByCreatedAtAsc(Long originalEventId);

    /** Lists all events that are themselves replays, ordered newest-first. */
    List<DomainEvent> findByReplayedTrueOrderByCreatedAtDesc();
}
