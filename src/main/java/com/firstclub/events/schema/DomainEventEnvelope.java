package com.firstclub.events.schema;

import com.firstclub.events.entity.DomainEvent;

/**
 * Wraps a {@link DomainEvent} with its migration-processed payload and schema metadata.
 *
 * <p>Created by {@link #wrap(DomainEvent, PayloadMigrationRegistry)} before an
 * event is dispatched to a handler or a replay pipeline.  If the stored
 * {@code schema_version} is lower than the current version for the event type,
 * {@link #migratedPayload} holds the up-converted JSON and
 * {@link #wasMigrated} is {@code true}.
 *
 * <p>Consumers must always read from {@link #effectivePayload()} rather than
 * accessing the raw payload on the wrapped event directly.
 *
 * @param event               original stored event (payload may be at an older schema version)
 * @param migratedPayload     payload after all migration steps; {@code null} when no migration was needed
 * @param storedSchemaVersion the {@code schema_version} recorded in the event row
 * @param currentSchemaVersion the version the registry considers current for this event type
 * @param wasMigrated         {@code true} when at least one migration step was applied
 */
public record DomainEventEnvelope(
        DomainEvent event,
        String migratedPayload,
        int storedSchemaVersion,
        int currentSchemaVersion,
        boolean wasMigrated) {

    /**
     * Returns the payload a handler should use: the migrated payload when migration
     * was applied, otherwise the original event payload unchanged.
     */
    public String effectivePayload() {
        return wasMigrated ? migratedPayload : event.getPayload();
    }

    /**
     * Wraps {@code event} and runs the migration pipeline against {@code registry}.
     *
     * @param event    the domain event to wrap
     * @param registry migration registry to use for version resolution and migration
     * @return an envelope carrying migration metadata and (if applicable) the migrated payload
     */
    public static DomainEventEnvelope wrap(DomainEvent event, PayloadMigrationRegistry registry) {
        int stored  = event.getSchemaVersion();
        int current = registry.currentVersion(event.getEventType());

        if (stored >= current) {
            return new DomainEventEnvelope(event, null, stored, current, false);
        }

        String migrated = registry.migrate(event.getPayload(), event.getEventType(), stored);
        return new DomainEventEnvelope(event, migrated, stored, current, true);
    }
}
