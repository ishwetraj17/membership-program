package com.firstclub.events.replay;

/**
 * Controls whether a specific domain event type is eligible for schema-versioned replay.
 *
 * <p>Policies are looked up per event type by {@link ReplaySafetyService}.
 * The default for any event type not explicitly registered is {@link #ALLOW}.
 *
 * <ul>
 *   <li>{@link #ALLOW} – freely replayable with no restrictions.</li>
 *   <li>{@link #IDEMPOTENT_ONLY} – replayable at most once.  If a replay event
 *       already exists whose {@code original_event_id} matches this event's {@code id},
 *       the attempt is blocked.</li>
 *   <li>{@link #BLOCKED} – never replay.  Reserved for event types that trigger
 *       irreversible external side-effects (e.g. refunds, payment captures, or
 *       notification delivery).  Replaying these would cause real-world harm.</li>
 * </ul>
 */
public enum ReplayPolicy {

    /** No restrictions. The event may be replayed any number of times. */
    ALLOW,

    /**
     * Replayable at most once.
     * {@link ReplaySafetyService} blocks a second replay by checking for an
     * existing replay event with a matching {@code original_event_id}.
     */
    IDEMPOTENT_ONLY,

    /**
     * Never replay.
     * Used for event types with permanent, irreversible external side-effects
     * such as {@code REFUND_ISSUED} or direct notification delivery.
     */
    BLOCKED
}
