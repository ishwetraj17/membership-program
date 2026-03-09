package com.firstclub.reporting.ops.timeline.repository;

import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, Long> {

    /**
     * Sorted history for a specific entity.  Ordered newest-first with id
     * as the tiebreaker (stable when two events share the same millisecond).
     */
    List<TimelineEvent> findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(
            Long merchantId, String entityType, Long entityId);

    /**
     * Paginated history for a specific entity.
     */
    Page<TimelineEvent> findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(
            Long merchantId, String entityType, Long entityId, Pageable pageable);

    /**
     * All events sharing a correlation id — used to trace a single user
     * action across entity boundaries.
     */
    Page<TimelineEvent> findByMerchantIdAndCorrelationIdOrderByEventTimeDescIdDesc(
            Long merchantId, String correlationId, Pageable pageable);

    /**
     * Dedup check: returns {@code true} if a row already exists for this
     * source event + entity combination.  Used to guarantee idempotent replay.
     */
    @Query("""
            SELECT COUNT(t) > 0
            FROM TimelineEvent t
            WHERE t.sourceEventId = :sourceEventId
              AND t.entityType    = :entityType
              AND t.entityId      = :entityId
            """)
    boolean existsDedup(
            @Param("sourceEventId") Long sourceEventId,
            @Param("entityType")    String entityType,
            @Param("entityId")      Long entityId);
}
