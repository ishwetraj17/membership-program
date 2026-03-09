package com.firstclub.events.schema;

/**
 * Migrates an event payload from one schema version to the next (exactly one step).
 *
 * <p>Implementations must be registered as Spring beans so that
 * {@link PayloadMigrationRegistry} can discover them automatically at startup.
 * Each migrator covers exactly one version step:
 * {@link #fromVersion()} → {@link #toVersion()}, where
 * {@code toVersion() == fromVersion() + 1} (validated at startup).
 *
 * <p>Implementations must be <strong>stateless</strong> and
 * <strong>deterministic</strong>: given the same input payload they must always
 * produce the same output.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * @Component
 * public class InvoiceCreatedV1ToV2Migrator implements PayloadMigrator {
 *
 *     private final ObjectMapper mapper;
 *
 *     @Override public String eventType()  { return "INVOICE_CREATED"; }
 *     @Override public int    fromVersion() { return 1; }
 *     @Override public int    toVersion()   { return 2; }
 *
 *     @Override
 *     public String migrate(String payload) {
 *         // add 'currency' field with default "INR" for old payloads
 *         ObjectNode node = (ObjectNode) mapper.readTree(payload);
 *         if (!node.has("currency")) node.put("currency", "INR");
 *         return mapper.writeValueAsString(node);
 *     }
 * }
 * }</pre>
 */
public interface PayloadMigrator {

    /** Event type this migrator applies to (e.g. {@code "INVOICE_CREATED"}). */
    String eventType();

    /** Source schema version (the version of the incoming payload). */
    int fromVersion();

    /**
     * Target schema version after migration.
     * Must equal {@code fromVersion() + 1} — validated at startup by
     * {@link PayloadMigrationRegistry}.
     */
    int toVersion();

    /**
     * Transforms a JSON payload string from {@link #fromVersion()} format to
     * {@link #toVersion()} format.
     *
     * @param payload raw JSON at the source schema version
     * @return transformed JSON at the target schema version
     * @throws RuntimeException if the payload is malformed or the migration fails
     */
    String migrate(String payload);
}
