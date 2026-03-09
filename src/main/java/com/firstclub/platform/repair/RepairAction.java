package com.firstclub.platform.repair;

import org.springframework.lang.Nullable;

/**
 * Contract every repair action must implement.
 *
 * <p>A repair action is a controlled, auditable mutation of <em>derived</em>
 * state (projections, queue records, snapshot tables) or a safe retry of a
 * recoverable async operation.  It must never directly mutate immutable
 * financial source-of-truth rows (e.g. {@code payments}, {@code refunds},
 * {@code ledger_entries}) unless the mutation is an explicitly allowed
 * compensating / derived-state rebuild.
 */
public interface RepairAction {

    /**
     * Unique stable key, e.g. {@code "repair.invoice.recompute_totals"}.
     * This key is stored in the audit log and must never be changed once
     * the action is deployed.
     */
    String getRepairKey();

    /**
     * Human-readable name of the entity type this action targets,
     * e.g. {@code "INVOICE"}, {@code "OUTBOX_EVENT"}.
     */
    String getTargetType();

    /**
     * Whether this action supports dry-run mode.
     * Dry-run executions read and compute but do not persist any changes.
     */
    boolean supportsDryRun();

    /**
     * Execute the repair action.
     *
     * @param context all parameters needed to execute the repair
     * @return the result, including before/after snapshots and a summary
     */
    RepairActionResult execute(RepairContext context);

    // ── Inner context record ──────────────────────────────────────────────────

    /**
     * Immutable execution context passed into every repair action.
     */
    record RepairContext(
            /** Primary target identifier (invoice id, event id, etc.). */
            @Nullable String targetId,

            /**
             * Secondary parameter map — keys and semantics are action-specific.
             * Example: {@code {"date": "2024-03-15", "merchantId": "42"}}.
             */
            java.util.Map<String, String> params,

            /** When true the action must not persist any changes. */
            boolean dryRun,

            /** Id of the admin user who triggered the repair, for audit. */
            @Nullable Long actorUserId,

            /** Optional human-readable reason logged in the audit trail. */
            @Nullable String reason
    ) {
        public RepairContext {
            params = params != null ? java.util.Collections.unmodifiableMap(params) : java.util.Map.of();
        }

        public String param(String key) {
            return params.get(key);
        }

        public String paramOrDefault(String key, String defaultValue) {
            return params.getOrDefault(key, defaultValue);
        }
    }
}
