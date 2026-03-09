package com.firstclub.outbox.schema;

import com.firstclub.events.schema.PayloadMigrationRegistry;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.handler.OutboxEventHandler;

/**
 * Base class for outbox event handlers that support schema versioning.
 *
 * <p>Subclasses declare their expected schema version by overriding
 * {@link #currentSchemaVersion()} (defaults to {@code 1}).  When an outbox event
 * arrives with an older {@code schema_version}, this class transparently migrates
 * the payload to the expected version before invoking
 * {@link #handleMigrated(OutboxEvent, String, int, int)}.
 *
 * <p>This decouples event consumers from the storage format of older events,
 * allowing payload schemas to evolve without requiring bulk re-writes of stored data.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * @Component
 * public class InvoiceCreatedV2Handler extends SchemaAwareOutboxEventHandler {
 *
 *     public InvoiceCreatedV2Handler(PayloadMigrationRegistry registry) {
 *         super(registry);
 *     }
 *
 *     @Override public String getEventType()      { return "INVOICE_CREATED"; }
 *     @Override public int    currentSchemaVersion() { return 2; }
 *
 *     @Override
 *     protected void handleMigrated(OutboxEvent event, String payload,
 *                                   int fromVersion, int toVersion) throws Exception {
 *         // payload is guaranteed to be at schema version 2
 *         InvoiceCreatedV2Dto dto = objectMapper.readValue(payload, InvoiceCreatedV2Dto.class);
 *         // ... business logic ...
 *     }
 * }
 * }</pre>
 */
public abstract class SchemaAwareOutboxEventHandler implements OutboxEventHandler {

    private final PayloadMigrationRegistry migrationRegistry;

    protected SchemaAwareOutboxEventHandler(PayloadMigrationRegistry migrationRegistry) {
        this.migrationRegistry = migrationRegistry;
    }

    /**
     * The schema version this handler's business logic expects.
     * Override to declare support for a version higher than {@code 1}.
     */
    public int currentSchemaVersion() {
        return 1;
    }

    /**
     * Routes the event through the migration pipeline then delegates to
     * {@link #handleMigrated}.
     *
     * <p>Do not override this method — override {@link #handleMigrated} instead.
     */
    @Override
    public final void handle(OutboxEvent event) throws Exception {
        int stored  = event.getSchemaVersion();
        int current = currentSchemaVersion();

        String payload = (stored < current)
                ? migrationRegistry.migrate(event.getPayload(), event.getEventType(), stored)
                : event.getPayload();

        handleMigrated(event, payload, stored, current);
    }

    /**
     * Called after the payload has been migrated to {@link #currentSchemaVersion()}.
     *
     * @param event       the raw outbox event row; read the payload from the
     *                    {@code payload} parameter, not from {@code event.getPayload()}
     * @param payload     JSON payload at {@link #currentSchemaVersion()}
     * @param fromVersion the schema version the payload was stored at
     * @param toVersion   the schema version after migration (== {@link #currentSchemaVersion()})
     * @throws Exception on any unrecoverable processing failure
     */
    protected abstract void handleMigrated(OutboxEvent event, String payload,
                                           int fromVersion, int toVersion) throws Exception;
}
