package com.firstclub.integrity;

/**
 * Contract that every pluggable invariant checker must implement.
 *
 * <h3>Rules for implementors</h3>
 * <ol>
 *   <li>Never throw checked or unchecked exceptions from {@link #check()};
 *       wrap unexpected failures in {@link InvariantResult#error}.</li>
 *   <li>Be stateless — the same instance is called repeatedly by
 *       {@link InvariantEngine}.</li>
 *   <li>Annotate implementations with {@code @Component} so Spring auto-registers
 *       them in the engine's {@code List<InvariantChecker>} dependency.</li>
 *   <li>Keep queries read-only; never mutate data inside a checker.</li>
 * </ol>
 */
public interface InvariantChecker {

    /**
     * Stable, machine-readable name for this invariant (UPPER_SNAKE_CASE).
     * Used as the primary key in persisted {@code integrity_check_results}.
     */
    String getName();

    /**
     * Operational severity when this invariant fails.
     */
    InvariantSeverity getSeverity();

    /**
     * Run the check and return the aggregate result.
     * <p>Implementations MUST NOT throw — all exceptional paths must be returned
     * as {@link InvariantResult#error(String, InvariantSeverity, String)}.
     */
    InvariantResult check();
}
