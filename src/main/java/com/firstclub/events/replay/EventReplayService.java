package com.firstclub.events.replay;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.events.schema.DomainEventEnvelope;
import com.firstclub.events.schema.PayloadMigrationRegistry;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestrates schema-aware, idempotency-guarded replay of domain events.
 *
 * <h3>Replay flow</h3>
 * <ol>
 *   <li>Load the original event from the repository.</li>
 *   <li>Run a {@link ReplaySafetyService} pre-flight check — abort early if
 *       blocked by policy or loop-detection.</li>
 *   <li>Wrap the event in a {@link DomainEventEnvelope} to run the migration
 *       pipeline if the stored {@code schema_version} lags behind.</li>
 *   <li>Append a new {@link DomainEvent} row with {@code replayed=true},
 *       {@code replayedAt=now()}, {@code originalEventId} pointing at the source,
 *       and the migrated payload at the current schema version.</li>
 * </ol>
 *
 * <p>The original event is <em>never modified</em> — the domain event table
 * remains append-only from the perspective of event content.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EventReplayService {

    private final DomainEventRepository    domainEventRepository;
    private final PayloadMigrationRegistry migrationRegistry;
    private final ReplaySafetyService      replaySafetyService;

    // ── Single-event replay ───────────────────────────────────────────────────

    /**
     * Replays a single domain event by its primary key.
     *
     * @param eventId primary key of the original event
     * @return result indicating whether the event was replayed or skipped
     * @throws EntityNotFoundException if no event exists with {@code eventId}
     */
    public ReplayResult replay(Long eventId) {
        DomainEvent original = domainEventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "DomainEvent not found: " + eventId));
        return replayEvent(original);
    }

    // ── Range replay ─────────────────────────────────────────────────────────

    /**
     * Replays all events matching the time window and optional filters in
     * {@code request}.
     *
     * <p>Events blocked by safety checks are included in the result with
     * {@code replayed=false} and a {@code skipReason}; they do not abort the
     * rest of the range.
     *
     * @return one {@link ReplayResult} per matched event, in {@code createdAt DESC} order
     */
    public List<ReplayResult> replayRange(ReplayRangeRequest request) {
        List<DomainEvent> events = domainEventRepository.findWithFilters(
                request.merchantId(),
                request.eventType(),
                null,
                null,
                request.from(),
                request.to(),
                Pageable.unpaged()
        ).getContent();

        log.info("ReplayRange: {} candidate event(s) from={} to={} eventType={} merchantId={}",
                events.size(), request.from(), request.to(),
                request.eventType(), request.merchantId());

        List<ReplayResult> results = events.stream()
                .map(this::replayEvent)
                .toList();

        long replayed = results.stream().filter(ReplayResult::replayed).count();
        log.info("ReplayRange complete: {}/{} event(s) replayed", replayed, results.size());
        return results;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private ReplayResult replayEvent(DomainEvent original) {
        ReplaySafetyService.ReplaySafetyCheckResult safety =
                replaySafetyService.checkCanReplay(original);

        if (!safety.allowed()) {
            log.info("Replay skipped: eventId={} reason={}", original.getId(), safety.reason());
            return ReplayResult.skipped(original.getId(), original.getEventType(), safety.reason());
        }

        DomainEventEnvelope envelope = DomainEventEnvelope.wrap(original, migrationRegistry);
        LocalDateTime now = LocalDateTime.now();

        DomainEvent replayEvent = DomainEvent.builder()
                .eventType(original.getEventType())
                .payload(envelope.effectivePayload())
                .schemaVersion(envelope.currentSchemaVersion())
                .eventVersion(original.getEventVersion())
                .correlationId(original.getCorrelationId())
                .causationId(String.valueOf(original.getId()))
                .aggregateType(original.getAggregateType())
                .aggregateId(original.getAggregateId())
                .merchantId(original.getMerchantId())
                .replayed(true)
                .replayedAt(now)
                .originalEventId(original.getId())
                .build();

        DomainEvent saved = domainEventRepository.save(replayEvent);

        log.info("Replayed: originalEventId={} replayEventId={} eventType={} wasMigrated={}",
                original.getId(), saved.getId(), original.getEventType(), envelope.wasMigrated());

        return ReplayResult.replayed(
                original.getId(), saved.getId(),
                original.getEventType(), envelope.wasMigrated());
    }
}
