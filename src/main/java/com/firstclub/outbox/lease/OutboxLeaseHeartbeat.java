package com.firstclub.outbox.lease;

import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Periodic heartbeat that renews the {@code lease_expires_at} timestamp for
 * every outbox event currently being processed by this JVM instance.
 *
 * <h3>Why heartbeats are necessary</h3>
 * When an event handler invokes an external API or runs long business logic, its
 * wall-clock duration can exceed the initial lease window.  Without heartbeats,
 * the {@link OutboxLeaseRecoveryService} would incorrectly classify the still-
 * active event as stale and reset it to NEW — causing a duplicate delivery.
 *
 * <p>The heartbeat keeps the lease fresh while the handler is alive.  Only
 * genuinely abandoned leases (e.g. JVM crash, hard kill) expire and are
 * subsequently recovered.
 *
 * <h3>Node identity</h3>
 * {@link #NODE_ID} is computed once at class-load time using
 * {@code hostname:pid}.  It is stable for the lifetime of the JVM but changes on
 * restart, ensuring a dead node's events are never re-owned by a new instance.
 */
@Component
@Slf4j
public class OutboxLeaseHeartbeat {

    /** Duration of each lease grant, in minutes. */
    public static final int LEASE_DURATION_MINUTES = 5;

    /**
     * Stable identifier for this JVM process used as the {@code processing_owner}
     * value on all events claimed by this node.
     */
    public static final String NODE_ID = computeNodeId();

    private final OutboxEventRepository repository;

    public OutboxLeaseHeartbeat(OutboxEventRepository repository) {
        this.repository = repository;
        log.info("OutboxLeaseHeartbeat started — nodeId={}, leaseDuration={}m",
                NODE_ID, LEASE_DURATION_MINUTES);
    }

    /**
     * Runs every 60 seconds (after an initial 30-second delay to avoid clashing
     * with application startup).  Extends {@code lease_expires_at} for all
     * PROCESSING events owned by this node, keeping active leases alive.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    @Transactional
    public void heartbeat() {
        LocalDateTime newExpiry = LocalDateTime.now().plusMinutes(LEASE_DURATION_MINUTES);
        int extended = repository.extendLeases(OutboxEventStatus.PROCESSING, NODE_ID, newExpiry);
        if (extended > 0) {
            log.debug("LeaseHeartbeat: extended {} lease(s) for node={}, newExpiry={}",
                    extended, NODE_ID, newExpiry);
        }
    }

    private static String computeNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid();
    }
}
