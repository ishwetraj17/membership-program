package com.firstclub.outbox.lease;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recovers outbox events that are stuck in PROCESSING state because their
 * processing node stopped sending heartbeats.
 *
 * <h3>Two recovery strategies</h3>
 *
 * <p><b>Lease-based</b> ({@link #recoverExpiredLeases()}): reclaims PROCESSING
 * events whose {@code lease_expires_at &lt; now}.  These events have a lease set
 * (Phase 12+) but the owning node stopped heartbeating — indicating a crash,
 * forced kill, or prolonged GC pause beyond the lease window.
 *
 * <p><b>Legacy time-based</b> ({@link #recoverLegacyStaleLeases(int)}): reclaims
 * PROCESSING events that have no {@code lease_expires_at} value (created before
 * the V58 migration) and whose {@code processing_started_at} is older than the
 * supplied threshold.
 *
 * <h3>Safety guarantee</h3>
 * Events actively processed by a live node are protected because their
 * {@link OutboxLeaseHeartbeat} renews {@code lease_expires_at} every 60 seconds.
 * Only truly abandoned leases (heartbeat stopped) expire and are reset here.
 * Recovery is therefore <em>safe to run concurrently</em> with active pollers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxLeaseRecoveryService {

    private final OutboxEventRepository repository;

    /**
     * Reclaims PROCESSING events whose {@code lease_expires_at} has passed,
     * resetting them to NEW for the next poll cycle.
     *
     * @return number of events reset to NEW
     */
    @Transactional
    public int recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> expired = repository.findByLeaseExpiredBefore(
                OutboxEventStatus.PROCESSING, now);

        if (expired.isEmpty()) {
            return 0;
        }

        expired.forEach(e -> {
            log.warn("Recovering expired lease: id={}, owner={}, leaseExpiresAt={}, eventType={}",
                    e.getId(), e.getProcessingOwner(), e.getLeaseExpiresAt(), e.getEventType());
            e.setStatus(OutboxEventStatus.NEW);
            e.setProcessingStartedAt(null);
            e.setProcessingOwner(null);
            e.setLeaseExpiresAt(null);
            e.setNextAttemptAt(now);
        });

        repository.saveAll(expired);
        log.info("Lease recovery: reset {} expired-lease event(s) to NEW", expired.size());
        return expired.size();
    }

    /**
     * Backward-compatibility recovery for PROCESSING events that pre-date the
     * lease column (no {@code lease_expires_at} set).
     *
     * @param staleMinutes an event is stale if {@code processing_started_at} is
     *                     older than this many minutes
     * @return number of events reset to NEW
     */
    @Transactional
    public int recoverLegacyStaleLeases(int staleMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleMinutes);
        List<OutboxEvent> stale = repository.findStaleProcessingWithoutLease(
                OutboxEventStatus.PROCESSING, threshold);

        if (stale.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        stale.forEach(e -> {
            log.warn("Recovering legacy stale lease: id={}, owner={}, processingStartedAt={}, eventType={}",
                    e.getId(), e.getProcessingOwner(), e.getProcessingStartedAt(), e.getEventType());
            e.setStatus(OutboxEventStatus.NEW);
            e.setProcessingStartedAt(null);
            e.setProcessingOwner(null);
            e.setNextAttemptAt(now);
        });

        repository.saveAll(stale);
        log.info("Legacy stale-lease recovery: reset {} event(s) to NEW", stale.size());
        return stale.size();
    }

    /**
     * Runs both recovery strategies.  Called by the scheduled poller and the
     * ops admin endpoint.
     *
     * @param legacyThresholdMinutes stale threshold for events without a lease
     * @return total number of events recovered
     */
    @Transactional
    public int recoverAll(int legacyThresholdMinutes) {
        return recoverExpiredLeases() + recoverLegacyStaleLeases(legacyThresholdMinutes);
    }
}
