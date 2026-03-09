package com.firstclub.events.schema;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Central registry for {@link PayloadMigrator} instances.
 *
 * <p>All Spring beans implementing {@link PayloadMigrator} are injected via the
 * constructor.  At startup the registry validates:
 * <ol>
 *   <li>Each migrator satisfies {@code toVersion() == fromVersion() + 1}.</li>
 *   <li>No two migrators share the same {@code (eventType, fromVersion)} key.</li>
 * </ol>
 *
 * <p>Use {@link #migrate} to bring a stored payload up to the current schema
 * version by chaining all required migration steps in order.
 *
 * <p>If no migrators are registered for an event type, that type is treated as
 * always at version {@code 1} and no migration is ever applied.
 */
@Component
@Slf4j
public class PayloadMigrationRegistry {

    /** "{eventType}:{fromVersion}" → migrator */
    private final Map<String, PayloadMigrator> migrators;

    /** eventType → highest registered toVersion (= current version for that type) */
    private final Map<String, Integer> currentVersions;

    public PayloadMigrationRegistry(List<PayloadMigrator> migratorList) {
        Map<String, PayloadMigrator> idx    = new LinkedHashMap<>();
        Map<String, Integer>         maxVer = new HashMap<>();

        for (PayloadMigrator m : migratorList) {
            if (m.toVersion() != m.fromVersion() + 1) {
                throw new IllegalStateException(String.format(
                        "PayloadMigrator %s must satisfy toVersion = fromVersion + 1 " +
                        "(from=%d, to=%d)",
                        m.getClass().getSimpleName(), m.fromVersion(), m.toVersion()));
            }
            String key = key(m.eventType(), m.fromVersion());
            if (idx.containsKey(key)) {
                throw new IllegalStateException(
                        "Duplicate PayloadMigrator for " + key
                        + ": " + m.getClass().getSimpleName());
            }
            idx.put(key, m);
            maxVer.merge(m.eventType(), m.toVersion(), Math::max);
        }

        this.migrators      = Collections.unmodifiableMap(idx);
        this.currentVersions = Collections.unmodifiableMap(maxVer);

        if (!migratorList.isEmpty()) {
            log.info("PayloadMigrationRegistry: {} migrator(s) loaded, eventTypes={}",
                    migratorList.size(), maxVer.keySet());
        }
    }

    /**
     * Returns the current schema version for {@code eventType}.
     * Defaults to {@code 1} if no migrators are registered for this type.
     */
    public int currentVersion(String eventType) {
        return currentVersions.getOrDefault(eventType, 1);
    }

    /**
     * Returns {@code true} when the stored version is behind the current version.
     */
    public boolean needsMigration(String eventType, int storedVersion) {
        return storedVersion < currentVersion(eventType);
    }

    /**
     * Chains migrators from {@code storedVersion} up to the current version for
     * {@code eventType}, transforming {@code payload} at each step in sequence.
     *
     * <p>Returns the original payload unchanged if no migration is needed.
     *
     * @throws IllegalStateException if a required migration step is missing
     */
    public String migrate(String payload, String eventType, int storedVersion) {
        int target = currentVersion(eventType);
        if (storedVersion >= target) {
            return payload;
        }

        String current = payload;
        for (int v = storedVersion; v < target; v++) {
            PayloadMigrator m = migrators.get(key(eventType, v));
            if (m == null) {
                throw new IllegalStateException(String.format(
                        "No PayloadMigrator registered for %s v%d→v%d",
                        eventType, v, v + 1));
            }
            current = m.migrate(current);
            log.debug("Migrated {} payload v{}→v{}", eventType, v, v + 1);
        }

        log.info("Payload migration complete: eventType={} v{}→v{}", eventType, storedVersion, target);
        return current;
    }

    private static String key(String eventType, int fromVersion) {
        return eventType + ":" + fromVersion;
    }
}
