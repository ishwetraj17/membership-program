package com.firstclub.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.lease.OutboxLeaseHeartbeat;
import com.firstclub.outbox.lease.OutboxLeaseRecoveryService;
import com.firstclub.outbox.ordering.OutboxPrioritySelector;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.entity.DeadLetterMessage;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Core outbox service.
 *
 * <h3>Publisher side</h3>
 * {@link #publish(String, Object)} serialize a domain event into the
 * {@code outbox_events} table.  It is intended to be called inside the
 * <em>same</em> database transaction as the originating business change, so it
 * uses {@code propagation = REQUIRED} to join the caller's transaction.
 *
 * <h3>Poller side</h3>
 * {@link #lockDueEvents(int)} grabs the next batch of NEW events, marks them
 * PROCESSING and returns their IDs (all in one transaction).
 *
 * {@link #processSingleEvent(Long)} runs each event in its own
 * {@code REQUIRES_NEW} transaction so failures are fully isolated.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    /** Maximum delivery attempts before an event goes to the dead-letter table. */
    public static final int MAX_ATTEMPTS = 5;

    /**
     * Retry back-off intervals (minutes) indexed by zero-based attempts count.
     * Entry [i] is used after the (i+1)-th failure.
     * The list has 4 entries covering attempts 1–4; the 5th attempt is terminal.
     */
    static final List<Long> BACKOFF_MINUTES = List.of(5L, 15L, 30L, 60L);

    /** Stale-lease threshold in minutes — events processing longer than this are recovered. */
    public static final int STALE_LEASE_MINUTES = 5;

    private final OutboxEventRepository      outboxEventRepository;
    private final DeadLetterMessageRepository deadLetterRepository;
    private final OutboxEventHandlerRegistry  handlerRegistry;
    private final ObjectMapper               objectMapper;
    private final MeterRegistry              meterRegistry;
    private final OutboxPrioritySelector     prioritySelector;
    private final OutboxLeaseRecoveryService leaseRecoveryService;

    private Counter outboxFailedCounter;

    @PostConstruct
    public void init() {
        outboxFailedCounter = meterRegistry.counter("outbox_failed_total");
    }

    // -------------------------------------------------------------------------
    // Publisher
    // -------------------------------------------------------------------------

    /**
     * Writes a domain event to the outbox.  Must be called inside a running
     * transaction (i.e., from a {@code @Transactional} service method) so the
     * outbox write and the business change are committed atomically.
     *
     * @param eventType a {@link com.firstclub.outbox.config.DomainEventTypes} constant
     * @param payload   any object; will be serialized to JSON
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void publish(String eventType, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot serialize outbox payload for event " + eventType, e);
        }

        outboxEventRepository.save(OutboxEvent.builder()
                .eventType(eventType)
                .payload(json)
                .status(OutboxEventStatus.NEW)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now())
                .build());

        log.debug("Outbox event published: type={}", eventType);
    }

    // -------------------------------------------------------------------------
    // Poller — step 1: lock a batch
    // -------------------------------------------------------------------------

    /**
     * Atomically marks the next {@code limit} due events as PROCESSING and
     * returns their IDs.  Uses {@code FOR UPDATE SKIP LOCKED} to be
     * safe under concurrent polling.
     *
     * <p>Phase 16: stamps {@code processingStartedAt} and {@code processingOwner}
     * on every claimed event for lease visibility and stale-lease recovery.
     */
    @Transactional
    public List<Long> lockDueEvents(int limit) {
        List<OutboxEvent> events = prioritySelector.selectAndLockBatch(limit);

        if (events.isEmpty()) {
            return List.of();
        }

        String owner = OutboxLeaseHeartbeat.NODE_ID;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpiry = now.plusMinutes(OutboxLeaseHeartbeat.LEASE_DURATION_MINUTES);
        events.forEach(e -> {
            e.setStatus(OutboxEventStatus.PROCESSING);
            e.setProcessingStartedAt(now);
            e.setProcessingOwner(owner);
            e.setLeaseExpiresAt(leaseExpiry);
        });
        outboxEventRepository.saveAll(events);

        log.debug("Locked {} outbox event(s) for processing (owner={})", events.size(), owner);
        return events.stream().map(OutboxEvent::getId).toList();
    }

    // -------------------------------------------------------------------------
    // Poller — step 2: process one event (in its own transaction)
    // -------------------------------------------------------------------------

    /**
     * Dispatches a single event to its handler inside a fresh transaction.
     *
     * <ul>
     *   <li>On success: status → DONE.</li>
     *   <li>On failure: attempts++ and status → NEW with exponential back-off,
     *       unless attempts has reached {@link #MAX_ATTEMPTS}, in which case
     *       status → FAILED and the payload is copied to {@code dead_letter_messages}
     *       with {@code source = "OUTBOX"}.</li>
     *   <li>Unknown event type: logged as a warning and the event is marked
     *       DONE to prevent infinite retries.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId).orElse(null);
        if (event == null) {
            log.warn("Outbox event {} not found — skipping", eventId);
            return;
        }

        Optional<OutboxEventHandler> handlerOpt = handlerRegistry.resolve(event.getEventType());
        if (handlerOpt.isEmpty()) {
            log.warn("No handler registered for outbox event type '{}' (id={}) — marking DONE",
                    event.getEventType(), eventId);
            event.setStatus(OutboxEventStatus.DONE);
            event.setFailureCategory("HANDLER_NOT_FOUND");
            outboxEventRepository.save(event);
            return;
        }

        try {
            handlerOpt.get().handle(event);
            event.setStatus(OutboxEventStatus.DONE);
            event.setLastError(null);
            event.setProcessingStartedAt(null);
            event.setProcessingOwner(null);
            event.setLeaseExpiresAt(null);
            log.info("Outbox event {} ({}) processed successfully", eventId, event.getEventType());
        } catch (Exception ex) {
            int newAttempts = event.getAttempts() + 1;
            event.setAttempts(newAttempts);
            event.setLastError(ex.getMessage());
            event.setFailureCategory(categorizeFailure(ex));

            if (newAttempts >= MAX_ATTEMPTS) {
                event.setStatus(OutboxEventStatus.FAILED);
                log.error("Outbox event {} ({}) permanently failed after {} attempts — routing to DLQ",
                        eventId, event.getEventType(), newAttempts, ex);
                writeToDLQ(event);
                outboxFailedCounter.increment();
            } else {
                long backoffMinutes = BACKOFF_MINUTES.get(
                        Math.min(newAttempts - 1, BACKOFF_MINUTES.size() - 1));
                event.setStatus(OutboxEventStatus.NEW);
                event.setProcessingStartedAt(null);
                event.setProcessingOwner(null);
                event.setLeaseExpiresAt(null);
                event.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMinutes));
                log.warn("Outbox event {} ({}) failed (attempt {}/{}), retry in {} min",
                        eventId, event.getEventType(), newAttempts, MAX_ATTEMPTS, backoffMinutes, ex);
            }
        }

        outboxEventRepository.save(event);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Delegates stale-lease recovery to {@link OutboxLeaseRecoveryService},
     * which handles both lease-based and legacy time-based recovery.
     *
     * @return total number of events reset to NEW
     */
    @Transactional
    public int recoverStaleLeases() {
        return leaseRecoveryService.recoverAll(STALE_LEASE_MINUTES);
    }

    /**
     * Resets an outbox event to NEW immediately, regardless of its current status.
     * Used by the ops API to manually requeue stuck or failed events.
     *
     * @param eventId the primary key of the event to requeue
     * @throws IllegalArgumentException if no event with the given ID exists
     */
    @Transactional
    public void requeueEvent(Long eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + eventId));
        event.setStatus(OutboxEventStatus.NEW);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setProcessingStartedAt(null);
        event.setProcessingOwner(null);
        event.setLeaseExpiresAt(null);
        outboxEventRepository.save(event);
        log.info("Outbox event {} requeued → NEW", eventId);
    }

    private void writeToDLQ(OutboxEvent event) {
        deadLetterRepository.save(DeadLetterMessage.builder()
                .source("OUTBOX")
                .payload(event.getEventType() + "|" + event.getPayload())
                .error(event.getLastError() != null ? event.getLastError() : "unknown")
                .failureCategory(event.getFailureCategory())
                .merchantId(event.getMerchantId())
                .build());
    }

    /**
     * Classifies an exception into a coarse failure category for ops triage.
     * The category is stored on the outbox event row and copied to the DLQ record.
     */
    public static String categorizeFailure(Exception ex) {
        if (ex instanceof com.fasterxml.jackson.core.JacksonException) {
            return "PAYLOAD_PARSE_ERROR";
        }
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        if (msg.contains("duplicate") || msg.contains("idempotent") || msg.contains("already_posted")) {
            return "DEDUP_DUPLICATE";
        }
        if (msg.contains("ledger") || msg.contains("accounting") || msg.contains("journal")) {
            return "ACCOUNTING_ERROR";
        }
        if (msg.contains("balance") || msg.contains("over_refund") || msg.contains("exceed")) {
            return "BUSINESS_RULE_VIOLATION";
        }
        if (msg.contains("connection") || msg.contains("timeout")
                || msg.contains("network") || msg.contains("unavailable")) {
            return "TRANSIENT_ERROR";
        }
        return "UNKNOWN";
    }

    /**
     * Stable processing-owner string: {@code hostname:pid}.
     * If hostname resolution fails, falls back to {@code "unknown:pid"}.
     */
    private static String processingOwner() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown";
        }
        return host + ":" + ProcessHandle.current().pid();
    }
}
