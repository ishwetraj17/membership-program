package com.firstclub.events.replay;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Guards the replay pipeline to prevent duplicated or dangerous re-processing.
 *
 * <h3>Safety rules (applied in order)</h3>
 * <ol>
 *   <li><b>No replay of replays</b> – an event that is itself a replay
 *       ({@code replayed=true}) can never be the source for another replay.
 *       This prevents unbounded chains.</li>
 *   <li><b>BLOCKED policy</b> – event types with permanent, irreversible external
 *       side-effects (e.g. {@code REFUND_ISSUED}) are never replayed.</li>
 *   <li><b>IDEMPOTENT_ONLY policy</b> – replayable at most once.  If a replay
 *       event already exists for the original event ID, the check fails.</li>
 *   <li><b>ALLOW policy</b> (default) – no restrictions.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplaySafetyService {

    /**
     * Built-in per-type replay policies.
     * Event types absent from this map default to {@link ReplayPolicy#ALLOW}.
     */
    static final Map<String, ReplayPolicy> POLICY_MAP = Map.of(
            "REFUND_ISSUED",           ReplayPolicy.BLOCKED,
            "PAYMENT_SUCCEEDED",       ReplayPolicy.IDEMPOTENT_ONLY,
            "INVOICE_CREATED",         ReplayPolicy.IDEMPOTENT_ONLY,
            "PAYMENT_INTENT_CREATED",  ReplayPolicy.IDEMPOTENT_ONLY,
            "SUBSCRIPTION_ACTIVATED",  ReplayPolicy.ALLOW,
            "SUBSCRIPTION_CREATED",    ReplayPolicy.ALLOW,
            "SUBSCRIPTION_CANCELLED",  ReplayPolicy.ALLOW,
            "PAYMENT_ATTEMPT_FAILED",  ReplayPolicy.ALLOW
    );

    private final DomainEventRepository domainEventRepository;

    // ── Result ───────────────────────────────────────────────────────────────

    /** Outcome of a pre-flight replay safety check. */
    public record ReplaySafetyCheckResult(boolean allowed, String reason) {

        public static ReplaySafetyCheckResult ok() {
            return new ReplaySafetyCheckResult(true, null);
        }

        public static ReplaySafetyCheckResult blocked(String reason) {
            return new ReplaySafetyCheckResult(false, reason);
        }
    }

    // ── API ──────────────────────────────────────────────────────────────────

    /**
     * Returns the {@link ReplayPolicy} that applies to {@code eventType}.
     * Returns {@link ReplayPolicy#ALLOW} for any unregistered event type.
     */
    public ReplayPolicy policyFor(String eventType) {
        return POLICY_MAP.getOrDefault(eventType, ReplayPolicy.ALLOW);
    }

    /**
     * Checks whether {@code event} can safely be replayed.
     *
     * @return {@link ReplaySafetyCheckResult#allowed()} if replay may proceed,
     *         or a blocked result with the reason string
     */
    public ReplaySafetyCheckResult checkCanReplay(DomainEvent event) {
        // Rule 1: never replay a replay — infinite-loop prevention
        if (event.isReplayed()) {
            return ReplaySafetyCheckResult.blocked(
                    "Source event " + event.getId()
                    + " is itself a replay — replay chains are not allowed");
        }

        ReplayPolicy policy = policyFor(event.getEventType());

        // Rule 2: BLOCKED policy
        if (policy == ReplayPolicy.BLOCKED) {
            return ReplaySafetyCheckResult.blocked(
                    "Event type '" + event.getEventType() + "' has a BLOCKED replay policy");
        }

        // Rule 3: IDEMPOTENT_ONLY — allow only when not already replayed
        if (policy == ReplayPolicy.IDEMPOTENT_ONLY
                && domainEventRepository.existsByOriginalEventId(event.getId())) {
            return ReplaySafetyCheckResult.blocked(
                    "Event " + event.getId()
                    + " has already been replayed (IDEMPOTENT_ONLY policy prevents duplicates)");
        }

        log.debug("Replay safety check PASSED: eventId={} eventType={} policy={}",
                event.getId(), event.getEventType(), policy);
        return ReplaySafetyCheckResult.ok();
    }
}
