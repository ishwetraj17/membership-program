package com.firstclub.events.replay;

/**
 * Describes the outcome of a single-event replay attempt.
 *
 * @param originalEventId ID of the source event that was (or was attempted to be) replayed
 * @param replayEventId   ID of the newly-persisted replay event; {@code null} when skipped
 * @param eventType       event type string
 * @param replayed        {@code true} when the replay succeeded and a new event was persisted
 * @param wasMigrated     {@code true} when the payload was migrated to a newer schema version
 * @param skipReason      human-readable reason why the event was not replayed; {@code null} when replayed
 */
public record ReplayResult(
        Long    originalEventId,
        Long    replayEventId,
        String  eventType,
        boolean replayed,
        boolean wasMigrated,
        String  skipReason) {

    /** Creates a successful replay result. */
    public static ReplayResult replayed(Long originalId, Long replayId,
                                        String eventType, boolean wasMigrated) {
        return new ReplayResult(originalId, replayId, eventType, true, wasMigrated, null);
    }

    /** Creates a skipped result (event was not replayed). */
    public static ReplayResult skipped(Long originalId, String eventType, String reason) {
        return new ReplayResult(originalId, null, eventType, false, false, reason);
    }
}
