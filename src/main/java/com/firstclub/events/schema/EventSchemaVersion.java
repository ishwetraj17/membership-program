package com.firstclub.events.schema;

/**
 * Declares the current schema version for a specific event type.
 *
 * <p>Instances of this record are the source of truth for what version
 * {@link PayloadMigrationRegistry} considers "current".  When a stored event's
 * {@code schema_version} is lower than {@link #currentVersion()}, the registry
 * chains the registered {@link PayloadMigrator}s to bring the payload up to date
 * before it reaches a handler.
 *
 * <h3>Version semantics</h3>
 * <ul>
 *   <li><b>event_version</b> – monotonically increasing sequence number for an
 *       individual event instance (concurrency / causality ordering).
 *       It is set once at write time and never changed.</li>
 *   <li><b>schema_version</b> – version of the JSON <em>schema</em> used to
 *       encode the payload.  It increments when the shape of the payload changes
 *       (e.g. a field is added, renamed, or removed).  Old events keep their
 *       original schema_version; migration happens at read time.</li>
 * </ul>
 *
 * @param eventType      canonical event-type string (e.g. {@code "INVOICE_CREATED"})
 * @param currentVersion highest stable schema version; must be ≥ 1
 */
public record EventSchemaVersion(String eventType, int currentVersion) {

    public EventSchemaVersion {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (currentVersion < 1) {
            throw new IllegalArgumentException(
                    "currentVersion must be >= 1, got " + currentVersion);
        }
    }

    /** Factory method for readability: {@code EventSchemaVersion.of("INVOICE_CREATED", 2)}. */
    public static EventSchemaVersion of(String eventType, int version) {
        return new EventSchemaVersion(eventType, version);
    }

    /** Returns {@code true} when {@code storedVersion} is older than {@link #currentVersion()}. */
    public boolean isStale(int storedVersion) {
        return storedVersion < currentVersion;
    }
}
