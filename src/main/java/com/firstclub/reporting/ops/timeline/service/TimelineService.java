package com.firstclub.reporting.ops.timeline.service;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.reporting.ops.timeline.cache.TimelineCacheService;
import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import com.firstclub.reporting.ops.timeline.repository.TimelineEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Core timeline service — appends rows from domain events and serves
 * read queries for the timeline API.
 *
 * <h3>Dedup / replay behaviour</h3>
 * Before saving each row produced by {@link TimelineMapper}, the service
 * checks whether a row with the same
 * {@code (source_event_id, entity_type, entity_id)} already exists.
 * If it does, the row is silently skipped.  A unique partial DB index
 * provides a hard guarantee against races; a
 * {@link DataIntegrityViolationException} from a concurrent insert is
 * also caught and logged as a warning (not an error) so the caller is
 * never disrupted.
 *
 * <h3>Caching</h3>
 * Successful saves evict the Redis cache for the affected entity.
 * Query methods populate the cache on DB reads (TTL 60 s).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimelineService {

    private final TimelineEventRepository repo;
    private final TimelineMapper           mapper;
    private final TimelineCacheService     cache;

    // ── Write path ────────────────────────────────────────────────────────────

    /**
     * Map a domain event to timeline rows and persist them — unless a matching
     * row already exists (dedup).  Called asynchronously from
     * {@link com.firstclub.reporting.projections.listener.ProjectionEventListener}.
     */
    @Transactional
    public void appendFromEvent(DomainEvent event) {
        List<TimelineEvent> rows = mapper.map(event);
        if (rows.isEmpty()) return;

        for (TimelineEvent row : rows) {
            try {
                // Code-level dedup check (fast path to avoid constraint roundtrip)
                if (row.getSourceEventId() != null
                        && repo.existsDedup(row.getSourceEventId(), row.getEntityType(), row.getEntityId())) {
                    log.debug("Timeline dedup: skipping event={} entity={}/{} (already present)",
                            row.getSourceEventId(), row.getEntityType(), row.getEntityId());
                    continue;
                }
                repo.save(row);
                cache.evict(row.getMerchantId(), row.getEntityType(), row.getEntityId());
                log.debug("Timeline: appended {} {} for event {} (merchant={})",
                        row.getEntityType(), row.getEntityId(), event.getEventType(), event.getMerchantId());
            } catch (DataIntegrityViolationException ex) {
                // Race condition: two threads tried to insert the same row concurrently.
                // The DB unique index won — treat as a successful dedup.
                log.warn("Timeline concurrent dedup: event={} entity={}/{} — row already inserted by another thread",
                        row.getSourceEventId(), row.getEntityType(), row.getEntityId());
            }
        }
    }

    /**
     * Persist a manually constructed timeline event (no dedup — {@code sourceEventId} is null).
     * Used by repair actions and the support-case service to record ops-driven
     * state changes directly, without going through the domain-event pipeline.
     *
     * <p>The timeline cache for the affected entity is evicted on save.
     */
    @Transactional
    public void appendManual(TimelineEvent event) {
        repo.save(event);
        cache.evict(event.getMerchantId(), event.getEntityType(), event.getEntityId());
        log.debug("Timeline manual: appended {} {} eventType={}",
                event.getEntityType(), event.getEntityId(), event.getEventType());
    }

    // ── Read path ─────────────────────────────────────────────────────────────

    /**
     * Full history for a specific entity, newest first.
     * Results are cached for {@value TimelineCacheService#TTL_SECONDS} seconds.
     */
    public List<TimelineEventDTO> getTimelineForEntity(Long merchantId, String entityType, Long entityId) {
        Optional<List<TimelineEventDTO>> cached = cache.get(merchantId, entityType, entityId);
        if (cached.isPresent()) {
            log.debug("Timeline cache hit: merchant={} entity={}/{}", merchantId, entityType, entityId);
            return cached.get();
        }
        List<TimelineEventDTO> dtos = repo
                .findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(merchantId, entityType, entityId)
                .stream()
                .map(TimelineEventDTO::from)
                .collect(Collectors.toList());
        cache.put(merchantId, entityType, entityId, dtos);
        return dtos;
    }

    /**
     * Paginated history for a specific entity, newest first.
     */
    public Page<TimelineEventDTO> getTimelineForEntityPaged(
            Long merchantId, String entityType, Long entityId, Pageable pageable) {
        return repo.findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(
                merchantId, entityType, entityId, pageable)
                .map(TimelineEventDTO::from);
    }

    /**
     * All timeline events sharing a correlation id — reveals the span of a
     * single user action across multiple entity boundaries.
     */
    public Page<TimelineEventDTO> getTimelineByCorrelation(
            Long merchantId, String correlationId, Pageable pageable) {
        return repo.findByMerchantIdAndCorrelationIdOrderByEventTimeDescIdDesc(
                merchantId, correlationId, pageable)
                .map(TimelineEventDTO::from);
    }
}
