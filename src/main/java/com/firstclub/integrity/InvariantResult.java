package com.firstclub.integrity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Immutable output of a single {@link InvariantChecker#check()} call.
 *
 * <p>Use the factory methods ({@link #pass}, {@link #fail}, {@link #error}) to
 * construct instances; do not call the constructor directly.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class InvariantResult {

    private final String          invariantName;
    private final InvariantStatus status;
    private final InvariantSeverity severity;
    private final List<InvariantViolation> violations;
    /** Non-null only when {@code status == ERROR}. */
    private final String          errorMessage;

    // ── Computed ─────────────────────────────────────────────────────────────

    public int getViolationCount() {
        return violations == null ? 0 : violations.size();
    }

    public boolean isPassed() {
        return status == InvariantStatus.PASS;
    }

    public boolean isFailed() {
        return status == InvariantStatus.FAIL;
    }

    public boolean isError() {
        return status == InvariantStatus.ERROR;
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * All rows satisfied the invariant — no violations found.
     */
    public static InvariantResult pass(String name, InvariantSeverity severity) {
        return new InvariantResult(name, InvariantStatus.PASS, severity, List.of(), null);
    }

    /**
     * One or more violations were found.
     *
     * @param violations  must not be null or empty
     */
    public static InvariantResult fail(String name,
                                       InvariantSeverity severity,
                                       List<InvariantViolation> violations) {
        return new InvariantResult(name, InvariantStatus.FAIL, severity,
                List.copyOf(violations), null);
    }

    /**
     * The checker itself threw an unexpected exception.
     *
     * @param errorMessage  {@code e.getMessage()} from the caught exception
     */
    public static InvariantResult error(String name,
                                        InvariantSeverity severity,
                                        String errorMessage) {
        return new InvariantResult(name, InvariantStatus.ERROR, severity, List.of(), errorMessage);
    }
}
